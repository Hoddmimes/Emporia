package com.hoddmimes.emporia.server.bdx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import com.hoddmimes.emporia.server.me.Order;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BdxDistributorTest {

    private static final long INST1 = (1L << 32) | 1;   // instrument in market 1
    private static final long INST2 = (2L << 32) | 1;   // instrument in market 2
    private static final long S     = MatchingEngine.SCALE;

    /** A fake WebSocket client recording what it receives. */
    private static class TestClient {
        final List<JsonObject> received = new ArrayList<>();
        String closedReason;

        List<String> types() { return received.stream().map(j -> j.get("type").getAsString()).toList(); }

        List<JsonObject> ofType(String pType) {
            return received.stream().filter(j -> j.get("type").getAsString().equals(pType)).toList();
        }
    }

    private InstrumentCache mCache;
    private BdxDistributor  mBdx;
    private MatchingEngine  mEngine;

    @BeforeEach
    void setUp() {
        mCache = new InstrumentCache();
        mCache.putMarket(market(1, "M1", "OPEN"));
        mCache.putMarket(market(2, "M2", "OPEN"));
        mCache.putInstrument(instrument(INST1, 1, "Wheat"));
        mCache.putInstrument(instrument(INST2, 2, "Corn"));
        mBdx    = new BdxDistributor(mCache);
        mEngine = new MatchingEngine(mCache, null, mBdx);
    }

    private static Document market(int pMarketId, String pName, String pStatus) {
        return new Document("marketId", pMarketId).append("name", pName).append("type", "CONTINUOUS")
                .append("tradingStatus", pStatus);
    }

    private static Document instrument(long pId, int pMarketId, String pName) {
        return new Document("instrumentId", String.valueOf(pId)).append("marketId", pMarketId)
                .append("name", pName).append("tickSize", S / 100).append("lotSize", S)
                .append("tradingStatus", "ACTIVE");
    }

    private TestClient connect(String pHttpSessionId, String pFirm, Integer... pMarkets) {
        mBdx.dispatchQueued();
        TestClient tClient = new TestClient();
        mBdx.addClient(tClient, pHttpSessionId, "u-" + pFirm, pFirm, "127.0.0.1", Set.of(pMarkets),
                tJson -> tClient.received.add(JsonParser.parseString(tJson).getAsJsonObject()),
                tReason -> tClient.closedReason = tReason);
        return tClient;
    }

    private void add(long pInstrumentId, Order.Side pSide, double pPrice, long pQty, String pFirm, String pUserRef) {
        assertTrue(mEngine.addOrder(new AddOrderRequest(pInstrumentId, pSide, Math.round(pPrice * S), pQty * S, pFirm, pUserRef)).success());
        mBdx.dispatchQueued();
    }

    /** Publishes and sends the message on the test thread (the distributor thread is not started). */
    private void publish(BdxMessage pMessage) {
        mBdx.publish(pMessage);
        mBdx.dispatchQueued();
    }

    @Test
    void publicMessagesGoToAuthorizedMarketsAndPrivateMessagesToTheFirm() {
        TestClient tA = connect("s1", "FirmA", 1);
        TestClient tB = connect("s2", "FirmB", 1, 2);
        TestClient tC = connect("s3", "FirmC", 2);

        publish(BdxMessage.instrument(BdxMessage.TRADE, 1, INST1).put("price", 10.0));
        publish(BdxMessage.forFirm(BdxMessage.OWN_TRADE, 2, INST2, "FirmA").put("price", 11.0));

        assertEquals(List.of(BdxMessage.TRADE, BdxMessage.OWN_TRADE), tA.types());   // private regardless of market
        assertEquals(List.of(BdxMessage.TRADE), tB.types());
        assertEquals(List.of(), tC.types());

        JsonObject tTrade = tA.received.get(0);
        assertEquals("/Trade/1/" + INST1, tTrade.get("topic").getAsString());
        assertEquals(1, tTrade.get("marketId").getAsInt());
        assertEquals(INST1, tTrade.get("instrumentId").getAsLong());
    }

    @Test
    void globalMessagesGoToEveryClient() {
        TestClient tA = connect("s1", "FirmA", 1);
        TestClient tB = connect("s2", "FirmB");   // no markets at all
        publish(BdxMessage.global(BdxMessage.CHAT).put("text", "hello"));
        assertEquals(List.of(BdxMessage.CHAT), tA.types());
        assertEquals(List.of(BdxMessage.CHAT), tB.types());
        JsonObject tChat = tB.received.get(0);
        assertEquals("/Chat", tChat.get("topic").getAsString());
        assertFalse(tChat.has("marketId"));
        assertEquals("hello", tChat.get("text").getAsString());
    }

    @Test
    void updatedAuthorizationsApplyToConnectedClients() {
        publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, 2, INST2).put("last", 20.0));
        TestClient tA = connect("s1", "FirmA", 1);
        TestClient tB = connect("s2", "FirmB", 1);
        int tBReceived = tB.received.size();
        assertTrue(tA.ofType(BdxMessage.PRICE_UPDATE).isEmpty());

        // FirmA gains market 2 and loses market 1; FirmB is unchanged
        mBdx.updateAuthorizations(f -> f.equals("FirmA") ? Set.of(2) : Set.of(1));
        List<JsonObject> tPrices = tA.ofType(BdxMessage.PRICE_UPDATE);
        assertEquals(1, tPrices.size());                                      // market 2's snapshot
        assertEquals(20.0, tPrices.get(0).get("last").getAsDouble());
        assertEquals(tBReceived, tB.received.size());

        tA.received.clear();
        publish(BdxMessage.instrument(BdxMessage.TRADE, 1, INST1).put("price", 10.0));
        publish(BdxMessage.instrument(BdxMessage.TRADE, 2, INST2).put("price", 21.0));
        assertEquals(List.of(2), tA.ofType(BdxMessage.TRADE).stream().map(j -> j.get("marketId").getAsInt()).toList());
        assertEquals(List.of(1), tB.ofType(BdxMessage.TRADE).stream().map(j -> j.get("marketId").getAsInt()).toList());
    }

    @Test
    void newClientGetsTheLatestPriceUpdateAndMarketStateButNoEvents() {
        publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, 1, INST1).put("last", 9.0));
        publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, 1, INST1).put("last", 10.0));
        publish(BdxMessage.instrument(BdxMessage.PRICE_UPDATE, 2, INST2).put("last", 20.0));
        publish(BdxMessage.instrument(BdxMessage.TRADE, 1, INST1).put("price", 10.0));
        mCache.putMarket(market(1, "M1", "CLOSED"));                      // MarketState from the cache

        TestClient tA = connect("s1", "FirmA", 1);
        assertEquals(2, tA.received.size(), tA.received.toString());
        assertEquals(10.0, tA.ofType(BdxMessage.PRICE_UPDATE).get(0).get("last").getAsDouble());
        JsonObject tState = tA.ofType(BdxMessage.MARKET_STATE).get(0);
        assertEquals("/MarketState/1", tState.get("topic").getAsString());
        assertEquals("CLOSED", tState.get("tradingStatus").getAsString());
        assertFalse(tState.has("instrumentId"));
    }

    @Test
    void closeClientsClosesOnlyTheSessionsSockets() {
        TestClient tA1 = connect("s1", "FirmA", 1);
        TestClient tA2 = connect("s1", "FirmA", 1);
        TestClient tB  = connect("s2", "FirmB", 1);

        mBdx.closeClients("s1", "Logged out");
        assertEquals("Logged out", tA1.closedReason);
        assertEquals("Logged out", tA2.closedReason);
        assertNull(tB.closedReason);
        assertEquals(1, mBdx.clientCount());

        publish(BdxMessage.instrument(BdxMessage.TRADE, 1, INST1));
        assertEquals(0, tA1.received.size());
        assertEquals(1, tB.received.size());
    }

    @Test
    void engineBroadcastsOrderBookEventsTradesAndPrivateMessages() {
        TestClient tA = connect("s1", "FirmA", 1);
        TestClient tB = connect("s2", "FirmB", 1);
        TestClient tC = connect("s3", "FirmC", 2);

        add(INST1, Order.Side.BUY, 10.00, 5, "FirmA", "a1");
        assertEquals(List.of(BdxMessage.ADD_ORDER, BdxMessage.OWN_ORDER, BdxMessage.PRICE_UPDATE), tA.types());
        assertEquals(List.of(BdxMessage.ADD_ORDER, BdxMessage.PRICE_UPDATE), tB.types());

        JsonObject tAdd = tB.ofType(BdxMessage.ADD_ORDER).get(0);
        assertEquals("BUY", tAdd.get("side").getAsString());
        assertEquals(10.0, tAdd.get("price").getAsDouble());
        assertEquals(5.0, tAdd.get("quantity").getAsDouble());
        assertTrue(tAdd.has("seqNo") && tAdd.has("orderId"));
        assertFalse(tAdd.has("userRef") || tAdd.has("account"));          // anonymous

        JsonObject tOwn = tA.ofType(BdxMessage.OWN_ORDER).get(0);
        assertEquals("ADDED", tOwn.get("event").getAsString());
        assertEquals("a1", tOwn.get("userRef").getAsString());

        // FirmB sells 3 into the bid: a trade, and the bid rests partially filled
        tA.received.clear();
        tB.received.clear();
        add(INST1, Order.Side.SELL, 10.00, 3, "FirmB", "b1");

        JsonObject tTrade = tB.ofType(BdxMessage.TRADE).get(0);
        assertEquals(10.0, tTrade.get("price").getAsDouble());
        assertEquals(3.0, tTrade.get("quantity").getAsDouble());
        assertFalse(tTrade.toString().contains("Firm"));                   // no counterparties

        JsonObject tBuy  = tA.ofType(BdxMessage.OWN_TRADE).get(0);
        JsonObject tSell = tB.ofType(BdxMessage.OWN_TRADE).get(0);
        assertEquals("BUY", tBuy.get("side").getAsString());
        assertEquals("a1", tBuy.get("userRef").getAsString());
        assertEquals("SELL", tSell.get("side").getAsString());
        assertEquals("b1", tSell.get("userRef").getAsString());
        assertEquals(1, tA.ofType(BdxMessage.OWN_TRADE).size());
        assertEquals(1, tB.ofType(BdxMessage.OWN_TRADE).size());

        // The partial fill is a public ModifyOrder with the remaining quantity
        JsonObject tModify = tB.ofType(BdxMessage.MODIFY_ORDER).get(0);
        assertEquals(tAdd.get("orderId").getAsString(), tModify.get("orderId").getAsString());
        assertEquals(2.0, tModify.get("quantity").getAsDouble());
        assertTrue(tModify.get("seqNo").getAsLong() > tAdd.get("seqNo").getAsLong());
        assertEquals("MODIFIED", tA.ofType(BdxMessage.OWN_ORDER).get(0).get("event").getAsString());

        // The fully matched sell never rested: no order book event or OwnOrder for it
        assertEquals(0, tB.ofType(BdxMessage.OWN_ORDER).size());
        assertEquals(0, tB.ofType(BdxMessage.ADD_ORDER).size());

        JsonObject tPrice = tB.ofType(BdxMessage.PRICE_UPDATE).get(0);
        assertEquals(2.0, tPrice.get("bidVol").getAsDouble());
        assertTrue(tPrice.get("askPx").isJsonNull());
        assertEquals(10.0, tPrice.get("last").getAsDouble());

        assertEquals(0, tC.received.size());                                // other market
    }

    @Test
    void deletedOrderIsARemoveOrderEvent() {
        TestClient tA = connect("s1", "FirmA", 1);
        add(INST1, Order.Side.SELL, 11.00, 2, "FirmA", null);
        String tOrderId = tA.ofType(BdxMessage.ADD_ORDER).get(0).get("orderId").getAsString();
        tA.received.clear();

        assertTrue(mEngine.deleteOrder("FirmA", tOrderId).success());
        mBdx.dispatchQueued();
        assertEquals(List.of(BdxMessage.REMOVE_ORDER, BdxMessage.OWN_ORDER, BdxMessage.PRICE_UPDATE), tA.types());
        assertEquals("REMOVED", tA.ofType(BdxMessage.OWN_ORDER).get(0).get("event").getAsString());
        assertTrue(tA.ofType(BdxMessage.PRICE_UPDATE).get(0).get("askPx").isJsonNull());
    }
}
