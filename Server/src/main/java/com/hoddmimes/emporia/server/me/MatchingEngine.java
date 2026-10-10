package com.hoddmimes.emporia.server.me;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.PriceFeed;
import com.hoddmimes.emporia.server.PriceUpdate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matching Engine — manages a set of orderbooks, one per instrument, created on first use.
 * Implements {@link MatchingEngineCallbackIf} to receive callbacks from the orderbooks and
 * publishes every orderbook change as a {@link PriceUpdate} on the price feed.
 *
 * Prices and quantities are longs scaled by {@link #SCALE}. Orders, books and trades are
 * held in memory only. The public methods are synchronized: requests arrive concurrently
 * from the REST endpoints and the auction scheduler.
 *
 * An order's account is the firm that owns it; a firm can only see, modify and delete its own orders.
 * Orders are only accepted (added or modified) while the market is OPEN or PREOPEN and the
 * instrument is ACTIVE; deleting an order is always allowed. In PREOPEN orders are only put in
 * the book; when the market changes to OPEN its continuous books are uncrossed once at a single
 * clearing price, after which continuous matching applies.
 */
public class MatchingEngine implements MatchingEngineCallbackIf {

    private static final Logger cLog = LogManager.getLogger(MatchingEngine.class);

    /** Prices and quantities are stored as longs scaled by this factor (10.50 is stored as 1050000). */
    public static final long SCALE = 100000L;

    public static final String MARKET_OPEN       = "OPEN";
    public static final String MARKET_PREOPEN    = "PREOPEN";
    public static final String INSTRUMENT_ACTIVE = "ACTIVE";

    private final InstrumentCache         mInstrumentCache;
    private final PriceFeed               mPriceFeed;
    private final Map<Long, Orderbook>    mOrderbooks;
    private final List<InternalTrade>     mTrades;

    public MatchingEngine(InstrumentCache pInstrumentCache, PriceFeed pPriceFeed) {
        mInstrumentCache = pInstrumentCache;
        mPriceFeed       = pPriceFeed;
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

    /** Modifies one of the firm's resting orders; pPrice 0 keeps the current price. */
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
        return tBook.deleteOrder(Long.parseUnsignedLong(pOrderId, 16));
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
        JsonArray tResult = new JsonArray();
        for (Map.Entry<Long, Orderbook> tEntry : mOrderbooks.entrySet()) {
            if (pInstrumentId != 0 && tEntry.getKey() != pInstrumentId) continue;
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
        JsonArray tResult = new JsonArray();
        for (InternalTrade tTrade : mTrades) {
            if (pInstrumentId != 0 && tTrade.getInstrumentId() != pInstrumentId) continue;
            boolean tIsBuyer  = pFirm.equals(tTrade.getBuyOrder().getAccount());
            boolean tIsSeller = pFirm.equals(tTrade.getSellOrder().getAccount());
            // A firm trading with itself sees both sides
            if (tIsBuyer)  tResult.add(tradeToJsonForFirm(tTrade, Order.Side.BUY));
            if (tIsSeller) tResult.add(tradeToJsonForFirm(tTrade, Order.Side.SELL));
        }
        return tResult;
    }

    // ── MatchingEngineCallbackIf ───────────────────────────────────────────

    @Override
    public void orderAdded(Order pOrder, long pSeqNo) {
        cLog.debug("Order added: {}", pOrder);
    }

    @Override
    public void orderRemoved(Order pOrder, long pSeqNo) {
        cLog.debug("Order removed: {}", pOrder);
    }

    @Override
    public void orderModified(Order pOrder, long pSeqNo) {
        cLog.debug("Order modified: {}", pOrder);
    }

    @Override
    public void trade(InternalTrade pTrade) {
        mTrades.add(pTrade);
        cLog.info("{}", pTrade);
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

        mPriceFeed.publish(new PriceUpdate(pInstrumentId,
                tBestBuy  != null ? unscale(tBook.getBestPriceVolume(Order.Side.BUY))  : null,
                tBestBuy  != null ? unscale(tBestBuy.getPrice())                        : null,
                tBestSell != null ? unscale(tBook.getBestPriceVolume(Order.Side.SELL)) : null,
                tBestSell != null ? unscale(tBestSell.getPrice())                       : null,
                tTraded ? unscale(tBook.getOpenPrice())      : null,
                tTraded ? unscale(tBook.getHighPrice())      : null,
                tTraded ? unscale(tBook.getLastTradePrice()) : null,
                unscale(tBook.getTradedVolume())));
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
        if (!INSTRUMENT_ACTIVE.equals(tInstrument.tradingStatus())) {
            return "Instrument " + tInstrument.name() + " is " + tInstrument.tradingStatus() + ", orders are only accepted when it is ACTIVE";
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
