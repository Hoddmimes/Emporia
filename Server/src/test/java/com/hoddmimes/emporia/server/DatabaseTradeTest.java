package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.InternalTrade;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import com.hoddmimes.emporia.server.me.Order;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTradeTest {

    private static final long INST = (3L << 32) | 7;
    private static final long S    = MatchingEngine.SCALE;

    @Test
    void tradeDocumentConvertsBackToTheSameTrade() {
        Order tBuy  = new Order(new AddOrderRequest(INST, Order.Side.BUY,  1050 * S / 100, 10 * S, "FirmA", "a1", "Client 7"));
        Order tSell = new Order(new AddOrderRequest(INST, Order.Side.SELL, 1040 * S / 100,  4 * S, "FirmB", null));
        InternalTrade tTrade = new InternalTrade(INST, tSell.getPrice(), 4 * S, tSell, tBuy);

        Document tDoc = Database.toTradeDocument(tTrade);
        InternalTrade tBack = Database.fromTradeDocument(tDoc);

        assertEquals(tTrade.getTradeNo(),      tBack.getTradeNo());
        assertEquals(tTrade.getTradeTime(),    tBack.getTradeTime());   // stored with millisecond precision
        assertEquals(tTrade.getInstrumentId(), tBack.getInstrumentId());
        assertEquals(tTrade.getPrice(),        tBack.getPrice());
        assertEquals(tTrade.getQuantity(),     tBack.getQuantity());

        assertEquals(tBuy.getOrderId(),  tBack.getBuyOrder().getOrderId());
        assertEquals(Order.Side.BUY,     tBack.getBuyOrder().getSide());
        assertEquals("FirmA",            tBack.getBuyOrder().getAccount());
        assertEquals("a1",               tBack.getBuyOrder().getUserRef());
        assertEquals("Client 7",         tBack.getBuyOrder().getClientAccount());
        assertEquals(tSell.getOrderId(), tBack.getSellOrder().getOrderId());
        assertEquals(Order.Side.SELL,    tBack.getSellOrder().getSide());
        assertEquals("FirmB",            tBack.getSellOrder().getAccount());
        assertNull(tBack.getSellOrder().getUserRef());
        assertEquals("omni",             tBack.getSellOrder().getClientAccount());   // none given: the default

        // Storing the converted trade again gives the same document
        assertEquals(tDoc, Database.toTradeDocument(tBack));
    }

    @Test
    void tradeStoredBeforeClientAccountsGetsTheDefaultOne() {
        Order tBuy  = new Order(new AddOrderRequest(INST, Order.Side.BUY,  S, S, "FirmA", null, "c1"));
        Order tSell = new Order(new AddOrderRequest(INST, Order.Side.SELL, S, S, "FirmB", null));
        Document tDoc = Database.toTradeDocument(new InternalTrade(INST, S, S, tBuy, tSell));
        tDoc.remove("buyClientAccount");
        tDoc.remove("sellClientAccount");
        InternalTrade tBack = Database.fromTradeDocument(tDoc);
        assertEquals("omni", tBack.getBuyOrder().getClientAccount());
        assertEquals("omni", tBack.getSellOrder().getClientAccount());
    }

    @Test
    void convertingDoesNotUseTheTradeNumberCounter() {
        Order tBuy  = new Order(new AddOrderRequest(INST, Order.Side.BUY,  S, S, "FirmA", null));
        Order tSell = new Order(new AddOrderRequest(INST, Order.Side.SELL, S, S, "FirmB", null));
        long tFirst = new InternalTrade(INST, S, S, tBuy, tSell).getTradeNo();
        Database.fromTradeDocument(Database.toTradeDocument(new InternalTrade(INST, S, S, tBuy, tSell)));
        assertEquals(tFirst + 2, new InternalTrade(INST, S, S, tBuy, tSell).getTradeNo());
    }

    @Test
    void escapedRegexMatchesTheTextLiterally() {
        String tPattern = Database.escapeRegex("a.b*(c)[d]{e}|f?g+h^i$j\\k");
        assertTrue("xa.b*(c)[d]{e}|f?g+h^i$j\\ky".matches(".*" + tPattern + ".*"));
        assertFalse("aXb*(c)[d]{e}|f?g+h^i$j\\k".matches(".*" + tPattern + ".*"));
    }
}
