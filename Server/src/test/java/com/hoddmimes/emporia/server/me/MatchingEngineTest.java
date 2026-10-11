package com.hoddmimes.emporia.server.me;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    private static final long CONT    = (1L << 32) | 1;   // instrument in market 1 (CONTINUOUS)
    private static final long AUCTION = (2L << 32) | 1;   // instrument in market 2 (AUCTION)
    private static final long S       = MatchingEngine.SCALE;

    private InstrumentCache mCache;
    private MatchingEngine  mEngine;

    @BeforeEach
    void setUp() {
        mCache = new InstrumentCache();
        mCache.putMarket(market(1, "Cont", "CONTINUOUS", "OPEN"));
        mCache.putMarket(market(2, "Auct", "AUCTION", "OPEN"));
        mCache.putInstrument(instrument(CONT, 1, "Wheat", "ACTIVE"));
        mCache.putInstrument(instrument(AUCTION, 2, "Corn", "ACTIVE"));
        mEngine = new MatchingEngine(mCache, new BdxDistributor(mCache));
    }

    private static Document market(int pMarketId, String pName, String pType, String pStatus) {
        return new Document("marketId", pMarketId).append("name", pName).append("type", pType)
                .append("tradingStatus", pStatus);
    }

    private static Document instrument(long pId, int pMarketId, String pName, String pStatus) {
        return new Document("instrumentId", String.valueOf(pId)).append("marketId", pMarketId)
                .append("name", pName).append("tickSize", S / 100).append("lotSize", S)
                .append("tradingStatus", pStatus);
    }

    private OrderResponse add(long pInstrumentId, Order.Side pSide, double pPrice, long pQty, String pFirm) {
        return mEngine.addOrder(new AddOrderRequest(pInstrumentId, pSide, Math.round(pPrice * S), pQty * S, pFirm, null));
    }

    private JsonArray orders(String pFirm) {
        return mEngine.getOrdersByFirm(pFirm, 0);
    }

    @Test
    void nonCrossingOrdersRestInBook() {
        assertTrue(add(CONT, Order.Side.BUY, 10.00, 5, "A").inserted());
        OrderResponse tSell = add(CONT, Order.Side.SELL, 10.50, 5, "B");
        assertTrue(tSell.success());
        assertEquals(0, tSell.matched());
        assertEquals(1, orders("A").size());
        assertEquals(1, orders("B").size());
        assertEquals(0, mEngine.getTradesByFirm("A", 0).size());
    }

    @Test
    void crossingOrderTradesAtBookPriceInPriceTimePriority() {
        add(CONT, Order.Side.SELL, 10.50, 3, "A");
        add(CONT, Order.Side.SELL, 10.20, 2, "B");   // best price, matched first
        add(CONT, Order.Side.SELL, 10.20, 2, "C");   // same price, later: matched second

        OrderResponse tBuy = add(CONT, Order.Side.BUY, 10.50, 5, "D");
        assertEquals(5 * S, tBuy.matched());
        assertFalse(tBuy.inserted());

        JsonArray tTrades = mEngine.getTradesByFirm("D", 0);
        assertEquals(3, tTrades.size());
        assertEquals(Math.round(10.20 * S), tTrades.get(0).getAsJsonObject().get("price").getAsLong());
        assertEquals(Math.round(10.50 * S), tTrades.get(2).getAsJsonObject().get("price").getAsLong());
        assertEquals(1 * S, tTrades.get(2).getAsJsonObject().get("quantity").getAsLong());
        assertEquals(0, orders("B").size());
        assertEquals(0, orders("C").size());
        assertEquals(2 * S, orders("A").get(0).getAsJsonObject().get("quantity").getAsLong());
    }

    @Test
    void partialFillLeavesRemainderInBook() {
        add(CONT, Order.Side.SELL, 10.00, 2, "A");
        OrderResponse tBuy = add(CONT, Order.Side.BUY, 10.00, 5, "B");
        assertEquals(2 * S, tBuy.matched());
        assertTrue(tBuy.inserted());
        assertEquals(3 * S, tBuy.remaining());
        assertEquals(3 * S, orders("B").get(0).getAsJsonObject().get("quantity").getAsLong());
    }

    @Test
    void ordersViolatingTickOrLotSizeAreRejected() {
        assertFalse(add(CONT, Order.Side.BUY, 10.005, 1, "A").success());
        assertFalse(mEngine.addOrder(new AddOrderRequest(CONT, Order.Side.BUY, 10 * S, S / 2, "A", null)).success());
        assertFalse(add(CONT, Order.Side.BUY, 0, 1, "A").success());
        assertFalse(add(999L, Order.Side.BUY, 10, 1, "A").success());
    }

    @Test
    void firmCannotSeeModifyOrDeleteAnotherFirmsOrder() {
        String tOrderId = add(CONT, Order.Side.BUY, 10.00, 5, "A").orderId();
        assertEquals(0, orders("B").size());
        assertFalse(mEngine.deleteOrder("B", tOrderId).success());
        assertFalse(mEngine.modifyOrder("B", tOrderId, 0, S, null).success());
        assertEquals(0L, mEngine.getOrderInstrumentId("B", tOrderId));
        assertEquals(CONT, mEngine.getOrderInstrumentId("A", tOrderId));
        assertTrue(mEngine.deleteOrder("A", tOrderId).success());
        assertEquals(0, orders("A").size());
    }

    @Test
    void cleanModifyKeepsOrderIdAndFullModifyReplacesIt() {
        String tOrderId = add(CONT, Order.Side.BUY, 10.00, 5, "A").orderId();

        OrderResponse tClean = mEngine.modifyOrder("A", tOrderId, 0, 3 * S, null);   // reduce quantity
        assertEquals(tOrderId, tClean.orderId());

        OrderResponse tFull = mEngine.modifyOrder("A", tOrderId, 0, 8 * S, null);    // increase quantity
        assertTrue(tFull.success());
        assertNotEquals(tOrderId, tFull.orderId());
        assertEquals(1, orders("A").size());
        assertEquals(8 * S, orders("A").get(0).getAsJsonObject().get("quantity").getAsLong());
    }

    @Test
    void invalidModifyLeavesOrderUntouched() {
        String tOrderId = add(CONT, Order.Side.BUY, 10.00, 5, "A").orderId();
        assertFalse(mEngine.modifyOrder("A", tOrderId, Math.round(10.005 * S), 8 * S, null).success());
        JsonObject tOrder = orders("A").get(0).getAsJsonObject();
        assertEquals(tOrderId, tOrder.get("orderId").getAsString());
        assertEquals(5 * S, tOrder.get("quantity").getAsLong());
    }

    @Test
    void auctionBookCollectsOrdersAndUncrossesAtOneClearingPrice() {
        add(AUCTION, Order.Side.BUY,  10.20, 5, "A");
        add(AUCTION, Order.Side.BUY,  10.00, 5, "A");
        add(AUCTION, Order.Side.SELL,  9.90, 4, "B");
        add(AUCTION, Order.Side.SELL, 10.10, 4, "B");
        assertEquals(0, mEngine.getTradesByFirm("A", 0).size());      // no matching before the auction

        AuctionResult tResult = mEngine.triggerAuction(AUCTION);
        assertTrue(tResult.success());
        assertEquals(5 * S, tResult.matchedVolume());
        assertEquals(Math.round(10.10 * S), tResult.clearingPrice());
        for (var t : mEngine.getTradesByFirm("A", 0)) {
            assertEquals(tResult.clearingPrice(), t.getAsJsonObject().get("price").getAsLong());
        }
        assertFalse(mEngine.triggerAuction(CONT).success());          // no book yet / not an auction book
    }

    @Test
    void ordersAreRejectedWhenMarketIsClosedOrSuspendedOrInstrumentIsSuspended() {
        String tOrderId = add(CONT, Order.Side.BUY, 10.00, 5, "A").orderId();

        for (String tStatus : new String[] { "CLOSED", "SUSPENDED" }) {
            mCache.putMarket(market(1, "Cont", "CONTINUOUS", tStatus));
            OrderResponse tRsp = add(CONT, Order.Side.SELL, 10.00, 5, "B");
            assertFalse(tRsp.success(), tStatus);
            assertTrue(tRsp.message().contains(tStatus), tRsp.message());
            assertFalse(mEngine.modifyOrder("A", tOrderId, 0, 3 * S, null).success(), tStatus);
        }

        mCache.putMarket(market(1, "Cont", "CONTINUOUS", "OPEN"));
        mCache.putInstrument(instrument(CONT, 1, "Wheat", "SUSPENDED"));
        OrderResponse tRsp = add(CONT, Order.Side.SELL, 10.00, 5, "B");
        assertFalse(tRsp.success());
        assertTrue(tRsp.message().contains("SUSPENDED"), tRsp.message());

        // Nothing was matched or changed while trading was stopped, and deleting is still allowed
        assertEquals(0, mEngine.getTradesByFirm("A", 0).size());
        assertEquals(5 * S, orders("A").get(0).getAsJsonObject().get("quantity").getAsLong());
        assertTrue(mEngine.deleteOrder("A", tOrderId).success());

        mCache.putInstrument(instrument(CONT, 1, "Wheat", "ACTIVE"));
        assertTrue(add(CONT, Order.Side.SELL, 10.00, 5, "B").success());
    }

    @Test
    void ordersCanBeDeletedInEveryMarketStateExceptClosed() {
        for (String tStatus : new String[] { "OPEN", "PREOPEN", "SUSPENDED", "CLOSED" }) {
            mCache.putMarket(market(1, "Cont", "CONTINUOUS", "OPEN"));
            String tOrderId = add(CONT, Order.Side.BUY, 10.00, 5, "A").orderId();
            mCache.putMarket(market(1, "Cont", "CONTINUOUS", tStatus));

            OrderResponse tRsp = mEngine.deleteOrder("A", tOrderId);
            if ("CLOSED".equals(tStatus)) {
                assertFalse(tRsp.success());
                assertTrue(tRsp.message().contains("CLOSED"), tRsp.message());
                assertEquals(1, orders("A").size());
            } else {
                assertTrue(tRsp.success(), tStatus + ": " + tRsp.message());
                assertEquals(0, orders("A").size(), tStatus);
            }
        }

        // Another firm's order is not found
        mCache.putMarket(market(1, "Cont", "CONTINUOUS", "OPEN"));
        String tOrderId = orders("A").get(0).getAsJsonObject().get("orderId").getAsString();
        assertFalse(mEngine.deleteOrder("B", tOrderId).success());
        assertTrue(mEngine.deleteOrder("A", tOrderId).success());
    }

    @Test
    void openMarketMatchesOrdersForAnyInstrumentStatusButSuspended() {
        mCache.putInstrument(instrument(CONT, 1, "Wheat", "OPEN"));   // status used in EmporiaX instrument files
        assertTrue(add(CONT, Order.Side.BUY, 10.00, 5, "A").inserted());
        OrderResponse tSell = add(CONT, Order.Side.SELL, 10.00, 5, "B");
        assertTrue(tSell.success(), tSell.message());
        assertEquals(5 * S, tSell.matched());
        assertEquals(1, mEngine.getTradesByFirm("A", 0).size());
    }

    private OrderResponse addWithRef(Order.Side pSide, double pPrice, long pQty, String pUserRef) {
        return mEngine.addOrder(new AddOrderRequest(CONT, pSide, Math.round(pPrice * S), pQty * S, "A", pUserRef));
    }

    private JsonObject onlyOrder(String pFirm) {
        JsonArray tOrders = orders(pFirm);
        assertEquals(1, tOrders.size());
        return tOrders.get(0).getAsJsonObject();
    }

    @Test
    void modifyInPlaceKeepsOrderIdAndKeepsOrReplacesUserRef() {
        String tOrderId = addWithRef(Order.Side.BUY, 10.00, 5, "r1").orderId();

        // Lower quantity without userRef: same order, userRef kept
        OrderResponse tRsp = mEngine.modifyOrder("A", tOrderId, 0, 3 * S, null);
        assertTrue(tRsp.success(), tRsp.message());
        assertEquals(tOrderId, tRsp.orderId());
        assertEquals("r1", tRsp.userRef());
        assertEquals(3 * S, onlyOrder("A").get("quantity").getAsLong());
        assertEquals("r1", onlyOrder("A").get("userRef").getAsString());

        // Lower price with a new userRef: same order, userRef replaced
        tRsp = mEngine.modifyOrder("A", tOrderId, Math.round(9.90 * S), 3 * S, "r2");
        assertEquals(tOrderId, tRsp.orderId());
        assertEquals("r2", tRsp.userRef());
        assertEquals(Math.round(9.90 * S), onlyOrder("A").get("price").getAsLong());
        assertEquals("r2", onlyOrder("A").get("userRef").getAsString());
    }

    @Test
    void modifyThatReplacesTheOrderKeepsOrReplacesUserRef() {
        String tOrderId = addWithRef(Order.Side.BUY, 10.00, 5, "r1").orderId();

        // Higher quantity without userRef: new order, userRef kept
        OrderResponse tRsp = mEngine.modifyOrder("A", tOrderId, 0, 8 * S, null);
        assertTrue(tRsp.success(), tRsp.message());
        assertNotEquals(tOrderId, tRsp.orderId());
        assertEquals("r1", tRsp.userRef());
        assertEquals(tRsp.orderId(), onlyOrder("A").get("orderId").getAsString());
        assertEquals("r1", onlyOrder("A").get("userRef").getAsString());
        assertFalse(mEngine.modifyOrder("A", tOrderId, 0, 1 * S, null).success());   // old id is gone

        // Higher quantity with a new userRef: new order, userRef replaced
        tRsp = mEngine.modifyOrder("A", tRsp.orderId(), 0, 9 * S, "r2");
        assertEquals("r2", tRsp.userRef());
        assertEquals("r2", onlyOrder("A").get("userRef").getAsString());
    }

    @Test
    void modifyThatCrossesTheBookMatchesAndKeepsUserRef() {
        add(CONT, Order.Side.SELL, 10.50, 5, "B");
        String tOrderId = addWithRef(Order.Side.BUY, 10.00, 5, "r1").orderId();

        OrderResponse tRsp = mEngine.modifyOrder("A", tOrderId, Math.round(10.50 * S), 5 * S, null);
        assertTrue(tRsp.success(), tRsp.message());
        assertEquals(5 * S, tRsp.matched());
        assertEquals(0, orders("A").size());
        JsonArray tTrades = mEngine.getTradesByFirm("A", 0);
        assertEquals(1, tTrades.size());
        assertEquals("r1", tTrades.get(0).getAsJsonObject().get("userRef").getAsString());
    }

    @Test
    void modifyIsRejectedForAnotherFirmsOrderAndInvalidValues() {
        String tOrderId = addWithRef(Order.Side.BUY, 10.00, 5, "r1").orderId();
        assertFalse(mEngine.modifyOrder("B", tOrderId, 0, 3 * S, "x").success());
        assertFalse(mEngine.modifyOrder("A", tOrderId, 0, 0, null).success());              // zero quantity
        assertFalse(mEngine.modifyOrder("A", "nothex", 0, 3 * S, null).success());
        assertEquals(5 * S, onlyOrder("A").get("quantity").getAsLong());
        assertEquals("r1", onlyOrder("A").get("userRef").getAsString());
    }

    @Test
    void preopenCollectsOrdersWithoutMatchingAndOpenUncrossesTheBook() {
        mCache.putMarket(market(1, "Cont", "CONTINUOUS", "PREOPEN"));
        OrderResponse tBuy  = add(CONT, Order.Side.BUY,  10.20, 5, "A");
        OrderResponse tSell = add(CONT, Order.Side.SELL, 10.00, 3, "B");
        assertTrue(tBuy.inserted());
        assertTrue(tSell.inserted());
        assertEquals(0, tSell.matched());
        assertEquals(0, mEngine.getTradesByFirm("A", 0).size());      // crossed, but nothing matched

        // A modify in PREOPEN is accepted and does not match either
        assertTrue(mEngine.modifyOrder("B", tSell.orderId(), 0, 4 * S, null).success());
        assertEquals(0, mEngine.getTradesByFirm("A", 0).size());

        mCache.putMarket(market(1, "Cont", "CONTINUOUS", "OPEN"));     // opening uncross
        JsonArray tTrades = mEngine.getTradesByFirm("A", 0);
        assertEquals(1, tTrades.size());
        assertEquals(Math.round(10.00 * S), tTrades.get(0).getAsJsonObject().get("price").getAsLong());
        assertEquals(4 * S, tTrades.get(0).getAsJsonObject().get("quantity").getAsLong());
        assertEquals(0, orders("B").size());
        assertEquals(1 * S, orders("A").get(0).getAsJsonObject().get("quantity").getAsLong());

        // Continuous matching from now on, at the resting order's price
        assertEquals(1 * S, add(CONT, Order.Side.SELL, 10.10, 1, "B").matched());
        assertEquals(Math.round(10.20 * S),
                mEngine.getTradesByFirm("B", 0).get(1).getAsJsonObject().get("price").getAsLong());
    }
}
