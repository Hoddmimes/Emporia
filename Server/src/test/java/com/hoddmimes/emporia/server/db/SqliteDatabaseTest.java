package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.me.InternalTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Database contract on SqliteDatabase, a new database file per test. */
class SqliteDatabaseTest extends DatabaseContractTest {

    @TempDir
    Path mDir;

    @Override
    Database open() {
        return Database.open(config());
    }

    private JsonObject config() {
        JsonObject tConfig = new JsonObject();
        tConfig.addProperty("type", "sqlite");
        tConfig.addProperty("file", mDir.resolve("emporia.db").toString());
        return tConfig;
    }

    @Test
    void dataSurvivesReopening() {
        mDb.createFirm("FA", "ACTIVE", "", "", "", "");
        mDb.saveTrades(List.of(trade(1, "2026-10-16T09:00:00.000", (1L << 32) | 1, 100, "FA", "FB")));
        mDb.close();
        mDb = Database.open(config());   // the tables exist already
        assertNotNull(mDb.getFirmByName("FA"));
        assertEquals(1, mDb.listTradesByFirm("FA", null).size());
    }

    @Test
    void columnsAddedToAnOlderDatabaseFile() throws Exception {
        mDb.close();
        Path tFile = mDir.resolve("emporia.db");
        java.nio.file.Files.delete(tFile);
        // The Trades table as created before the traders and reversals, with a trade in it
        try (var tConn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tFile);
             var tStmt = tConn.createStatement()) {
            tStmt.execute("""
                CREATE TABLE Trades (seq INTEGER PRIMARY KEY AUTOINCREMENT, tradeNo TEXT,
                    marketId INTEGER, instrumentId TEXT, price INTEGER, quantity INTEGER, tradeTime TEXT,
                    buyOrderId TEXT, buyAccount TEXT, buyUserRef TEXT, buyClientAccount TEXT,
                    sellOrderId TEXT, sellAccount TEXT, sellUserRef TEXT, sellClientAccount TEXT)""");
            tStmt.execute("""
                INSERT INTO Trades (tradeNo, marketId, instrumentId, price, quantity, tradeTime, buyOrderId, buyAccount,
                    buyClientAccount, sellOrderId, sellAccount, sellClientAccount)
                VALUES ('1', 1, '4294967297', 100, 5, '2026-10-16 09:00:00.000', 'a1', 'FA', 'omni', 'b1', 'FB', 'omni')""");
        }
        mDb = Database.open(config());
        InternalTrade tOld = mDb.listTradesByDate("2026-10-16").get(0);
        assertNull(tOld.getBuyOrder().getUser());
        assertFalse(tOld.isReversal());
        assertEquals(100, mDb.lastTradePrice(4294967297L));
        assertTrue(mDb.listReversalTrades().isEmpty());
        mDb.saveTrades(List.of(trade(2, "2026-10-16T10:00:00.000", 4294967297L, 101, "FA", "FB")));
        assertEquals(2, mDb.listTradesByFirm("FA", null).size());
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertEquals("50\\%\\_a\\\\b", SqliteDatabase.escapeLike("50%_a\\b"));
    }

    @Test
    void unknownDatabaseType() {
        JsonObject tConfig = new JsonObject();
        tConfig.addProperty("type", "oracle");
        assertThrows(IllegalArgumentException.class, () -> Database.open(tConfig));
    }
}
