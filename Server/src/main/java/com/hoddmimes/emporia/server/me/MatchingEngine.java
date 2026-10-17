package com.hoddmimes.emporia.server.me;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Matching Engine — manages a set of orderbooks, one per instrument, created on first use.
 * Implements {@link MatchingEngineCallbackIf} to receive callbacks from the orderbooks and
 * broadcasts every orderbook change as bdx messages (see {@link BdxMessage}).
 *
 * Prices and quantities are longs scaled by {@link #SCALE}. Orders and books are held in memory
 * only. Requests arrive concurrently from the REST endpoints and the auction scheduler; each order
 * book has its own lock (synchronized on the Orderbook), held around every call into the book, so
 * orders in different instruments are matched in parallel and those in one instrument one at a time.
 * The state shared by the books is thread-safe: the book map, the index of resting orders (order id
 * to instrument, so a modify or delete goes straight to its book; checked again under the book's
 * lock) and today's trades. A request spanning several books (a market's or a firm's orders, a
 * market's prices, the uncross at open) locks one book at a time, so it is no atomic snapshot of
 * them all.
 *
 * Trades must survive a host failure, so they are stored durably, and nobody hears of a trade before
 * it is stored: the engine queues its bdx messages and trades in a {@link TradePersister} instead of
 * publishing and storing them itself. The persister stores each batch of trades with one journaled
 * write outside the books' locks and then publishes the batch's messages in order (a book's messages
 * are queued under its lock, so they keep its seqNo order). addOrder, modifyOrder and triggerAuction
 * wait, after leaving the book's lock, until a trade they made is stored and its messages published
 * (durable).
 *
 * An order's account is the firm that owns it; a firm can only see, modify and delete its own orders.
 * Orders are only accepted (added or modified) while the market is OPEN or PREOPEN and the
 * instrument is not SUSPENDED; deleting an order is allowed in every market state except CLOSED.
 * In PREOPEN orders are only put in the book; when the market changes to OPEN its continuous
 * books are uncrossed once at a single clearing price, after which continuous matching applies.
 */
public class MatchingEngine implements MatchingEngineCallbackIf {

    private static final Logger cLog = LogManager.getLogger(MatchingEngine.class);

    /** Prices and quantities are stored as longs scaled by this factor (10.50 is stored as 1050000). */
    public static final long SCALE = 100000L;

    public static final String MARKET_OPEN       = "OPEN";
    public static final String MARKET_PREOPEN    = "PREOPEN";
    public static final String MARKET_CLOSED     = "CLOSED";
    public static final String INSTRUMENT_SUSPENDED = "SUSPENDED";

    private final InstrumentCache         mInstrumentCache;
    private final Map<Long, Orderbook>    mOrderbooks;   // instrument id -> book
    private final Map<Long, Long>         mOrderIndex;   // resting order id -> instrument id
    private final List<InternalTrade>     mTrades;       // today's trades, synchronized list
    private final Database mDB;
    private final TradePersister          mPersister;

    public MatchingEngine(InstrumentCache pInstrumentCache, Database pDB, BdxDistributor pBdx) {
        mInstrumentCache = pInstrumentCache;
        mDB              = pDB;
        mOrderbooks      = new ConcurrentHashMap<>();
        mOrderIndex      = new ConcurrentHashMap<>();
        mTrades          = Collections.synchronizedList(new ArrayList<>());
        // Without a database (unit tests) messages are published at once and trades are not stored
        mPersister       = new TradePersister((mDB != null) ? mDB::saveTrades : null, pBdx::publish);
        mPersister.startPersisting();

        mInstrumentCache.addMarketSubscription(new InstrumentCache.MarketCallbacks() {
            @Override
            public void marketStateChange(int pMarketId, String pMarketName, String pState) {
                if (MARKET_OPEN.equals(pState)) uncrossAtOpen(pMarketId);
            }
            @Override public void marketAdded(JsonObject pMarket)   {}
            @Override public void marketUpdated(JsonObject pMarket) {}
        });

        // Without a database (unit tests) trades are neither reloaded nor stored
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
        if (mDB != null) restoreTrades(mDB.listTradesByDate(simpleDateFormat.format(new Date())));
    }

    /**
     * Takes over today's trades reloaded at startup: adds them to the trade list and replays them,
     * oldest first, into the statistics (open, high, last, volume) of their instruments' order books
     * (creating the books), then publishes a PriceUpdate per instrument so the bdx snapshot and
     * getPrices show the day's prices before the first new order. The order books themselves are
     * empty, since orders are not stored. Reversal trades are not replayed: they are no market prices.
     */
    void restoreTrades(List<InternalTrade> pTrades) {
        mTrades.addAll(pTrades);
        List<InternalTrade> tOldestFirst = new ArrayList<>(pTrades);
        tOldestFirst.sort(Comparator.comparingLong(InternalTrade::getTradeTime));
        Set<Orderbook> tBooks = new LinkedHashSet<>();
        for (InternalTrade tTrade : tOldestFirst) {
            if (tTrade.isReversal()) continue;
            Orderbook tBook = getOrCreateOrderbook(tTrade.getInstrumentId());
            if (tBook == null) continue;   // the instrument no longer exists
            synchronized (tBook) {
                tBook.restoreTrade(tTrade.getPrice(), tTrade.getQuantity());
            }
            tBooks.add(tBook);
        }
        for (Orderbook tBook : tBooks) {
            synchronized (tBook) {
                orderbookChanged(tBook.getInstrumentId());
            }
        }
        cLog.info("{} trade(s) of today reloaded, prices restored for {} instrument(s)", pTrades.size(), tBooks.size());
    }

    /** Stores and publishes what is queued and stops the trade persister (server stop). */
    public void stop() {
        mPersister.stopPersisting(10000);
    }

    /**
     * Runs pCall under the book's lock and, if the book traded, waits after leaving the lock until its
     * trades are stored and its messages published, so the caller's answer never reports a trade that
     * is not on disk. Other requests, in this book too, are matched meanwhile.
     */
    private <T> T durable(Orderbook pBook, Supplier<T> pCall) {
        T    tResult;
        long tWaitFor = 0;
        synchronized (pBook) {
            int tExecutions = pBook.getExecutionCount();
            tResult = pCall.get();
            // Taken after this call's items were queued; may also cover other books' items queued before
            if (pBook.getExecutionCount() != tExecutions) tWaitFor = mPersister.lastQueued();
        }
        mPersister.awaitReleased(tWaitFor);
        return tResult;
    }

    // ── Public order operations ────────────────────────────────────────────

    public OrderResponse addOrder(AddOrderRequest pReq) {
        String tErr = checkTradingStatus(pReq.instrumentId());   // before a book is created for the order
        if (tErr != null) return OrderResponse.error(tErr);
        Orderbook tBook = getOrCreateOrderbook(pReq.instrumentId());
        if (tBook == null) {
            return OrderResponse.error("Instrument not found: " + pReq.instrumentId());
        }
        return durable(tBook, () -> {
            // Checked again under the book's lock, which the uncross at open takes after the status changed
            String tStatusErr = checkTradingStatus(pReq.instrumentId());
            if (tStatusErr != null) return OrderResponse.error(tStatusErr);
            return tBook.addOrder(pReq, isMarketOpen(pReq.instrumentId()));
        });
    }

    /** Modifies one of the firm's resting orders; pPrice 0 keeps the current price, a null pUserRef the current userRef. */
    public OrderResponse modifyOrder(String pFirm, String pOrderId, long pPrice, long pQuantity, String pUserRef) {
        long      tOrderId = parseOrderId(pOrderId);
        Orderbook tBook    = orderbookOf(tOrderId);
        if (tBook == null) return OrderResponse.error("Order not found: " + pOrderId);
        return durable(tBook, () -> {
            if (!isFirmsOrder(tBook, pFirm, tOrderId)) return OrderResponse.error("Order not found: " + pOrderId);
            String tErr = checkTradingStatus(tBook.getInstrumentId());
            if (tErr != null) return OrderResponse.error(tErr);
            return tBook.modifyOrder(tOrderId, pPrice, pQuantity, pUserRef, isMarketOpen(tBook.getInstrumentId()));
        });
    }

    public OrderResponse deleteOrder(String pFirm, String pOrderId) {
        long      tOrderId = parseOrderId(pOrderId);
        Orderbook tBook    = orderbookOf(tOrderId);
        if (tBook == null) return OrderResponse.error("Order not found: " + pOrderId);
        synchronized (tBook) {
            if (!isFirmsOrder(tBook, pFirm, tOrderId)) return OrderResponse.error("Order not found: " + pOrderId);
            InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(tBook.getInstrumentId());
            InstrumentCache.MarketEntry tMarket = (tInstrument != null) ? mInstrumentCache.getMarket(tInstrument.marketId()) : null;
            if (tMarket != null && MARKET_CLOSED.equals(tMarket.tradingStatus())) {
                return OrderResponse.error("Market " + tMarket.name() + " is CLOSED, orders can not be deleted");
            }
            return tBook.deleteOrder(tOrderId);
        }
    }

    /**
     * Admin: deletes every resting order in the market's order books, of all firms and in any market
     * state (no authorization or trading status checks). Each removal is published like a normal
     * delete. Returns the number of orders deleted.
     */
    public int deleteAllOrders(int pMarketId) {
        int tDeleted = 0;
        for (Orderbook tBook : mOrderbooks.values()) {
            if (marketIdOf(tBook.getInstrumentId()) != pMarketId) continue;
            synchronized (tBook) {
                List<Order> tOrders = new ArrayList<>(tBook.getBuySide().getOrders());
                tOrders.addAll(tBook.getSellSide().getOrders());
                for (Order tOrder : tOrders) {
                    if (tBook.deleteOrder(tOrder.getOrderId()).success()) tDeleted++;
                }
            }
        }
        cLog.info("Admin deleted all orders in market {}: {} order(s)", pMarketId, tDeleted);
        return tDeleted;
    }

    /**
     * Market supervision (admin): deletes any firm's resting order, in any market state (no authorization
     * or trading status checks). The removal is published like a normal delete, so the firm gets its
     * OwnOrder REMOVED.
     */
    public OrderResponse supervisionDeleteOrder(String pOrderId) {
        long      tOrderId = parseOrderId(pOrderId);
        Orderbook tBook    = orderbookOf(tOrderId);
        if (tBook == null) return OrderResponse.error("Order not found: " + pOrderId);
        synchronized (tBook) {
            if (tBook.getOrder(tOrderId) == null) return OrderResponse.error("Order not found: " + pOrderId);
            return tBook.deleteOrder(tOrderId);
        }
    }

    /**
     * Market supervision (admin): the resting orders of all firms, or of one firm (pFirm null: all), in one
     * market or (pMarketId 0) all markets, sorted by market, instrument, side and price priority.
     */
    public JsonArray getAllOrders(int pMarketId, String pFirm) {
        List<Orderbook> tBooks = new ArrayList<>();
        for (Orderbook tBook : mOrderbooks.values()) {
            if (pMarketId == 0 || marketIdOf(tBook.getInstrumentId()) == pMarketId) tBooks.add(tBook);
        }
        tBooks.sort(Comparator.comparingLong(Orderbook::getInstrumentId));
        JsonArray tResult = new JsonArray();
        for (Orderbook tBook : tBooks) {
            synchronized (tBook) {
                List<Order> tOrders = new ArrayList<>(tBook.getBuySide().getOrders());
                tOrders.addAll(tBook.getSellSide().getOrders());
                for (Order tOrder : tOrders) {
                    if (pFirm == null || pFirm.equals(tOrder.getAccount())) tResult.add(orderToJson(tOrder));
                }
            }
        }
        return tResult;
    }

    /**
     * Market supervision (admin): makes the trade reversing pOriginal (InternalTrade.reversal: same
     * instrument, price and quantity, buyer and seller swapped), stores it durably and sends each firm its
     * OwnTrade (with reversalOf), then returns it. It does not touch the order book: no public Trade, no
     * change of the open/high/last/volume statistics. The caller checks that pOriginal may be reversed.
     */
    public InternalTrade reverseTrade(InternalTrade pOriginal) {
        InternalTrade tReversal = InternalTrade.reversal(pOriginal);
        int tMarketId = marketIdOf(tReversal.getInstrumentId());
        long tWaitFor;
        synchronized (mTrades) {   // the trade and its messages queued together, in this order
            mTrades.add(tReversal);
            mPersister.trade(tReversal);
            publishOwnTrade(tReversal, tMarketId, Order.Side.BUY);
            publishOwnTrade(tReversal, tMarketId, Order.Side.SELL);
            tWaitFor = mPersister.lastQueued();
        }
        mPersister.awaitReleased(tWaitFor);
        cLog.info("Trade {} reversed by trade {}", tReversal.getReversalOf(), tReversal);
        return tReversal;
    }

    /** The number of resting orders in the market's order books. */
    public int orderCount(int pMarketId) {
        int tCount = 0;
        for (Orderbook tBook : mOrderbooks.values()) {
            if (marketIdOf(tBook.getInstrumentId()) == pMarketId) {
                synchronized (tBook) {
                    tCount += tBook.getBuySideDepth() + tBook.getSellSideDepth();
                }
            }
        }
        return tCount;
    }

    /** The instrument an order belongs to, if it is one of the firm's resting orders; 0 otherwise. */
    public long getOrderInstrumentId(String pFirm, String pOrderId) {
        long      tOrderId = parseOrderId(pOrderId);
        Orderbook tBook    = orderbookOf(tOrderId);
        if (tBook == null) return 0L;
        synchronized (tBook) {
            return isFirmsOrder(tBook, pFirm, tOrderId) ? tBook.getInstrumentId() : 0L;
        }
    }

    public AuctionResult triggerAuction(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook == null) {
            return AuctionResult.error("No orderbook for instrument: " + pInstrumentId);
        }
        if (!Orderbook.TYPE_AUCTION.equals(tBook.getType())) {
            return AuctionResult.error("Not an auction book: " + pInstrumentId);
        }
        AuctionResult tResult = durable(tBook, tBook::triggerAuction);
        if (tResult.success()) {
            cLog.info("Auction triggered for instrument {}, matched volume: {}",
                    pInstrumentId, tResult.matchedVolume());
        }
        return tResult;
    }

    /** The firm's resting orders, for one instrument or (pInstrumentId 0) all instruments. */
    public JsonArray getOrdersByFirm(String pFirm, long pInstrumentId) {
        return getOrdersByFirm(pFirm, 0, pInstrumentId);
    }

    /** The firm's resting orders, optionally only for one market and/or one instrument (0 = all). */
    public JsonArray getOrdersByFirm(String pFirm, int pMarketId, long pInstrumentId) {
        JsonArray tResult = new JsonArray();
        for (Map.Entry<Long, Orderbook> tEntry : mOrderbooks.entrySet()) {
            if (pInstrumentId != 0 && tEntry.getKey() != pInstrumentId) continue;
            if (pMarketId != 0 && marketIdOf(tEntry.getKey()) != pMarketId) continue;
            Orderbook tBook = tEntry.getValue();
            synchronized (tBook) {
                for (Order tOrder : tBook.getBuySide().getOrders()) {
                    if (pFirm.equals(tOrder.getAccount())) tResult.add(orderToJson(tOrder));
                }
                for (Order tOrder : tBook.getSellSide().getOrders()) {
                    if (pFirm.equals(tOrder.getAccount())) tResult.add(orderToJson(tOrder));
                }
            }
        }
        return tResult;
    }

    /** The firm's trades, for one instrument or (pInstrumentId 0) all instruments. */
    public JsonArray getTradesByFirm(String pFirm, long pInstrumentId) {
        return getTradesByFirm(pFirm, 0, pInstrumentId);
    }

    /** The firm's trades, oldest first, optionally only for one market and/or one instrument (0 = all). */
    public JsonArray getTradesByFirm(String pFirm, int pMarketId, long pInstrumentId) {
        return tradesForFirm(todaysTrades(), pFirm, pMarketId, pInstrumentId);
    }

    /** A copy of today's trades, in the order they were made. */
    private List<InternalTrade> todaysTrades() {
        synchronized (mTrades) {
            return new ArrayList<>(mTrades);
        }
    }

    /**
     * The firm's side of the given trades, in their order and filtered like getTradesByFirm; used for
     * the stored trades of a chosen day (Database.listTradesByFirm).
     */
    public JsonArray tradesForFirm(List<InternalTrade> pTrades, String pFirm, int pMarketId, long pInstrumentId) {
        JsonArray tResult = new JsonArray();
        for (InternalTrade tTrade : pTrades) {
            if (pInstrumentId != 0 && tTrade.getInstrumentId() != pInstrumentId) continue;
            if (pMarketId != 0 && marketIdOf(tTrade.getInstrumentId()) != pMarketId) continue;
            boolean tIsBuyer  = pFirm.equals(tTrade.getBuyOrder().getAccount());
            boolean tIsSeller = pFirm.equals(tTrade.getSellOrder().getAccount());
            // A firm trading with itself sees both sides
            if (tIsBuyer)  tResult.add(tradeToJsonForFirm(tTrade, Order.Side.BUY));
            if (tIsSeller) tResult.add(tradeToJsonForFirm(tTrade, Order.Side.SELL));
        }
        return tResult;
    }

    /**
     * The market's trades, oldest first, optionally for one instrument (0 = all): anonymous, without
     * firms, order ids or userRefs.
     */
    public JsonArray getPublicTrades(int pMarketId, long pInstrumentId) {
        JsonArray tResult = new JsonArray();
        for (InternalTrade tTrade : todaysTrades()) {
            if (tTrade.isReversal()) continue;   // no market trade
            if (pInstrumentId != 0 && tTrade.getInstrumentId() != pInstrumentId) continue;
            if (marketIdOf(tTrade.getInstrumentId()) != pMarketId) continue;
            JsonObject tJson = instrumentJson(tTrade.getInstrumentId());
            tJson.addProperty("price",     tTrade.getPrice());
            tJson.addProperty("quantity",  tTrade.getQuantity());
            tJson.addProperty("tradeNo",   Long.toHexString(tTrade.getTradeNo()));
            tJson.addProperty("tradeTime", tTrade.getTradeTime());
            tResult.add(tJson);
        }
        return tResult;
    }

    /**
     * A price snapshot of every instrument in the market, sorted by name, with the same fields as the
     * PriceUpdate bdx message but scaled like the rest of the REST API: best bid/ask price and volume,
     * open, high, last and traded volume. A value is JSON null when there is none (empty book side, no
     * trades yet, or no order book yet).
     */
    public JsonArray getPrices(int pMarketId) {
        List<InstrumentCache.InstrumentEntry> tInstruments = new ArrayList<>(mInstrumentCache.getInstrumentsByMarket(pMarketId));
        tInstruments.sort(Comparator.comparing(InstrumentCache.InstrumentEntry::name));
        JsonArray tResult = new JsonArray();
        for (InstrumentCache.InstrumentEntry tInstrument : tInstruments) {
            Orderbook tBook = mOrderbooks.get(tInstrument.instrumentId());
            if (tBook == null) {
                tResult.add(priceJson(tInstrument.instrumentId(), null));
                continue;
            }
            synchronized (tBook) {
                tResult.add(priceJson(tInstrument.instrumentId(), tBook));
            }
        }
        return tResult;
    }

    /** One instrument's entry in getPrices; pBook (null if none) locked by the caller. */
    private JsonObject priceJson(long pInstrumentId, Orderbook pBook) {
        Order     tBestBuy  = (pBook != null) ? pBook.getBuySide().peek()  : null;
        Order     tBestSell = (pBook != null) ? pBook.getSellSide().peek() : null;
        boolean   tTraded   = pBook != null && pBook.getTradedVolume() > 0;
        JsonObject tJson = instrumentJson(pInstrumentId);
        tJson.addProperty("bidVol", tBestBuy  != null ? pBook.getBestPriceVolume(Order.Side.BUY)  : null);
        tJson.addProperty("bidPx",  tBestBuy  != null ? tBestBuy.getPrice()                       : null);
        tJson.addProperty("askVol", tBestSell != null ? pBook.getBestPriceVolume(Order.Side.SELL) : null);
        tJson.addProperty("askPx",  tBestSell != null ? tBestSell.getPrice()                      : null);
        tJson.addProperty("open",   tTraded ? pBook.getOpenPrice()      : null);
        tJson.addProperty("high",   tTraded ? pBook.getHighPrice()      : null);
        tJson.addProperty("last",   tTraded ? pBook.getLastTradePrice() : null);
        tJson.addProperty("volume", (pBook != null) ? pBook.getTradedVolume() : 0L);
        return tJson;
    }

    /** The instrument's last traded price today (scaled), 0 if it has not traded today. */
    public long getLastTradePrice(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook == null) return 0L;
        synchronized (tBook) {
            return (tBook.getTradedVolume() > 0) ? tBook.getLastTradePrice() : 0L;
        }
    }

    /**
     * A snapshot of the instrument's whole order book, anonymous, aggregated per price level: "bids" and
     * "asks", best price first, each level with price, quantity (total), orders (count) and own (the
     * quantity of pFirm's orders at the level), plus open, high, last (JSON null if not traded today) and
     * volume. Prices and quantities scaled. Empty sides if the instrument has no order book yet.
     */
    public JsonObject getOrderbook(long pInstrumentId, String pFirm) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook == null) return orderbookJson(pInstrumentId, null, pFirm);
        synchronized (tBook) {
            return orderbookJson(pInstrumentId, tBook, pFirm);
        }
    }

    /** getOrderbook's snapshot; pBook (null if none) locked by the caller. */
    private JsonObject orderbookJson(long pInstrumentId, Orderbook pBook, String pFirm) {
        boolean   tTraded = pBook != null && pBook.getTradedVolume() > 0;
        JsonObject tJson = instrumentJson(pInstrumentId);
        tJson.add("bids", priceLevels(pBook != null ? pBook.getBuySide().getOrders()  : List.of(), pFirm));
        tJson.add("asks", priceLevels(pBook != null ? pBook.getSellSide().getOrders() : List.of(), pFirm));
        tJson.addProperty("open",   tTraded ? pBook.getOpenPrice()      : null);
        tJson.addProperty("high",   tTraded ? pBook.getHighPrice()      : null);
        tJson.addProperty("last",   tTraded ? pBook.getLastTradePrice() : null);
        tJson.addProperty("volume", (pBook != null) ? pBook.getTradedVolume() : 0L);
        tJson.addProperty("time",   System.currentTimeMillis());
        return tJson;
    }

    /** One side's orders (in priority order, so best price first) aggregated per price. */
    private static JsonArray priceLevels(List<Order> pOrders, String pFirm) {
        JsonArray  tLevels = new JsonArray();
        JsonObject tLevel  = null;
        for (Order o : pOrders) {
            if (tLevel == null || tLevel.get("price").getAsLong() != o.getPrice()) {
                tLevel = new JsonObject();
                tLevel.addProperty("price",    o.getPrice());
                tLevel.addProperty("quantity", 0L);
                tLevel.addProperty("orders",   0);
                tLevel.addProperty("own",      0L);
                tLevels.add(tLevel);
            }
            tLevel.addProperty("quantity", tLevel.get("quantity").getAsLong() + o.getQuantity());
            tLevel.addProperty("orders",   tLevel.get("orders").getAsInt() + 1);
            if (o.getAccount().equals(pFirm)) tLevel.addProperty("own", tLevel.get("own").getAsLong() + o.getQuantity());
        }
        return tLevels;
    }

    // ── MatchingEngineCallbackIf ───────────────────────────────────────────
    // Every book change is broadcast: an anonymous order book event (with the book's sequence
    // number) to all clients authorized for the market, an OwnOrder/OwnTrade to the owning firm,
    // and a PriceUpdate snapshot after each request. The books call them under their own lock.

    @Override
    public void orderAdded(Order pOrder, long pSeqNo) {
        cLog.debug("Bdx Order added: {}", pOrder);
        mOrderIndex.put(pOrder.getOrderId(), pOrder.getInstrumentId());
        publishOrder(BdxMessage.ADD_ORDER, "ADDED", pOrder, pSeqNo);
    }

    @Override
    public void orderRemoved(Order pOrder, long pSeqNo) {
        cLog.debug("Bdx Order removed: {}", pOrder);
        mOrderIndex.remove(pOrder.getOrderId());
        publishOrder(BdxMessage.REMOVE_ORDER, "REMOVED", pOrder, pSeqNo);
    }

    @Override
    public void orderModified(Order pOrder, long pSeqNo) {
        cLog.debug("Bdx Order modified: {}", pOrder);
        publishOrder(BdxMessage.MODIFY_ORDER, "MODIFIED", pOrder, pSeqNo);
    }

    @Override
    public void trade(InternalTrade pTrade) {
        mTrades.add(pTrade);
        cLog.info("Bdx public trade {}", pTrade);

        int tMarketId = marketIdOf(pTrade.getInstrumentId());

        // Stored before the messages below are published (TradePersister)
        mPersister.trade(pTrade);

        mPersister.publish(BdxMessage.instrument(BdxMessage.TRADE, tMarketId, pTrade.getInstrumentId())
                .put("price",     unscale(pTrade.getPrice()))
                .put("quantity",  unscale(pTrade.getQuantity()))
                .put("tradeNo",   Long.toHexString(pTrade.getTradeNo()))
                .put("tradeTime", pTrade.getTradeTime()));
        publishOwnTrade(pTrade, tMarketId, Order.Side.BUY);
        publishOwnTrade(pTrade, tMarketId, Order.Side.SELL);

    }

    @Override
    public void newOrderMatched(Order pOrder, long pSeqNo) {
        cLog.debug("New order fully matched: {}", Long.toHexString(pOrder.getOrderId()));
    }

    @Override
    public void orderbookChanged(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook == null) return;

        Order tBestBuy  = tBook.getBuySide().peek();
        Order tBestSell = tBook.getSellSide().peek();
        boolean tTraded = tBook.getTradedVolume() > 0;

        // A full snapshot: null (empty book side, no trades yet) clears the client's value
        mPersister.publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, marketIdOf(pInstrumentId), pInstrumentId)
                .put("bidVol", tBestBuy  != null ? unscale(tBook.getBestPriceVolume(Order.Side.BUY))  : null)
                .put("bidPx",  tBestBuy  != null ? unscale(tBestBuy.getPrice())                        : null)
                .put("askVol", tBestSell != null ? unscale(tBook.getBestPriceVolume(Order.Side.SELL)) : null)
                .put("askPx",  tBestSell != null ? unscale(tBestSell.getPrice())                       : null)
                .put("open",   tTraded ? unscale(tBook.getOpenPrice())      : null)
                .put("high",   tTraded ? unscale(tBook.getHighPrice())      : null)
                .put("last",   tTraded ? unscale(tBook.getLastTradePrice()) : null)
                .put("volume", unscale(tBook.getTradedVolume())));
    }

    /** The public order book event and the private OwnOrder to the order's firm. */
    private void publishOrder(String pType, String pEvent, Order pOrder, long pSeqNo) {
        int tMarketId = marketIdOf(pOrder.getInstrumentId());
        mPersister.publish(orderMessage(BdxMessage.instrument(pType, tMarketId, pOrder.getInstrumentId()), pOrder, pSeqNo));
        mPersister.publish(orderMessage(BdxMessage.forFirm(BdxMessage.OWN_ORDER, tMarketId, pOrder.getInstrumentId(), pOrder.getAccount()), pOrder, pSeqNo)
                .put("event",   pEvent)
                .put("userRef", pOrder.getUserRef())
                .put("clientAccount", pOrder.getClientAccount()));
    }

    private static BdxMessage orderMessage(BdxMessage pMessage, Order pOrder, long pSeqNo) {
        return pMessage.put("seqNo",    pSeqNo)
                       .put("orderId",  Long.toHexString(pOrder.getOrderId()))
                       .put("side",     pOrder.getSide().name())
                       .put("price",    unscale(pOrder.getPrice()))
                       .put("quantity", unscale(pOrder.getQuantity()));   // remaining; 0 when removed fully filled
    }

    /** The private OwnTrade for one side of a trade, to the firm of that side's order (a reversal's has reversalOf). */
    private void publishOwnTrade(InternalTrade pTrade, int pMarketId, Order.Side pSide) {
        Order tOrder = (pSide == Order.Side.BUY) ? pTrade.getBuyOrder() : pTrade.getSellOrder();
        BdxMessage tMessage = BdxMessage.forFirm(BdxMessage.OWN_TRADE, pMarketId, pTrade.getInstrumentId(), tOrder.getAccount())
                .put("side",      pSide.name())
                .put("price",     unscale(pTrade.getPrice()))
                .put("quantity",  unscale(pTrade.getQuantity()))
                .put("tradeNo",   Long.toHexString(pTrade.getTradeNo()))
                .put("tradeTime", pTrade.getTradeTime())
                .put("orderId",   Long.toHexString(tOrder.getOrderId()))
                .put("userRef",   tOrder.getUserRef())
                .put("clientAccount", tOrder.getClientAccount());
        if (pTrade.isReversal()) tMessage.put("reversalOf", pTrade.getReversalOf());   // a market supervision reversal
        mPersister.publish(tMessage);
    }

    private int marketIdOf(long pInstrumentId) {
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(pInstrumentId);
        return (tInstrument != null) ? tInstrument.marketId() : (int) (pInstrumentId >>> 32);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Null if orders may enter the instrument's book, otherwise the reason they may not. */
    private String checkTradingStatus(long pInstrumentId) {
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(pInstrumentId);
        if (tInstrument == null) return "Instrument not found: " + pInstrumentId;
        InstrumentCache.MarketEntry tMarket = mInstrumentCache.getMarket(tInstrument.marketId());
        if (tMarket == null) return "Market not found: " + tInstrument.marketId();
        if (!MARKET_OPEN.equals(tMarket.tradingStatus()) && !MARKET_PREOPEN.equals(tMarket.tradingStatus())) {
            return "Market " + tMarket.name() + " is " + tMarket.tradingStatus() + ", orders are only accepted when it is OPEN or PREOPEN";
        }
        if (INSTRUMENT_SUSPENDED.equals(tInstrument.tradingStatus())) {
            return "Instrument " + tInstrument.name() + " is SUSPENDED, orders are not accepted";
        }
        return null;
    }

    /** True if the instrument's market is OPEN, i.e. incoming orders match (PREOPEN only collects them). */
    private boolean isMarketOpen(long pInstrumentId) {
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(pInstrumentId);
        InstrumentCache.MarketEntry tMarket = (tInstrument != null) ? mInstrumentCache.getMarket(tInstrument.marketId()) : null;
        return tMarket != null && MARKET_OPEN.equals(tMarket.tradingStatus());
    }

    /** Uncrosses the market's continuous books, which may hold crossing orders collected in PREOPEN. */
    private void uncrossAtOpen(int pMarketId) {
        for (Orderbook tBook : mOrderbooks.values()) {
            InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(tBook.getInstrumentId());
            if (tInstrument == null || tInstrument.marketId() != pMarketId) continue;
            if (Orderbook.TYPE_AUCTION.equals(tBook.getType())) continue;
            // The status is already OPEN: an order that got the lock first was put in the book without
            // matching (PREOPEN) and is uncrossed here, one that gets it after this matches at once
            AuctionResult tResult;
            synchronized (tBook) {
                tResult = tBook.uncross();
            }
            if (tResult.matchedVolume() > 0) {
                cLog.info("Market {} opened: instrument {} uncrossed at {}, matched volume: {}",
                        pMarketId, tBook.getInstrumentId(), tResult.clearingPrice(), tResult.matchedVolume());
            }
        }
    }

    private static double unscale(long pValue) {
        return (double) pValue / SCALE;
    }

    private Orderbook getOrCreateOrderbook(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook != null) return tBook;

        InstrumentCache.InstrumentEntry tEntry = mInstrumentCache.getInstrument(pInstrumentId);
        if (tEntry == null) {
            cLog.warn("Instrument {} not found in cache", pInstrumentId);
            return null;
        }
        // Two first orders arriving at once get the same book
        return mOrderbooks.computeIfAbsent(pInstrumentId, id -> {
            InstrumentCache.MarketEntry tMarket = mInstrumentCache.getMarket(tEntry.marketId());
            String tType = tMarket != null ? tMarket.type() : "CONTINUOUS";
            cLog.info("Orderbook created for instrument {} ({})", id, tType);
            return new Orderbook(id, tType, tEntry.tickSize(), tEntry.lotSize(), this);
        });
    }

    /** An order id from its hex string, 0 (no order) if it is not one. */
    private static long parseOrderId(String pOrderId) {
        try { return Long.parseUnsignedLong(pOrderId, 16); }
        catch (NumberFormatException | NullPointerException e) { return 0L; }
    }

    /**
     * The book the order rests in according to the order index, null if none. The order may be removed
     * before the caller gets the book's lock, so the caller checks it again under the lock (isFirmsOrder).
     */
    private Orderbook orderbookOf(long pOrderId) {
        Long tInstrumentId = mOrderIndex.get(pOrderId);
        return (tInstrumentId != null) ? mOrderbooks.get(tInstrumentId) : null;
    }

    /** True if the order rests in the book and belongs to the firm; the caller holds the book's lock. */
    private static boolean isFirmsOrder(Orderbook pBook, String pFirm, long pOrderId) {
        Order tOrder = pBook.getOrder(pOrderId);
        return tOrder != null && tOrder.getAccount().equals(pFirm);
    }

    private JsonObject instrumentJson(long pInstrumentId) {
        InstrumentCache.InstrumentEntry tInst = mInstrumentCache.getInstrument(pInstrumentId);
        int    tMarketId       = tInst != null ? tInst.marketId() : 0;
        String tInstrumentName = tInst != null ? tInst.name()     : "";
        InstrumentCache.MarketEntry tMarket = mInstrumentCache.getMarket(tMarketId);

        JsonObject tJson = new JsonObject();
        tJson.addProperty("marketId",       tMarketId);
        tJson.addProperty("marketName",     tMarket != null ? tMarket.name() : "");
        tJson.addProperty("instrumentId",   pInstrumentId);
        tJson.addProperty("instrumentName", tInstrumentName);
        return tJson;
    }

    private JsonObject orderToJson(Order pOrder) {
        JsonObject tJson = instrumentJson(pOrder.getInstrumentId());
        tJson.addProperty("orderId",  Long.toHexString(pOrder.getOrderId()));
        tJson.addProperty("side",     pOrder.getSide().name());
        tJson.addProperty("price",    pOrder.getPrice());
        tJson.addProperty("quantity", pOrder.getQuantity());
        tJson.addProperty("account",  pOrder.getAccount());
        tJson.addProperty("orderTime", pOrder.getCreationTime());
        if (pOrder.getUserRef() != null) tJson.addProperty("userRef", pOrder.getUserRef());
        tJson.addProperty("clientAccount", pOrder.getClientAccount());
        tJson.addProperty("user", pOrder.getUser());
        return tJson;
    }

    private JsonObject tradeToJsonForFirm(InternalTrade pTrade, Order.Side pFirmSide) {
        Order tFirmOrder = pFirmSide == Order.Side.BUY ? pTrade.getBuyOrder() : pTrade.getSellOrder();
        JsonObject tJson = instrumentJson(pTrade.getInstrumentId());
        tJson.addProperty("side",      pFirmSide.name());
        tJson.addProperty("price",     pTrade.getPrice());
        tJson.addProperty("quantity",  pTrade.getQuantity());
        tJson.addProperty("tradeNo",   Long.toHexString(pTrade.getTradeNo()));
        tJson.addProperty("tradeTime", pTrade.getTradeTime());
        tJson.addProperty("orderId",   Long.toHexString(tFirmOrder.getOrderId()));
        tJson.addProperty("account",   tFirmOrder.getAccount());
        if (tFirmOrder.getUserRef() != null) tJson.addProperty("userRef", tFirmOrder.getUserRef());
        tJson.addProperty("clientAccount", tFirmOrder.getClientAccount());
        tJson.addProperty("user", tFirmOrder.getUser());
        if (pTrade.isReversal()) tJson.addProperty("reversalOf", pTrade.getReversalOf());
        return tJson;
    }
}
