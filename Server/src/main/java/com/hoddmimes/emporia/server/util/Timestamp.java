package com.hoddmimes.emporia.server.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects timestamps at labelled measuring points in code and reports the execution times
 * between them. Timestamps are taken with {@link System#nanoTime()}.
 *
 * <pre>
 * Timestamp tTimestamp = new Timestamp("addOrder");   // start point
 * ...
 * tTimestamp.mark("validated");
 * ...
 * tTimestamp.mark("matched");
 * cLog.debug(tTimestamp);
 * </pre>
 *
 * logs
 *
 * <pre>
 * addOrder usec:
 *   validated       3.1 (+3.1)
 *   matched        12.3 (+9.2)
 *   total          12.3
 * </pre>
 *
 * A Timestamp measures one execution and is not thread-safe; create a new one for every
 * measurement. Only the constructor, {@link #mark(String)} and {@link #toString()} are public;
 * the rest is package-private for the unit test.
 */
public final class Timestamp {

    /** A measuring point: its label and its time in nanoseconds since the start. */
    private record Point(String label, long nanos) {}

    private final String      mName;
    private final List<Point> mPoints = new ArrayList<>();
    private final long        mStart;

    public Timestamp(String pName) {
        mName  = pName;
        mStart = System.nanoTime();
    }

    /** Records a measuring point; returns the time since the previous point (or the start) in nanoseconds. */
    public long mark(String pLabel) {
        long tNanos    = System.nanoTime() - mStart;
        long tPrevious = mPoints.isEmpty() ? 0L : mPoints.get(mPoints.size() - 1).nanos();
        mPoints.add(new Point(pLabel, tNanos));
        return tNanos - tPrevious;
    }

    /** Nanoseconds from the start to the last measuring point; 0 if there is none. */
    long totalNanos() {
        return mPoints.isEmpty() ? 0L : mPoints.get(mPoints.size() - 1).nanos();
    }

    /**
     * The name, then one line per point with its timestamp relative to the start in microseconds
     * and the time since the previous point in parentheses, and a last line with the total time from
     * the start to the last point (see the class comment). The labels are padded to the same width.
     */
    @Override
    public String toString() {
        int tWidth = "total".length();
        for (Point p : mPoints) tWidth = Math.max(tWidth, p.label().length());
        String tFormat = "%n  %-" + tWidth + "s %10s";

        StringBuilder sb = new StringBuilder(mName).append(" usec:");
        long tPrevious = 0L;
        for (Point p : mPoints) {
            sb.append(String.format(Locale.ROOT, tFormat, p.label(), usec(p.nanos())))
              .append(" (+").append(usec(p.nanos() - tPrevious)).append(')');
            tPrevious = p.nanos();
        }
        sb.append(String.format(Locale.ROOT, tFormat, "total", usec(totalNanos())));
        return sb.toString();
    }

    /** Formats nanoseconds as microseconds with one decimal, e.g. 12345 as "12.3". */
    static String usec(long pNanos) {
        return String.format(Locale.ROOT, "%.1f", pNanos / 1_000.0);
    }
}
