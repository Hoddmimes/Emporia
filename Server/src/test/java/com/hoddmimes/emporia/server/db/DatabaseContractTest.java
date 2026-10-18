package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache.InstrumentEntry;
import com.hoddmimes.emporia.server.InstrumentCache.MarketEntry;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.InternalTrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The behaviour every Database implementation must have; run against each of them by a subclass
 * (SqliteDatabaseTest, MongoDbDatabaseContractTest), each test on an empty database.
 */
abstract class DatabaseContractTest {

    static final long S = 100000;   // MatchingEngine.SCALE

    Database mDb;

    /** A new, empty database. */
    abstract Database open() throws Exception;

    @BeforeEach
    void setUp() throws Exception {
        mDb = open();
    }

    @AfterEach
    void tearDown() {
        if (mDb != null) mDb.close();
    }

    static long time(String pDateTime) {
        return LocalDateTime.parse(pDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // ── Admin and login ──────────────────────────────────────────────────

    @Test
    void defaultAdminAndPasswordChange() {
        mDb.initializeAdmin();
        mDb.initializeAdmin();   // the second time nothing changes
        assertTrue(mDb.verifyAdminLogin("admin", "changeit"));   // the user name is case-insensitive
        assertFalse(mDb.verifyAdminLogin("Admin", "wrong"));
        assertFalse(mDb.verifyAdminLogin("Nobody", "changeit"));
        assertFalse(mDb.changeAdminPassword("Admin", "wrong", "new"));
        assertTrue(mDb.changeAdminPassword("Admin", "changeit", "new"));
        assertTrue(mDb.verifyAdminLogin("Admin", "new"));
        mDb.updateLastSeen("ADMIN");
        mDb.initializeAdmin();
        assertTrue(mDb.verifyAdminLogin("Admin", "new"));
    }

    @Test
    void userLogin() {
        mDb.createFirm("FA", "ACTIVE", null, null, null, null);
        mDb.createFirm("FS", "SUSPENDED", "", "", "", "");
        mDb.createUser("anna", "FA", "Anna", "", "", "ACTIVE", "pw");
        mDb.createUser("olof", "FA", "Olof", "", "", "INACTIVE", "pw");
        mDb.createUser("erik", "FS", "Erik", "", "", "ACTIVE", "pw");
        mDb.createUser("nopw", "FA", "No", "", "", "ACTIVE", "");

        JsonObject tOk = mDb.validateUserLogin("FA", "anna", "pw");
        assertTrue(tOk.get("success").getAsBoolean());
        assertEquals("anna", tOk.get("userId").getAsString());
        assertEquals("Invalid credentials", message(mDb.validateUserLogin("FA", "anna", "bad")));
        mDb.createFirm("FB", "ACTIVE", "", "", "", "");
        assertEquals("Invalid credentials", message(mDb.validateUserLogin("FB", "anna", "pw")));     // another firm's user
        assertEquals("Firm is suspended", message(mDb.validateUserLogin("FS", "erik", "pw")));
        assertEquals("Firm not found", message(mDb.validateUserLogin("FX", "anna", "pw")));
        assertEquals("User account is suspended", message(mDb.validateUserLogin("FA", "olof", "pw")));
        assertEquals("Invalid credentials", message(mDb.validateUserLogin("FA", "nopw", "")));
        assertEquals("Invalid credentials", message(mDb.validateUserLogin("FA", "nobody", "pw")));
    }

    private static String message(JsonObject pResult) {
        assertFalse(pResult.get("success").getAsBoolean());
        return pResult.get("message").getAsString();
    }

    // ── Firms, users, authorizations ─────────────────────────────────────

    @Test
    void firms() {
        String tId = mDb.createFirm("FA", "ACTIVE", "Street 1", "123", "Anna", "a@x");
        assertTrue(tId.matches("[0-9a-f]{24}"));
        mDb.createFirm("FB", "INACTIVE", null, null, null, null);

        Stored<Firm> tFirm = mDb.getFirm(tId);
        assertEquals(tId, tFirm.databaseId());
        assertEquals(new Firm("FA", "ACTIVE", "Street 1", "123", "Anna", "a@x"), tFirm.value());
        assertEquals(List.of("FA", "FB"), mDb.listFirms().stream().map(f -> f.value().name()).toList());
        assertEquals("", mDb.getFirmByName("FB").address());
        assertNull(mDb.getFirmByName("FC"));

        mDb.updateFirm(tId, "SUSPENDED", "Street 2", "", "", "");
        assertEquals(new Firm("FA", "SUSPENDED", "Street 2", "", "", ""), mDb.getFirm(tId).value());   // the name stays

        assertThrows(IllegalArgumentException.class, () -> mDb.getFirm("not-an-id"));
        assertNull(mDb.getFirm(DatabaseIds.newId()));
    }

    @Test
    void deletingAFirmDeletesItsAuthorizations() {
        String tFa = mDb.createFirm("FA", "ACTIVE", "", "", "", "");
        mDb.createFirm("FB", "ACTIVE", "", "", "", "");
        mDb.createAuthorization("FA", "1");
        mDb.createAuthorization("FA", "*");
        mDb.createAuthorization("FB", "1");

        assertEquals("FA", mDb.deleteFirm(tFa));
        assertNull(mDb.getFirm(tFa));
        assertEquals(List.of(new Authorization("FB", "1")), mDb.listAuthorizations());
        assertNull(mDb.deleteFirm(tFa));
    }

    @Test
    void users() {
        String tId = mDb.createUser("anna", "FA", "Anna", "a@x", "1", null, "pw");
        Stored<User> tUser = mDb.getUser(tId);
        assertEquals("INACTIVE", tUser.value().status());
        assertEquals(Database.hashSha512("pw"), tUser.value().password());
        assertEquals(tUser.value(), mDb.getUserByUserId("anna"));
        assertNull(mDb.getUserByUserId("nobody"));

        mDb.updateUser(tId, "FB", "Anna L", "", "2", "ACTIVE", "");   // the password stays
        assertEquals(new User("anna", "FB", "Anna L", "", "2", "ACTIVE", Database.hashSha512("pw")), mDb.getUser(tId).value());
        mDb.updateUser(tId, "FB", "Anna L", "", "2", "ACTIVE", "new");
        assertEquals(Database.hashSha512("new"), mDb.getUser(tId).value().password());

        mDb.createUser("erik", "FB", "Erik", "", "", "ACTIVE", null);
        assertEquals("", mDb.getUserByUserId("erik").password());
        assertEquals(2, mDb.listUsers().size());
        mDb.deleteUser(tId);
        assertEquals(List.of("erik"), mDb.listUsers().stream().map(u -> u.value().userId()).toList());
        assertThrows(IllegalArgumentException.class, () -> mDb.deleteUser("x"));
    }

    @Test
    void authorizations() {
        String tId = mDb.createAuthorization("FA", "1");
        mDb.createAuthorization("FA", "*");
        mDb.createAuthorization("FB", "2");
        assertTrue(mDb.authorizationExists("FA", "*"));
        assertFalse(mDb.authorizationExists("FB", "1"));
        List<Stored<Authorization>> tFa = mDb.listAuthorizationsByFirm("FA");
        assertEquals(List.of(new Authorization("FA", "1"), new Authorization("FA", "*")), tFa.stream().map(Stored::value).toList());
        assertEquals(tId, tFa.get(0).databaseId());
        mDb.deleteAuthorization(tId);
        assertFalse(mDb.authorizationExists("FA", "1"));
        assertEquals(2, mDb.listAuthorizations().size());
    }

    // ── Markets, bulletin markets, instruments ───────────────────────────

    static MarketEntry market(int pMarketId, String pName, String pStatus) {
        return new MarketEntry(pMarketId, pName, "desc", "AUCTION", "09:00", "17:00", true,
                "08:45", "", "900", "60", pStatus);
    }

    @Test
    void markets() {
        String tId = mDb.createMarket(market(1, "Grains", "CLOSED"));
        mDb.createMarket(market(2, "Metals", "OPEN"));
        assertEquals(market(1, "Grains", "CLOSED"), mDb.getMarket(tId).value());
        assertEquals(List.of(1, 2), mDb.listMarkets().stream().map(m -> m.value().marketId()).toList());

        // The marketId never changes, the name only if one is given
        MarketEntry tEdit = new MarketEntry(9, "", "new desc", "CONTINUOUS", "08:00", "16:00", false, "", "", "", "", "SUSPENDED");
        mDb.updateMarket(tId, tEdit);
        assertEquals(new MarketEntry(1, "Grains", "new desc", "CONTINUOUS", "08:00", "16:00", false, "", "", "", "", "SUSPENDED"),
                mDb.getMarket(tId).value());
        mDb.updateMarket(tId, new MarketEntry(0, "Cereals", "", "CONTINUOUS", "", "", false, "", "", "", "", "CLOSED"));
        assertEquals("Cereals", mDb.getMarket(tId).value().name());

        assertEquals("OPEN", mDb.setMarketStatus(1, "OPEN").tradingStatus());
        assertEquals("OPEN", mDb.getMarket(tId).value().tradingStatus());
        assertNull(mDb.setMarketStatus(7, "OPEN"));

        mDb.deleteMarket(tId);
        assertNull(mDb.getMarket(tId));
        assertEquals(1, mDb.listMarkets().size());
    }

    @Test
    void bulletinMarkets() {
        BulletinMarket tMarket = BulletinMarket.of(3, "OTC", "desc", "CLOSED", "07:00", "19:00",
                List.of("Old tractor", "/Brent.*/"), "168:00");
        String tId = mDb.createBulletinMarket(tMarket);
        BulletinMarket tBack = mDb.getBulletinMarket(tId).value();
        assertEquals(tMarket.toJson(), tBack.toJson());
        assertTrue(tBack.allows("Brent Jan"));
        assertFalse(tBack.allows("old tractor"));

        mDb.updateBulletinMarket(tId, BulletinMarket.of(9, "Other", "new", "OPEN", "08:00", "18:00", List.of("x"), "1:00"));
        BulletinMarket tUpdated = mDb.getBulletinMarket(tId).value();
        assertEquals(3, tUpdated.marketId());          // the marketId and name stay
        assertEquals("OTC", tUpdated.name());
        assertEquals(List.of("x"), tUpdated.instruments());
        assertEquals("1:00", tUpdated.maxOrderTime());

        assertEquals("SUSPENDED", mDb.setBulletinMarketStatus(3, "SUSPENDED").tradingStatus());
        assertNull(mDb.setBulletinMarketStatus(4, "OPEN"));
        assertEquals(1, mDb.listBulletinMarkets().size());
        mDb.deleteBulletinMarket(tId);
        assertTrue(mDb.listBulletinMarkets().isEmpty());
    }

    static InstrumentEntry instrument(long pInstrumentId, int pMarketId, String pName) {
        return new InstrumentEntry(pInstrumentId, pMarketId, pName, "FUTURE", S / 4, 10 * S, "SE0001",
                "ACTIVE", "2027-03-15", 230 * S, "Wheat", "");
    }

    @Test
    void instruments() {
        long tWheat = (1L << 32) | 1, tCorn = (1L << 32) | 2, tGold = (2L << 32) | 1;
        String tId = mDb.createInstrument(instrument(tWheat, 1, "WHEAT"));
        mDb.createInstrument(instrument(tCorn, 1, "CORN"));
        mDb.createInstrument(instrument(tGold, 2, "GOLD"));
        assertEquals(instrument(tWheat, 1, "WHEAT"), mDb.getInstrument(tId).value());
        assertEquals(List.of("WHEAT", "CORN"), mDb.listInstrumentsByMarket(1).stream().map(i -> i.value().name()).toList());

        // The instrumentId and status never change in an update, the name only if one is given
        mDb.updateInstrument(tId, new InstrumentEntry(0, 1, "", "COMMODITY", S, S, "", "", "", 0, "", ""));
        assertEquals(new InstrumentEntry(tWheat, 1, "WHEAT", "COMMODITY", S, S, "", "ACTIVE", "", 0, "", ""),
                mDb.getInstrument(tId).value());
        mDb.updateInstrumentStatus(tId, "SUSPENDED");
        assertEquals("SUSPENDED", mDb.getInstrument(tId).value().tradingStatus());

        assertTrue(mDb.deleteInstrumentByInstrumentId(tCorn));
        assertFalse(mDb.deleteInstrumentByInstrumentId(tCorn));
        mDb.deleteInstrument(tId);
        assertTrue(mDb.listInstrumentsByMarket(1).isEmpty());
        assertEquals(1, mDb.listInstrumentsByMarket(2).size());
    }

    // ── Bulletins ────────────────────────────────────────────────────────

    @Test
    void bulletins() {
        StoredBulletin tB1 = new StoredBulletin("b1", 3, "Old tractor", "SELL", 100 * S, 10 * S, 10 * S,
                "FA", "anna", "r1", "c1", 2000, 9000);
        StoredBulletin tB2 = new StoredBulletin("b2", 3, "Brent Jan", null, 0, 0, 0,
                "FB", "erik", null, "omni", 1000, 9000);
        mDb.saveBulletin(tB1);
        mDb.saveBulletin(tB2);
        assertEquals(List.of(tB2, tB1), mDb.listBulletins());   // oldest first

        StoredBulletin tTraded = new StoredBulletin("b1", 3, "Old tractor", "SELL", 100 * S, 10 * S, 6 * S,
                "FA", "anna", "r1", "c1", 2000, 9000);
        mDb.saveBulletin(tTraded);   // replaces it
        assertEquals(List.of(tB2, tTraded), mDb.listBulletins());
        mDb.deleteBulletin("b2");
        assertEquals(List.of(tTraded), mDb.listBulletins());
    }

    // ── Trades ───────────────────────────────────────────────────────────

    static InternalTrade trade(long pTradeNo, String pTime, long pInstrumentId, long pPrice, String pBuyer, String pSeller) {
        return new InternalTrade(pTradeNo, time(pTime), pInstrumentId, pPrice * S, 5 * S,
                0xa0 + pTradeNo, pBuyer, "b-ref", "bc",
                0xb0 + pTradeNo, pSeller, null, "omni");
    }

    @Test
    void trades() {
        long tWheat = (1L << 32) | 1, tCorn = (1L << 32) | 2;
        mDb.saveTrades(List.of(
                trade(1, "2026-10-15T09:00:00.000", tWheat, 100, "FA", "FB"),
                trade(2, "2026-10-16T09:00:00.000", tWheat, 101, "FB", "FC")));
        mDb.saveTrades(List.of(
                trade(3, "2026-10-16T09:00:00.000", tWheat, 102, "FC", "FA"),   // the same millisecond, stored later
                trade(4, "2026-10-16T10:00:00.000", tCorn,  50,  "FC", "FB")));

        assertEquals(List.of(4L, 3L, 2L), tradeNos(mDb.listTradesByDate("2026-10-16")));   // latest first
        assertEquals(List.of(1L), tradeNos(mDb.listTradesByDate("2026-10-15")));
        assertTrue(mDb.listTradesByDate("2026-10-17").isEmpty());
        assertEquals(List.of(3L, 1L), tradeNos(mDb.listTradesByFirm("FA", null)));
        assertEquals(List.of(3L), tradeNos(mDb.listTradesByFirm("FA", "2026-10-16")));
        assertThrows(IllegalArgumentException.class, () -> mDb.listTradesByDate("16/10/2026"));
        assertThrows(IllegalArgumentException.class, () -> mDb.listTradesByFirm("FA", "2026-13-01"));

        InternalTrade tBack = mDb.listTradesByDate("2026-10-15").get(0);
        InternalTrade tOrig = trade(1, "2026-10-15T09:00:00.000", tWheat, 100, "FA", "FB");
        assertEquals(tOrig.getTradeTime(), tBack.getTradeTime());
        assertEquals(tWheat, tBack.getInstrumentId());
        assertEquals(100 * S, tBack.getPrice());
        assertEquals(5 * S, tBack.getQuantity());
        assertEquals(0xa1, tBack.getBuyOrder().getOrderId());
        assertEquals("FA", tBack.getBuyOrder().getAccount());
        assertEquals("b-ref", tBack.getBuyOrder().getUserRef());
        assertEquals("bc", tBack.getBuyOrder().getClientAccount());
        assertEquals(0xb1, tBack.getSellOrder().getOrderId());
        assertEquals("FB", tBack.getSellOrder().getAccount());
        assertNull(tBack.getSellOrder().getUserRef());
        assertEquals("omni", tBack.getSellOrder().getClientAccount());

        assertEquals(102 * S, mDb.lastTradePrice(tWheat));   // the latest stored of the latest millisecond
        assertEquals(50 * S, mDb.lastTradePrice(tCorn));
        assertEquals(0, mDb.lastTradePrice((1L << 32) | 3));
    }

    @Test
    void tradeUsersAndReversals() {
        long tWheat = (1L << 32) | 1;
        InternalTrade tOriginal = new InternalTrade(0x10, time("2026-10-16T09:00:00.000"), tWheat, 100 * S, 5 * S,
                0xa1, "FA", "b-ref", "bc", "anna",
                0xb1, "FB", null, "omni", "erik", null);
        mDb.saveTrades(List.of(tOriginal));
        assertTrue(mDb.listReversalTrades().isEmpty());

        InternalTrade tBack = mDb.listTradesByDate("2026-10-16").get(0);
        assertEquals("anna", tBack.getBuyOrder().getUser());
        assertEquals("erik", tBack.getSellOrder().getUser());
        assertFalse(tBack.isReversal());

        InternalTrade tReversal = new InternalTrade(0x11, time("2026-10-17T10:00:00.000"), tWheat, 100 * S, 5 * S,
                0xb1, "FB", null, "omni", "erik",
                0xa1, "FA", "b-ref", "bc", "anna", "10");
        mDb.saveTrades(List.of(tReversal, trade(0x12, "2026-10-17T09:00:00.000", tWheat, 120, "FC", "FD")));

        List<InternalTrade> tReversals = mDb.listReversalTrades();
        assertEquals(1, tReversals.size());
        assertEquals(0x11, tReversals.get(0).getTradeNo());
        assertEquals("10", tReversals.get(0).getReversalOf());
        assertEquals("FB", tReversals.get(0).getBuyOrder().getAccount());
        assertEquals("erik", tReversals.get(0).getBuyOrder().getUser());
        // The reversal (stored last, at the latest time) is no market price
        assertEquals(120 * S, mDb.lastTradePrice(tWheat));
        // ...but it counts in the firms' trades (positions)
        assertEquals(List.of(0x11L, 0x10L), tradeNos(mDb.listTradesByFirm("FA", null)));
    }

    private static List<Long> tradeNos(List<InternalTrade> pTrades) {
        return pTrades.stream().map(InternalTrade::getTradeNo).toList();
    }

    @Test
    void bulletinTrades() {
        BulletinTrade t1 = new BulletinTrade("t1", "b1", 3, "Brent Jan", 80 * S, 4 * S, time("2026-10-15T12:00:00.000"),
                "FA", "anna", "r1", "c1", "FB", "erik", null, "omni");
        BulletinTrade t2 = new BulletinTrade("t2", "b1", 3, "Brent Jan", 81 * S, 1 * S, time("2026-10-16T12:00:00.000"),
                "FB", "erik", "o2", "omni", "FC", "lena", "r2", "c9");
        mDb.saveBulletinTrade(t1);
        mDb.saveBulletinTrade(t2);
        assertEquals(List.of(t2, t1), mDb.listBulletinTradesByFirm("FB", null));
        assertEquals(List.of(t1), mDb.listBulletinTradesByFirm("FA", "2026-10-15"));
        assertTrue(mDb.listBulletinTradesByFirm("FA", "2026-10-16").isEmpty());
        assertEquals(81 * S, mDb.lastBulletinTradePrice(3, "Brent Jan"));
        assertEquals(0, mDb.lastBulletinTradePrice(3, "brent jan"));
        assertEquals(0, mDb.lastBulletinTradePrice(4, "Brent Jan"));

        assertEquals(List.of(t2), mDb.listBulletinTradesByDate("2026-10-16"));
        assertThrows(IllegalArgumentException.class, () -> mDb.listBulletinTradesByDate("2026-10-32"));
        assertTrue(mDb.listBulletinReversalTrades().isEmpty());
        // The reversal of t2, stored latest: in the firms' trades, but no market price
        BulletinTrade tReversal = t2.reversal("t3", time("2026-10-17T09:00:00.000"));
        assertEquals("FC", tReversal.buyAccount());
        assertEquals("c9", tReversal.buyClientAccount());
        assertEquals("FB", tReversal.sellAccount());
        mDb.saveBulletinTrade(tReversal);
        assertEquals(List.of(tReversal), mDb.listBulletinReversalTrades());
        assertEquals(List.of(tReversal, t2), mDb.listBulletinTradesByFirm("FC", null));
        assertEquals(81 * S, mDb.lastBulletinTradePrice(3, "Brent Jan"));
        assertFalse(mDb.listBulletinTradesByDate("2026-10-16").get(0).isReversal());
    }

    // ── Chat ─────────────────────────────────────────────────────────────

    @Test
    void chat() {
        ChatMessage m1 = new ChatMessage("c1", time("2026-10-14T10:00:00.000"), "FA", "anna", "hello", "");
        ChatMessage m2 = new ChatMessage("c2", time("2026-10-15T10:00:00.000"), "FB", "erik", "wheat?", "Grain.Desk");
        ChatMessage m3 = new ChatMessage("c3", time("2026-10-15T11:00:00.000"), "FA", "anna", "50%", "50%_off");
        ChatMessage m4 = new ChatMessage("c4", time("2026-10-16T09:00:00.000"), "FC", "lena", "later", "grainXdesk");
        for (ChatMessage m : List.of(m1, m2, m3, m4)) mDb.saveChatMessage(m);

        assertEquals(List.of(m1, m2, m3), mDb.listChatMessages(null, "2026-10-15", null, 10));
        assertEquals(List.of(m2, m3), mDb.listChatMessages(null, "2026-10-15", null, 2));       // the latest, oldest first
        assertEquals(List.of(m2, m3, m4), mDb.listChatMessages("2026-10-15", "2026-10-16", "", 10));
        assertEquals(List.of(m2), mDb.listChatMessages(null, "2026-10-16", "grain.desk", 10));  // case-insensitive, literal
        assertEquals(List.of(m3), mDb.listChatMessages(null, "2026-10-16", "%_", 10));
        assertTrue(mDb.listChatMessages(null, "2026-10-13", null, 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mDb.listChatMessages("x", "2026-10-16", null, 10));
    }

    @Test
    void systemChat() {
        ChatMessage s1 = new ChatMessage("s1", time("2026-10-13T08:00:00.000"), "", "Admin", "maintenance", "", true);
        ChatMessage m1 = new ChatMessage("c1", time("2026-10-14T10:00:00.000"), "FA", "anna", "hello", "");
        ChatMessage s2 = new ChatMessage("s2", time("2026-10-15T07:00:00.000"), "", "Admin", "late open", "XSTO", true);
        ChatMessage s3 = new ChatMessage("s3", time("2026-10-16T07:00:00.000"), "", "Admin", "open", "", true);
        for (ChatMessage m : List.of(s1, m1, s2, s3)) mDb.saveChatMessage(m);

        assertEquals(List.of(s1, s2, s3), mDb.listSystemChatMessages(10));   // of any day, oldest first
        assertEquals(List.of(s2, s3), mDb.listSystemChatMessages(2));        // the latest
        assertEquals(List.of(s1, m1, s2), mDb.listChatMessages(null, "2026-10-15", null, 10));   // in the chat too
        assertFalse(mDb.listChatMessages(null, "2026-10-14", null, 10).get(1).system());
    }
}
