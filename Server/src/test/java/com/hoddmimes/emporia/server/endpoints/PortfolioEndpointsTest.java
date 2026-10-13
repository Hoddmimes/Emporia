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

    @Test
    void valueIsPositionTimesLastPrice() {
        assertEquals(510 * S, PortfolioEndpoints.value(5 * S, 102 * S));        // +5 @ 102
        assertEquals(-25 * S / 2, PortfolioEndpoints.value(-5 * S, 25 * S / 10)); // -5 @ 2.5
        // 1 000 000 @ 100 000 would overflow a long before dividing by SCALE
        assertEquals(100_000_000_000L * S, PortfolioEndpoints.value(1_000_000 * S, 100_000 * S));
    }
}
