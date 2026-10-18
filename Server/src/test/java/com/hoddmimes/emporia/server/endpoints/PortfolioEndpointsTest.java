package com.hoddmimes.emporia.server.endpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The positions themselves are tested in PositionCacheTest. */
class PortfolioEndpointsTest {

    private static final long S = 100000;                       // MatchingEngine.SCALE

    @Test
    void valueIsPositionTimesLastPrice() {
        assertEquals(510 * S, PortfolioEndpoints.value(5 * S, 102 * S));        // +5 @ 102
        assertEquals(-25 * S / 2, PortfolioEndpoints.value(-5 * S, 25 * S / 10)); // -5 @ 2.5
        // 1 000 000 @ 100 000 would overflow a long before dividing by SCALE
        assertEquals(100_000_000_000L * S, PortfolioEndpoints.value(1_000_000 * S, 100_000 * S));
    }
}
