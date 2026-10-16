package com.hoddmimes.emporia.server.db;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ids of the same form as a MongoDB ObjectId (24 hex digits: 4 bytes of seconds, 5 random bytes fixed
 * per process, 3 bytes of a counter), so they increase over time: the databaseIds of SQLite and the
 * chat message ids.
 */
public final class DatabaseIds {

    private static final byte[]        PROCESS = new byte[5];
    private static final AtomicInteger COUNTER;
    static {
        SecureRandom tRandom = new SecureRandom();
        tRandom.nextBytes(PROCESS);
        COUNTER = new AtomicInteger(tRandom.nextInt());
    }

    private DatabaseIds() {}

    public static String newId() {
        int  tSeconds = (int) (System.currentTimeMillis() / 1000);
        int  tCount   = COUNTER.getAndIncrement();
        byte[] tId = new byte[12];
        tId[0] = (byte) (tSeconds >>> 24); tId[1] = (byte) (tSeconds >>> 16); tId[2] = (byte) (tSeconds >>> 8); tId[3] = (byte) tSeconds;
        System.arraycopy(PROCESS, 0, tId, 4, 5);
        tId[9] = (byte) (tCount >>> 16); tId[10] = (byte) (tCount >>> 8); tId[11] = (byte) tCount;
        return HexFormat.of().formatHex(tId);
    }

    /** The id, if it is 24 hex digits; otherwise IllegalArgumentException (the admin API answers 400). */
    public static String check(String pId) {
        if (pId == null || !pId.matches("[0-9a-fA-F]{24}")) throw new IllegalArgumentException("Invalid id '" + pId + "'");
        return pId.toLowerCase();
    }
}
