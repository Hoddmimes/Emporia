package com.hoddmimes.emporia.server.endpoints;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioEndpointsTest {

    private static final long S = 100000;                       // MatchingEngine.SCALE
    private static final long ABB = (1L << 32) | 1;             // market 1, instrument 1
    private static final long VOLVO = (2L << 32) | 3;           // market 2, instrument 3

    private static Document trade(long pInstrumentId, long pQuantity, String pBuyer, String pSeller) {
        return new Document("instrumentId", String.valueOf(pInstrumentId))
                .append("price", 100 * S).append("quantity", pQuantity * S)
                .append("buyAccount", pBuyer).append("sellAccount", pSeller);
    }

    @Test
    void positionIsBoughtMinusSoldPerInstrument() {
        // Sell 10, buy 5, buy 10 -> +5; trades of other firms don't count
        List<PortfolioEndpoints.Position> tPositions = PortfolioEndpoints.positions("FA", List.of(
                trade(ABB, 10, "FB", "FA"),
                trade(ABB,  5, "FA", "FB"),
                trade(VOLVO, 7, "FA", "FC"),
                trade(ABB, 10, "FA", "FC"),
                trade(ABB, 99, "FB", "FC")));
        assertEquals(2, tPositions.size());
        PortfolioEndpoints.Position tAbb = tPositions.get(0);
        assertEquals(ABB, tAbb.instrumentId());
        assertEquals(1, tAbb.marketId());
        assertEquals(15 * S, tAbb.bought());
        assertEquals(10 * S, tAbb.sold());
        assertEquals(5 * S, tAbb.position());
        assertEquals(7 * S, tPositions.get(1).position());
        assertEquals(2, tPositions.get(1).marketId());
    }

    @Test
    void tradeWithItselfNetsToZero() {
        List<PortfolioEndpoints.Position> tPositions = PortfolioEndpoints.positions("FA", List.of(trade(ABB, 4, "FA", "FA")));
        assertEquals(0, tPositions.get(0).position());
        assertEquals(4 * S, tPositions.get(0).bought());
    }

    private static Document trade(long pInstrumentId, long pQuantity, String pBuyer, String pBuyerAccount,
                                  String pSeller, String pSellerAccount) {
        return trade(pInstrumentId, pQuantity, pBuyer, pSeller)
                .append("buyClientAccount", pBuyerAccount).append("sellClientAccount", pSellerAccount);
    }

    @Test
    void positionsArePerClientAccount() {
        List<PortfolioEndpoints.Position> tPositions = PortfolioEndpoints.positions("FA", List.of(
                trade(ABB, 10, "FA", "c1",  "FB", null),
                trade(ABB,  4, "FB", null,  "FA", "c2"),
                trade(ABB,  3, "FA", null,  "FB", "x"),     // stored without a client account: omni
                trade(ABB,  2, "FA", "c2",  "FA", "c1")));  // between two of FA's accounts: c1 -> c2
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

    private static Document bulletinTrade(int pMarketId, String pInstrument, long pQuantity, String pBuyer, String pSeller) {
        return new Document("marketId", pMarketId).append("instrument", pInstrument)
                .append("price", 80 * S).append("quantity", pQuantity * S)
                .append("buyAccount", pBuyer).append("sellAccount", pSeller);
    }

    @Test
    void bulletinPositionsArePerMarketAndInstrumentText() {
        List<PortfolioEndpoints.BulletinPosition> tPositions = PortfolioEndpoints.bulletinPositions("FA", List.of(
                bulletinTrade(5, "Brent Jan", 10, "FA", "FB"),
                bulletinTrade(5, "Brent Jan",  3, "FB", "FA"),
                bulletinTrade(6, "Brent Jan",  2, "FB", "FA"),    // another market: its own position
                bulletinTrade(5, "brent jan",  1, "FA", "FB")));  // another instrument text
        assertEquals(3, tPositions.size());
        assertEquals(7 * S, tPositions.get(0).position());
        assertEquals(10 * S, tPositions.get(0).bought());
        assertEquals(3 * S, tPositions.get(0).sold());
        assertEquals(6, tPositions.get(1).marketId());
        assertEquals(-2 * S, tPositions.get(1).position());
        assertEquals("brent jan", tPositions.get(2).instrument());
        assertEquals("omni", tPositions.get(0).clientAccount());   // stored without a client account
    }

    @Test
    void bulletinPositionsArePerClientAccount() {
        List<PortfolioEndpoints.BulletinPosition> tPositions = PortfolioEndpoints.bulletinPositions("FA", List.of(
                bulletinTrade(5, "Brent Jan", 10, "FA", "FB").append("buyClientAccount", "c1"),
                bulletinTrade(5, "Brent Jan",  4, "FB", "FA").append("sellClientAccount", "c2"),
                bulletinTrade(5, "Brent Jan",  1, "FB", "FA").append("sellClientAccount", "c1")));
        assertEquals(2, tPositions.size());
        assertEquals("c1", tPositions.get(0).clientAccount());
        assertEquals(9 * S, tPositions.get(0).position());
        assertEquals("c2", tPositions.get(1).clientAccount());
        assertEquals(-4 * S, tPositions.get(1).position());
    }

    @Test
    void valueIsPositionTimesLastPrice() {
        assertEquals(510 * S, PortfolioEndpoints.value(5 * S, 102 * S));        // +5 @ 102
        assertEquals(-25 * S / 2, PortfolioEndpoints.value(-5 * S, 25 * S / 10)); // -5 @ 2.5
        // 1 000 000 @ 100 000 would overflow a long before dividing by SCALE
        assertEquals(100_000_000_000L * S, PortfolioEndpoints.value(1_000_000 * S, 100_000 * S));
    }
}
