package com.hoddmimes.emporia.server.me;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Matching Engine — manages a set of orderbooks, one per instrument, created on first use.
 * Implements {@link MatchingEngineCallbackIf} to receive callbacks from the orderbooks and
 * broadcasts every orderbook change as bdx messages (see {@link BdxMessage}).
 *
 * Prices and quantities are longs scaled by {@link #SCALE}. Orders, books and trades are
 * held in memory only. The public methods are synchronized: requests arrive concurrently
 * from the REST endpoints and the auction scheduler.
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
    private final BdxDistributor          mBdx;
    private final Map<Long, Orderbook>    mOrderbooks;
    private final List<InternalTrade>     mTrades;
    private final Database mDB;

    public MatchingEngine(InstrumentCache pInstrumentCache, Database pDB, BdxDistributor pBdx) {
        mInstrumentCache = pInstrumentCache;
        mBdx             = pBdx;
        mDB              = pDB;
        mOrderbooks      = new HashMap<>();
        mTrades          = new ArrayList<>();

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
        List<Document> tTradeDocs = (mDB != null) ? mDB.listTradesByDate( simpleDateFormat.format(new Date())) : null;
        if (tTradeDocs != null) {
            List<InternalTrade> tTrades = new ArrayList<>();
            for( Document tDoc : tTradeDocs) {
                tTrades.add(Database.fromTradeDocument(tDoc ));
            }
            restoreTrades(tTrades);
        }
    }

    /**
     * Takes over today's trades reloaded at startup: adds them to the trade list and replays them,
     * oldest first, into the statistics (open, high, last, volume) of their instruments' order books
     * (creating the books), then publishes a PriceUpdate per instrument so the bdx snapshot and
     * getPrices show the day's prices before the first new order. The order books themselves are
     * empty, since orders are not stored.
     */
    synchronized void restoreTrades(List<InternalTrade> pTrades) {
        mTrades.addAll(pTrades);
        List<InternalTrade> tOldestFirst = new ArrayList<>(pTrades);
        tOldestFirst.sort(Comparator.comparingLong(InternalTrade::getTradeTime));
        Set<Long> tInstruments = new LinkedHashSet<>();
        for (InternalTrade tTrade : tOldestFirst) {
            Orderbook tBook = getOrCreateOrderbook(tTrade.getInstrumentId());
            if (tBook == null) continue;   // the instrument no longer exists
            tBook.restoreTrade(tTrade.getPrice(), tTrade.getQuantity());
            tInstruments.add(tTrade.getInstrumentId());
        }
        tInstruments.forEach(this::orderbookChanged);
        cLog.info("{} trade(s) of today reloaded, prices restored for {} instrument(s)", pTrades.size(), tInstruments.size());
    }

    // ── Public order operations ────────────────────────────────────────────

    public synchronized OrderResponse addOrder(AddOrderRequest pReq) {
        String tErr = checkTradingStatus(pReq.instrumentId());
        if (tErr != null) return OrderResponse.error(tErr);
        Orderbook tBook = getOrCreateOrderbook(pReq.instrumentId());
        if (tBook == null) {
            return OrderResponse.error("Instrument not found: " + pReq.instrumentId());
        }
        return tBook.addOrder(pReq, isMarketOpen(pReq.instrumentId()));
    }

    /** Modifies one of the firm's resting orders; pPrice 0 keeps the current price, a null pUserRef the current userRef. */
    public synchronized OrderResponse modifyOrder(String pFirm, String pOrderId, long pPrice, long pQuantity, String pUserRef) {
        Orderbook tBook = findOrderbookForOrder(pFirm, pOrderId);
        if (tBook == null) return OrderResponse.error("Order not found: " + pOrderId);
        String tErr = checkTradingStatus(tBook.getInstrumentId());
        if (tErr != null) return OrderResponse.error(tErr);
        return tBook.modifyOrder(Long.parseUnsignedLong(pOrderId, 16), pPrice, pQuantity, pUserRef,
                isMarketOpen(tBook.getInstrumentId()));
    }

    public synchronized OrderResponse deleteOrder(String pFirm, String pOrderId) {
        Orderbook tBook = findOrderbookForOrder(pFirm, pOrderId);
        if (tBook == null) return OrderResponse.error("Order not found: " + pOrderId);
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(tBook.getInstrumentId());
        InstrumentCache.MarketEntry tMarket = (tInstrument != null) ? mInstrumentCache.getMarket(tInstrument.marketId()) : null;
        if (tMarket != null && MARKET_CLOSED.equals(tMarket.tradingStatus())) {
            return OrderResponse.error("Market " + tMarket.name() + " is CLOSED, orders can not be deleted");
        }
        return tBook.deleteOrder(Long.parseUnsignedLong(pOrderId, 16));
    }

    /**
     * Admin: deletes every resting order in the market's order books, of all firms and in any market
     * state (no authorization or trading status checks). Each removal is published like a normal
     * delete. Returns the number of orders deleted.
     */
    public synchronized int deleteAllOrders(int pMarketId) {
        int tDeleted = 0;
        for (Orderbook tBook : mOrderbooks.values()) {
            if (marketIdOf(tBook.getInstrumentId()) != pMarketId) continue;
            List<Order> tOrders = new ArrayList<>(tBook.getBuySide().getOrders());
            tOrders.addAll(tBook.getSellSide().getOrders());
            for (Order tOrder : tOrders) {
                if (tBook.deleteOrder(tOrder.getOrderId()).success()) tDeleted++;
            }
        }
        cLog.info("Admin deleted all orders in market {}: {} order(s)", pMarketId, tDeleted);
        return tDeleted;
    }

    /** The number of resting orders in the market's order books. */
    public synchronized int orderCount(int pMarketId) {
        int tCount = 0;
        for (Orderbook tBook : mOrderbooks.values()) {
            if (marketIdOf(tBook.getInstrumentId()) == pMarketId) {
                tCount += tBook.getBuySideDepth() + tBook.getSellSideDepth();
            }
        }
        return tCount;
    }

    /** The instrument an order belongs to, if it is one of the firm's resting orders; 0 otherwise. */
    public synchronized long getOrderInstrumentId(String pFirm, String pOrderId) {
        Orderbook tBook = findOrderbookForOrder(pFirm, pOrderId);
        return (tBook != null) ? tBook.getInstrumentId() : 0L;
    }

    public synchronized AuctionResult triggerAuction(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        if (tBook == null) {
            return AuctionResult.error("No orderbook for instrument: " + pInstrumentId);
        }
        if (!Orderbook.TYPE_AUCTION.equals(tBook.getType())) {
            return AuctionResult.error("Not an auction book: " + pInstrumentId);
        }
        AuctionResult tResult = tBook.triggerAuction();
        if (tResult.success()) {
            cLog.info("Auction triggered for instrument {}, matched volume: {}",
                    pInstrumentId, tResult.matchedVolume());
        }
        return tResult;
    }

    /** The firm's resting orders, for one instrument or (pInstrumentId 0) all instruments. */
    public synchronized JsonArray getOrdersByFirm(String pFirm, long pInstrumentId) {
        return getOrdersByFirm(pFirm, 0, pInstrumentId);
    }

    /** The firm's resting orders, optionally only for one market and/or one instrument (0 = all). */
    public synchronized JsonArray getOrdersByFirm(String pFirm, int pMarketId, long pInstrumentId) {
        JsonArray tResult = new JsonArray();
        for (Map.Entry<Long, Orderbook> tEntry : mOrderbooks.entrySet()) {
            if (pInstrumentId != 0 && tEntry.getKey() != pInstrumentId) continue;
            if (pMarketId != 0 && marketIdOf(tEntry.getKey()) != pMarketId) continue;
            Orderbook tBook = tEntry.getValue();
            for (Order tOrder : tBook.getBuySide().getOrders()) {
                if (pFirm.equals(tOrder.getAccount())) tResult.add(orderToJson(tOrder));
            }
            for (Order tOrder : tBook.getSellSide().getOrders()) {
                if (pFirm.equals(tOrder.getAccount())) tResult.add(orderToJson(tOrder));
            }
        }
        return tResult;
    }

    /** The firm's trades, for one instrument or (pInstrumentId 0) all instruments. */
    public synchronized JsonArray getTradesByFirm(String pFirm, long pInstrumentId) {
        return getTradesByFirm(pFirm, 0, pInstrumentId);
    }

    /** The firm's trades, oldest first, optionally only for one market and/or one instrument (0 = all). */
    public synchronized JsonArray getTradesByFirm(String pFirm, int pMarketId, long pInstrumentId) {
        return tradesForFirm(mTrades, pFirm, pMarketId, pInstrumentId);
    }

    /**
     * The firm's side of the given trades, in their order and filtered like getTradesByFirm; used for
     * the stored trades of a chosen day (Database.fromTradeDocument).
     */
    public synchronized JsonArray tradesForFirm(List<InternalTrade> pTrades, String pFirm, int pMarketId, long pInstrumentId) {
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
    public synchronized JsonArray getPublicTrades(int pMarketId, long pInstrumentId) {
        JsonArray tResult = new JsonArray();
        for (InternalTrade tTrade : mTrades) {
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
    public synchronized JsonArray getPrices(int pMarketId) {
        List<InstrumentCache.InstrumentEntry> tInstruments = new ArrayList<>(mInstrumentCache.getInstrumentsByMarket(pMarketId));
        tInstruments.sort(Comparator.comparing(InstrumentCache.InstrumentEntry::name));
        JsonArray tResult = new JsonArray();
        for (InstrumentCache.InstrumentEntry tInstrument : tInstruments) {
            Orderbook tBook     = mOrderbooks.get(tInstrument.instrumentId());
            Order     tBestBuy  = (tBook != null) ? tBook.getBuySide().peek()  : null;
            Order     tBestSell = (tBook != null) ? tBook.getSellSide().peek() : null;
            boolean   tTraded   = tBook != null && tBook.getTradedVolume() > 0;
            JsonObject tJson = instrumentJson(tInstrument.instrumentId());
            tJson.addProperty("bidVol", tBestBuy  != null ? tBook.getBestPriceVolume(Order.Side.BUY)  : null);
            tJson.addProperty("bidPx",  tBestBuy  != null ? tBestBuy.getPrice()                       : null);
            tJson.addProperty("askVol", tBestSell != null ? tBook.getBestPriceVolume(Order.Side.SELL) : null);
            tJson.addProperty("askPx",  tBestSell != null ? tBestSell.getPrice()                      : null);
            tJson.addProperty("open",   tTraded ? tBook.getOpenPrice()      : null);
            tJson.addProperty("high",   tTraded ? tBook.getHighPrice()      : null);
            tJson.addProperty("last",   tTraded ? tBook.getLastTradePrice() : null);
            tJson.addProperty("volume", (tBook != null) ? tBook.getTradedVolume() : 0L);
            tResult.add(tJson);
        }
        return tResult;
    }

    /** The instrument's last traded price today (scaled), 0 if it has not traded today. */
    public synchronized long getLastTradePrice(long pInstrumentId) {
        Orderbook tBook = mOrderbooks.get(pInstrumentId);
        return (tBook != null && tBook.getTradedVolume() > 0) ? tBook.getLastTradePrice() : 0L;
    }

    /**
     * A snapshot of the instrument's whole order book, anonymous, aggregated per price level: "bids" and
     * "asks", best price first, each level with price, quantity (total), orders (count) and own (the
     * quantity of pFirm's orders at the level), plus open, high, last (JSON null if not traded today) and
     * volume. Prices and quantities scaled. Empty sides if the instrument has no order book yet.
     */
    public synchronized JsonObject getOrderbook(long pInstrumentId, String pFirm) {
        Orderbook tBook   = mOrderbooks.get(pInstrumentId);
        boolean   tTraded = tBook != null && tBook.getTradedVolume() > 0;
        JsonObject tJson = instrumentJson(pInstrumentId);
        tJson.add("bids", priceLevels(tBook != null ? tBook.getBuySide().getOrders()  : List.of(), pFirm));
        tJson.add("asks", priceLevels(tBook != null ? tBook.getSellSide().getOrders() : List.of(), pFirm));
        tJson.addProperty("open",   tTraded ? tBook.getOpenPrice()      : null);
        tJson.addProperty("high",   tTraded ? tBook.getHighPrice()      : null);
        tJson.addProperty("last",   tTraded ? tBook.getLastTradePrice() : null);
        tJson.addProperty("volume", (tBook != null) ? tBook.getTradedVolume() : 0L);
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
    // and a PriceUpdate snapshot after each request.

    @Override
    public void orderAdded(Order pOrder, long pSeqNo) {
        cLog.debug("Order added: {}", pOrder);
        publishOrder(BdxMessage.ADD_ORDER, "ADDED", pOrder, pSeqNo);
    }

    @Override
    public void orderRemoved(Order pOrder, long pSeqNo) {
        cLog.debug("Order removed: {}", pOrder);
        publishOrder(BdxMessage.REMOVE_ORDER, "REMOVED", pOrder, pSeqNo);
    }

    @Override
    public void orderModified(Order pOrder, long pSeqNo) {
        cLog.debug("Order modified: {}", pOrder);
        publishOrder(BdxMessage.MODIFY_ORDER, "MODIFIED", pOrder, pSeqNo);
    }

    @Override
    public void trade(InternalTrade pTrade) {
        mTrades.add(pTrade);
        cLog.info("{}", pTrade);



        int tMarketId = marketIdOf(pTrade.getInstrumentId());

        // Trade to database
        if (mDB != null) mDB.saveTrade(pTrade);

        mBdx.publish(BdxMessage.instrument(BdxMessage.TRADE, tMarketId, pTrade.getInstrumentId())
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
        mBdx.publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, marketIdOf(pInstrumentId), pInstrumentId)
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
        mBdx.publish(orderMessage(BdxMessage.instrument(pType, tMarketId, pOrder.getInstrumentId()), pOrder, pSeqNo));
        mBdx.publish(orderMessage(BdxMessage.forFirm(BdxMessage.OWN_ORDER, tMarketId, pOrder.getInstrumentId(), pOrder.getAccount()), pOrder, pSeqNo)
                .put("event",   pEvent)
                .put("userRef", pOrder.getUserRef()));
    }

    private static BdxMessage orderMessage(BdxMessage pMessage, Order pOrder, long pSeqNo) {
        return pMessage.put("seqNo",    pSeqNo)
                       .put("orderId",  Long.toHexString(pOrder.getOrderId()))
                       .put("side",     pOrder.getSide().name())
                       .put("price",    unscale(pOrder.getPrice()))
                       .put("quantity", unscale(pOrder.getQuantity()));   // remaining; 0 when removed fully filled
    }

    /** The private OwnTrade for one side of a trade, to the firm of that side's order. */
    private void publishOwnTrade(InternalTrade pTrade, int pMarketId, Order.Side pSide) {
        Order tOrder = (pSide == Order.Side.BUY) ? pTrade.getBuyOrder() : pTrade.getSellOrder();
        mBdx.publish(BdxMessage.forFirm(BdxMessage.OWN_TRADE, pMarketId, pTrade.getInstrumentId(), tOrder.getAccount())
                .put("side",      pSide.name())
                .put("price",     unscale(pTrade.getPrice()))
                .put("quantity",  unscale(pTrade.getQuantity()))
                .put("tradeNo",   Long.toHexString(pTrade.getTradeNo()))
                .put("tradeTime", pTrade.getTradeTime())
                .put("orderId",   Long.toHexString(tOrder.getOrderId()))
                .put("userRef",   tOrder.getUserRef()));
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
    private synchronized void uncrossAtOpen(int pMarketId) {
        for (Orderbook tBook : mOrderbooks.values()) {
            InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(tBook.getInstrumentId());
            if (tInstrument == null || tInstrument.marketId() != pMarketId) continue;
            if (Orderbook.TYPE_AUCTION.equals(tBook.getType())) continue;
            AuctionResult tResult = tBook.uncross();
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
        InstrumentCache.MarketEntry tMarket = mInstrumentCache.getMarket(tEntry.marketId());
        String tType = tMarket != null ? tMarket.type() : "CONTINUOUS";
        tBook = new Orderbook(pInstrumentId, tType, tEntry.tickSize(), tEntry.lotSize(), this);
        mOrderbooks.put(pInstrumentId, tBook);
        cLog.info("Orderbook created for instrument {} ({})", pInstrumentId, tType);
        return tBook;
    }

    /** The book holding the given order, if the order exists and belongs to the firm. */
    private Orderbook findOrderbookForOrder(String pFirm, String pOrderId) {
        long tOrderId;
        try { tOrderId = Long.parseUnsignedLong(pOrderId, 16); }
        catch (NumberFormatException e) { return null; }
        for (Orderbook tBook : mOrderbooks.values()) {
            Order tOrder = tBook.getOrder(tOrderId);
            if (tOrder != null) return pFirm.equals(tOrder.getAccount()) ? tBook : null;
        }
        return null;
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
        return tJson;
    }
}
