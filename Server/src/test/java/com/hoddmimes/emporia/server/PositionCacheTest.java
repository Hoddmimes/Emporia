package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.db.BulletinTrade;
import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.InternalTrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionCacheTest {

    private static final long S = 100000;                       // MatchingEngine.SCALE
    private static final long ABB = (1L << 32) | 1;             // market 1, instrument 1
    private static final long VOLVO = (2L << 32) | 3;           // market 2, instrument 3

    private final PositionCache mCache = new PositionCache();

    private static InternalTrade trade(long pInstrumentId, long pQuantity, String pBuyer, String pSeller) {
        return trade(pInstrumentId, 100, pQuantity, pBuyer, null, pSeller, null);
    }

    /** A stored trade; a null client account is the default one. */
    private static InternalTrade trade(long pInstrumentId, long pPrice, long pQuantity, String pBuyer, String pBuyerAccount,
                                       String pSeller, String pSellerAccount) {
        return new InternalTrade(1, 0, pInstrumentId, pPrice * S, pQuantity * S,
                1, pBuyer,  null, orDefault(pBuyerAccount),
                2, pSeller, null, orDefault(pSellerAccount));
    }

    private static String orDefault(String pClientAccount) {
        return (pClientAccount != null) ? pClientAccount : AddOrderRequest.DEFAULT_CLIENT_ACCOUNT;
    }

    @Test
    void positionIsBoughtMinusSoldPerInstrument() {
        // Sell 10, buy 5, buy 10 -> +5; trades of other firms don't count
        mCache.addTrades(List.of(
                trade(ABB, 10, "FB", "FA"),
                trade(ABB,  5, "FA", "FB"),
                trade(VOLVO, 7, "FA", "FC"),
                trade(ABB, 10, "FA", "FC"),
                trade(ABB, 99, "FB", "FC")));
        List<PositionCache.Position> tPositions = mCache.positions("FA");
        assertEquals(2, tPositions.size());
        PositionCache.Position tAbb = tPositions.get(0);
        assertEquals(ABB, tAbb.instrumentId());
        assertEquals(1, tAbb.marketId());
        assertEquals(15 * S, tAbb.bought());
        assertEquals(10 * S, tAbb.sold());
        assertEquals(5 * S, tAbb.position());
        assertEquals(7 * S, tPositions.get(1).position());
        assertEquals(2, tPositions.get(1).marketId());
        assertEquals(104 * S, mCache.positions("FB").get(0).position());   // 10 - 5 + 99
        assertTrue(mCache.positions("FX").isEmpty());
    }

    @Test
    void tradeWithItselfNetsToZero() {
        mCache.addTrade(trade(ABB, 4, "FA", "FA"));
        List<PositionCache.Position> tPositions = mCache.positions("FA");
        assertEquals(1, tPositions.size());
        assertEquals(0, tPositions.get(0).position());
        assertEquals(4 * S, tPositions.get(0).bought());
    }

    @Test
    void positionsArePerClientAccount() {
        mCache.addTrades(List.of(
                trade(ABB, 100, 10, "FA", "c1",  "FB", null),
                trade(ABB, 100,  4, "FB", null,  "FA", "c2"),
                trade(ABB, 100,  3, "FA", null,  "FB", "x"),     // the default account: omni
                trade(ABB, 100,  2, "FA", "c2",  "FA", "c1")));  // between two of FA's accounts: c1 -> c2
        List<PositionCache.Position> tPositions = mCache.positions("FA");
        assertEquals(3, tPositions.size());
        assertEquals("c1", tPositions.get(0).clientAccount());
        assertEquals(8 * S, tPositions.get(0).position());
        assertEquals(10 * S, tPositions.get(0).bought());
        assertEquals(2 * S, tPositions.get(0).sold());
        assertEquals("c2", tPositions.get(1).clientAccount());
        assertEquals(-2 * S, tPositions.get(1).position());
        assertEquals("omni", tPositions.get(2).clientAccount());
        assertEquals(3 * S, tPositions.get(2).position());
    }

    @Test
    void reversalNetsOutAndIsNoLastPrice() {
        InternalTrade tTrade = trade(ABB, 101, 6, "FA", "c1", "FB", null);
        mCache.addTrade(tTrade);
        assertEquals(101 * S, mCache.lastTradePrice(ABB));
        mCache.addTrade(InternalTrade.reversal(tTrade));
        assertEquals(0, mCache.positions("FA").get(0).position());
        assertEquals(6 * S, mCache.positions("FA").get(0).sold());
        assertEquals(0, mCache.positions("FB").get(0).position());
        assertEquals(101 * S, mCache.lastTradePrice(ABB));
        mCache.addTrade(trade(ABB, 99, 1, "FC", null, "FB", null));
        assertEquals(99 * S, mCache.lastTradePrice(ABB));   // the latest trade's
        assertEquals(0, mCache.lastTradePrice(VOLVO));
    }

    private static BulletinTrade bulletinTrade(int pMarketId, String pInstrument, long pQuantity, String pBuyer, String pSeller) {
        return bulletinTrade(pMarketId, pInstrument, 80, pQuantity, pBuyer, null, pSeller, null);
    }

    /** A stored bulletin trade; a null client account is the default one. */
    private static BulletinTrade bulletinTrade(int pMarketId, String pInstrument, long pPrice, long pQuantity,
                                               String pBuyer, String pBuyerAccount, String pSeller, String pSellerAccount) {
        return new BulletinTrade("t", "b", pMarketId, pInstrument, pPrice * S, pQuantity * S, 0,
                pBuyer, "u", null, orDefault(pBuyerAccount), pSeller, "u", null, orDefault(pSellerAccount));
    }

    @Test
    void bulletinPositionsArePerMarketAndInstrumentText() {
        for (BulletinTrade tTrade : List.of(
                bulletinTrade(5, "Brent Jan", 10, "FA", "FB"),
                bulletinTrade(5, "Brent Jan",  3, "FB", "FA"),
                bulletinTrade(6, "Brent Jan",  2, "FB", "FA"),      // another market: its own position
                bulletinTrade(5, "brent jan",  1, "FA", "FB"))) {   // another instrument text
            mCache.addBulletinTrade(tTrade);
        }
        List<PositionCache.BulletinPosition> tPositions = mCache.bulletinPositions("FA");
        assertEquals(3, tPositions.size());
        assertEquals(7 * S, tPositions.get(0).position());
        assertEquals(10 * S, tPositions.get(0).bought());
        assertEquals(3 * S, tPositions.get(0).sold());
        assertEquals(6, tPositions.get(1).marketId());
        assertEquals(-2 * S, tPositions.get(1).position());
        assertEquals("brent jan", tPositions.get(2).instrument());
        assertEquals("omni", tPositions.get(0).clientAccount());   // the default account
        assertEquals(-7 * S, mCache.bulletinPositions("FB").get(0).position());
    }

    @Test
    void bulletinPositionsArePerClientAccount() {
        mCache.addBulletinTrade(bulletinTrade(5, "Brent Jan", 80, 10, "FA", "c1", "FB", null));
        mCache.addBulletinTrade(bulletinTrade(5, "Brent Jan", 80,  4, "FB", null, "FA", "c2"));
        mCache.addBulletinTrade(bulletinTrade(5, "Brent Jan", 80,  1, "FB", null, "FA", "c1"));
        List<PositionCache.BulletinPosition> tPositions = mCache.bulletinPositions("FA");
        assertEquals(2, tPositions.size());
        assertEquals("c1", tPositions.get(0).clientAccount());
        assertEquals(9 * S, tPositions.get(0).position());
        assertEquals("c2", tPositions.get(1).clientAccount());
        assertEquals(-4 * S, tPositions.get(1).position());
    }

    @Test
    void bulletinReversalNetsOutAndIsNoLastPrice() {
        BulletinTrade tTrade = bulletinTrade(5, "Brent Jan", 82, 3, "FA", "c1", "FB", null);
        mCache.addBulletinTrade(tTrade);
        mCache.addBulletinTrade(tTrade.reversal("t2", 1));
        assertEquals(0, mCache.bulletinPositions("FA").get(0).position());
        assertEquals(0, mCache.bulletinPositions("FB").get(0).position());
        assertEquals(82 * S, mCache.lastBulletinTradePrice(5, "Brent Jan"));
        assertEquals(0, mCache.lastBulletinTradePrice(5, "brent jan"));
        assertEquals(0, mCache.lastBulletinTradePrice(6, "Brent Jan"));
    }
}
