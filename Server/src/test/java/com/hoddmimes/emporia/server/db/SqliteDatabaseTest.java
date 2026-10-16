package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
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
