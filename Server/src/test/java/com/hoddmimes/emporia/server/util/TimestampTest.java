package com.hoddmimes.emporia.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimestampTest {

    private static void spin(long pNanos) {
        long tEnd = System.nanoTime() + pNanos;
        while (System.nanoTime() < tEnd) Thread.onSpinWait();
    }

    @Test
    void withoutPointsOnlyTheNameAndTotalAreShown() {
        Timestamp tTimestamp = new Timestamp("op");
        assertEquals(String.format("op usec:%n  total        0.0"), tTimestamp.toString());
        assertEquals(0, tTimestamp.totalNanos());
    }

    @Test
    void measuresTimesBetweenPoints() {
        Timestamp tTimestamp = new Timestamp("op");
        spin(2_000_000);
        long tFirst = tTimestamp.mark("a");
        spin(3_000_000);
        long tSecond = tTimestamp.mark("b");

        assertTrue(tFirst  >= 2_000_000, "a: " + tFirst);
        assertTrue(tSecond >= 3_000_000, "b: " + tSecond);
        assertEquals(tFirst + tSecond, tTimestamp.totalNanos());
        String[] tLines = tTimestamp.toString().split("\\R");
        assertEquals(4, tLines.length, tTimestamp.toString());
        assertEquals("op usec:", tLines[0]);
        assertTrue(tLines[1].matches("  a     +\\d+\\.\\d \\(\\+\\d+\\.\\d\\)"), tLines[1]);
        assertTrue(tLines[2].matches("  b     +\\d+\\.\\d \\(\\+\\d+\\.\\d\\)"), tLines[2]);
        assertEquals("  total " + String.format("%10s", Timestamp.usec(tTimestamp.totalNanos())), tLines[3]);
    }

    @Test
    void formatsMicroseconds() {
        assertEquals("0.0", Timestamp.usec(0));
        assertEquals("1.5", Timestamp.usec(1_500));
        assertEquals("12.3", Timestamp.usec(12_345));
        assertEquals("2500000.0", Timestamp.usec(2_500_000_000L));
    }
}
