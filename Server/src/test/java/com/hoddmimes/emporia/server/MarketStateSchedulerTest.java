package com.hoddmimes.emporia.server;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static com.hoddmimes.emporia.server.MarketStateScheduler.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketStateSchedulerTest {

    // October 2026: Fri 9, Sat 10, Sun 11, Mon 12, Tue 13
    private static final int FRI = 9, SAT = 10, SUN = 11, MON = 12, TUE = 13;

    private static InstrumentCache.MarketEntry market(String pPreOpen, String pOpen, String pClose) {
        return new InstrumentCache.MarketEntry(1, "XSTO", "", "CONTINUOUS", pOpen, pClose, false,
                pPreOpen, "", "", "", "CLOSED");
    }

    private static LocalDateTime at(int pDay, String pTime) {
        return LocalDateTime.of(2026, 10, pDay, 0, 0).with(LocalTime.parse(pTime));
    }

    @Test
    void withoutPreOpenTimeTheMarketGoesFromClosedToOpen() {
        var tMarket = market("", "9:00", "22:00");                  // "9:00" without leading zero
        assertTrue(hasValidHours(tMarket));
        assertNull(transitionBetween(tMarket, at(MON, "08:00"), at(MON, "08:59:59")));
        assertEquals(OPEN,   transitionBetween(tMarket, at(MON, "08:59:59"), at(MON, "09:00")));
    }

    @Test
    void invalidMarketHoursAreNotScheduled() {
        assertFalse(hasValidHours(market("", "", "22:00")));
        assertFalse(hasValidHours(market("", "nine", "22:00")));
        assertNull(transitionBetween(market("", "", ""), at(MON, "00:00"), at(TUE, "00:00")));
    }

    @Test
    void transitionHappensOnlyWhenItsTimeIsPassed() {
        var tMarket = market("08:30", "09:00", "22:00");
        assertEquals(PREOPEN, transitionBetween(tMarket, at(MON, "08:29:59"), at(MON, "08:30")));
        assertEquals(OPEN,    transitionBetween(tMarket, at(MON, "08:59:59"), at(MON, "09:00")));
        assertEquals(CLOSED,  transitionBetween(tMarket, at(MON, "21:59:59"), at(MON, "22:00")));
        // No time passed inside the trading day: a manual change stands
        assertNull(transitionBetween(tMarket, at(MON, "10:00"), at(MON, "10:00:01")));
        assertNull(transitionBetween(tMarket, at(MON, "09:00"), at(MON, "21:59:59")));
    }

    @Test
    void latestTransitionWinsWhenSeveralArePassed() {
        var tMarket = market("08:30", "09:00", "22:00");
        assertEquals(OPEN,   transitionBetween(tMarket, at(MON, "08:00"), at(MON, "10:00")));   // after a pause
        assertEquals(CLOSED, transitionBetween(tMarket, at(MON, "08:00"), at(MON, "23:00")));
    }

    @Test
    void intervalAcrossMidnightFindsTheNextDaysTransitions() {
        var tMarket = market("00:00:30", "09:00", "22:00");
        assertNull(transitionBetween(tMarket, at(MON, "23:59:59"), at(TUE, "00:00:00")));
        assertEquals(PREOPEN, transitionBetween(tMarket, at(MON, "23:59:59"), at(TUE, "00:00:30")));
    }

    @Test
    void marketsStayClosedOnWeekends() {
        var tMarket = market("08:30", "09:00", "22:00");
        assertNull(transitionBetween(tMarket, at(SAT, "08:29:59"), at(SAT, "08:30")));    // no pre-open
        assertNull(transitionBetween(tMarket, at(SUN, "08:59:59"), at(SUN, "09:00")));    // no open
        assertEquals(CLOSED, transitionBetween(tMarket, at(SAT, "21:59:59"), at(SAT, "22:00")));   // a manual open is closed
        assertNull(transitionBetween(tMarket, at(FRI, "23:59:59"), at(SAT, "09:00")));     // Friday night into Saturday
        assertEquals(OPEN, transitionBetween(tMarket, at(SUN, "23:00"), at(MON, "09:00"))); // Monday opens again
    }
}
