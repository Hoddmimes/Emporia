package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache.InstrumentEntry;
import com.hoddmimes.emporia.server.InstrumentCache.MarketEntry;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.InternalTrade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The server's database: the master store of the reference data (admin, firms, users, markets, bulletin
 * markets, instruments, authorizations) and of the bulletins, trades, bulletin trades and chat messages.
 * Two implementations, chosen by the configuration's database "type" (open): MongoDbDatabase ("mongodb",
 * the default) and SqliteDatabase ("sqlite").
 *
 * Records are addressed by their databaseId (Stored), 24 hex digits; a malformed id throws
 * IllegalArgumentException, which the admin endpoints answer with 400. Prices and quantities are longs
 * scaled by MatchingEngine.SCALE. Trade times are stored as "yyyy-MM-dd HH:mm:ss.SSS" in server local time
 * (formatTradeTime), so a day is a string range. The trades, bulletin trades and chat messages are written
 * durably (on disk before the call returns), since they are published only afterwards.
 */
public interface Database {

    /** Format of the stored trade and chat times (server local time). */
    DateTimeFormatter TRADE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * Opens the database given by the configuration's "database" object: "type" "mongodb" (the default;
     * host, port, name) or "sqlite" (file, the database file, created if missing).
     */
    static Database open(JsonObject pConfig) {
        String tType = pConfig.has("type") ? pConfig.get("type").getAsString().trim().toLowerCase() : "mongodb";
        return switch (tType) {
            case "mongodb" -> new MongoDbDatabase(pConfig);
            case "sqlite"  -> new SqliteDatabase(pConfig);
            default -> throw new IllegalArgumentException("Unknown database type '" + tType + "', expected mongodb or sqlite");
        };
    }

    /** Closes the connection (server stop, web application undeploy). */
    void close();

    // ── Admin ────────────────────────────────────────────────────────────

    /** Creates the default admin Admin/changeit if there is no admin. */
    void initializeAdmin();

    /** True if the admin (user name case-insensitive) exists and has the password. */
    boolean verifyAdminLogin(String pUsername, String pPassword);

    /** Changes the admin's password; false if there is no such admin or the current password is wrong. */
    boolean changeAdminPassword(String pUsername, String pCurrentPassword, String pNewPassword);

    void updateLastSeen(String pUsername);

    // ── Firms ────────────────────────────────────────────────────────────

    List<Stored<Firm>> listFirms();

    /** The firm, or null. */
    Stored<Firm> getFirm(String pId);

    /** The firm with the name, or null. */
    Firm getFirmByName(String pName);

    /** Returns the new firm's databaseId. */
    String createFirm(String pName, String pStatus, String pAddress, String pPhone, String pContact, String pMail);

    /** The name is not changed. */
    void updateFirm(String pId, String pStatus, String pAddress, String pPhone, String pContact, String pMail);

    /** Deletes the firm and its authorizations; returns the firm's name, or null if there was no such firm. */
    String deleteFirm(String pId);

    // ── Users ────────────────────────────────────────────────────────────

    List<Stored<User>> listUsers();

    Stored<User> getUser(String pId);

    /** The user with the userId, or null. */
    User getUserByUserId(String pUserId);

    /** The password is stored as its SHA-512 hex ("" without a password); a null status is INACTIVE. */
    String createUser(String pUserId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword);

    /** The userId is not changed, nor the password if pPassword is null or empty. */
    void updateUser(String pId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword);

    void deleteUser(String pId);

    // ── Markets (CONTINUOUS and AUCTION) ─────────────────────────────────

    List<Stored<MarketEntry>> listMarkets();

    Stored<MarketEntry> getMarket(String pId);

    String createMarket(MarketEntry pMarket);

    /** The marketId is not changed, nor the name if it is empty. */
    void updateMarket(String pId, MarketEntry pMarket);

    /** Sets a market's tradingStatus; returns the updated market, or null if there is no such market. */
    MarketEntry setMarketStatus(int pMarketId, String pStatus);

    void deleteMarket(String pId);

    // ── Bulletin markets ─────────────────────────────────────────────────

    List<Stored<BulletinMarket>> listBulletinMarkets();

    Stored<BulletinMarket> getBulletinMarket(String pId);

    String createBulletinMarket(BulletinMarket pMarket);

    /** The marketId and the name are not changed. */
    void updateBulletinMarket(String pId, BulletinMarket pMarket);

    /** Sets a bulletin market's tradingStatus; returns the updated market, or null if there is no such market. */
    BulletinMarket setBulletinMarketStatus(int pMarketId, String pStatus);

    void deleteBulletinMarket(String pId);

    // ── Bulletins ────────────────────────────────────────────────────────

    /** Inserts or replaces the bulletin with the bulletinId. */
    void saveBulletin(StoredBulletin pBulletin);

    void deleteBulletin(String pBulletinId);

    /** All stored bulletins, oldest (createTime) first. */
    List<StoredBulletin> listBulletins();

    // ── Instruments ──────────────────────────────────────────────────────

    List<Stored<InstrumentEntry>> listInstrumentsByMarket(int pMarketId);

    Stored<InstrumentEntry> getInstrument(String pId);

    String createInstrument(InstrumentEntry pInstrument);

    /** The instrumentId and tradingStatus are not changed, nor the name if it is empty. */
    void updateInstrument(String pId, InstrumentEntry pInstrument);

    void updateInstrumentStatus(String pId, String pStatus);

    void deleteInstrument(String pId);

    /** Deletes the instrument with the instrumentId; false if there was none. */
    boolean deleteInstrumentByInstrumentId(long pInstrumentId);

    // ── Authorizations ───────────────────────────────────────────────────

    List<Stored<Authorization>> listAuthorizationsByFirm(String pFirm);

    List<Authorization> listAuthorizations();

    boolean authorizationExists(String pFirm, String pMarket);

    String createAuthorization(String pFirm, String pMarket);

    void deleteAuthorization(String pId);

    // ── Trades ───────────────────────────────────────────────────────────

    /**
     * Stores the trades durably with one write (one fsync for all of them): used by the matching engine's
     * TradePersister, which batches the trades of many orders into one call. Besides the trade, each side's
     * order id, firm (account), userRef, client account and user (trader), and reversalOf for a reversal.
     */
    void saveTrades(List<InternalTrade> pTrades);

    /**
     * The trades of a "yyyy-MM-dd" day, latest first (trades of the same millisecond latest stored first).
     * The buy and sell orders are stand-ins (see InternalTrade).
     *
     * @throws IllegalArgumentException if the date is not a valid "yyyy-MM-dd" date
     */
    List<InternalTrade> listTradesByDate(String pDate);

    /**
     * The trades where the firm is the buyer or the seller, of a "yyyy-MM-dd" day or, if pDate is null,
     * of all days; latest first.
     *
     * @throws IllegalArgumentException if the date is not null and not a valid "yyyy-MM-dd" date
     */
    List<InternalTrade> listTradesByFirm(String pFirm, String pDate);

    /**
     * The price of the instrument's latest stored trade, of any firm and day, reversal trades not counted
     * (they are no market prices); 0 if it never traded.
     */
    long lastTradePrice(long pInstrumentId);

    /**
     * All stored reversal trades (made by the market supervision, InternalTrade.getReversalOf not null), of
     * all days, latest first: a trade is reversed if one of them has its tradeNo and instrument.
     */
    List<InternalTrade> listReversalTrades();

    // ── Bulletin trades ──────────────────────────────────────────────────

    /** Stores the bulletin trade durably (it is published only afterwards). */
    void saveBulletinTrade(BulletinTrade pTrade);

    /** As listTradesByFirm, for the bulletin trades. */
    List<BulletinTrade> listBulletinTradesByFirm(String pFirm, String pDate);

    /** The price of the latest bulletin trade in the (free text) instrument of the bulletin market; 0 if none. */
    long lastBulletinTradePrice(int pMarketId, String pInstrument);

    // ── Chat ─────────────────────────────────────────────────────────────

    /** Stores the chat message durably (it is broadcast only afterwards). */
    void saveChatMessage(ChatMessage pMessage);

    /**
     * The chat messages up to and including pToDate (yyyy-MM-dd), and from pFromDate (null: no lower bound),
     * whose tag contains pTag (case-insensitive; null or empty: all). At most pLimit, the latest ones, returned
     * oldest first.
     *
     * @throws IllegalArgumentException for an invalid date
     */
    List<ChatMessage> listChatMessages(String pFromDate, String pToDate, String pTag, int pLimit);

    // ── Login ────────────────────────────────────────────────────────────

    /** A trading user's login: {"success":true,"userId","firm"} or {"success":false,"message"}. */
    default JsonObject validateUserLogin(String pFirm, String pUserId, String pPassword) {
        Firm tFirm = getFirmByName(pFirm);
        if (tFirm == null) return loginResult(false, "Firm not found");
        if (!"ACTIVE".equals(tFirm.status())) return loginResult(false, "Firm is suspended");
        User tUser = getUserByUserId(pUserId);
        if (tUser == null || !pFirm.equals(tUser.firm())) return loginResult(false, "Invalid credentials");
        if (tUser.password().isEmpty() || !hashSha512(pPassword).equals(tUser.password())) {
            return loginResult(false, "Invalid credentials");
        }
        if (!"ACTIVE".equals(tUser.status())) return loginResult(false, "User account is suspended");
        JsonObject tResult = new JsonObject();
        tResult.addProperty("success", true);
        tResult.addProperty("userId",  pUserId);
        tResult.addProperty("firm",    pFirm);
        return tResult;
    }

    private static JsonObject loginResult(boolean pSuccess, String pMessage) {
        JsonObject tObj = new JsonObject();
        tObj.addProperty("success", pSuccess);
        tObj.addProperty("message", pMessage);
        return tObj;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** An epoch millis time as stored: "yyyy-MM-dd HH:mm:ss.SSS", server local time. */
    static String formatTradeTime(long pEpochMillis) {
        return TRADE_TIME_FORMAT.format(Instant.ofEpochMilli(pEpochMillis));
    }

    /** A stored time back to epoch millis. */
    static long parseTradeTime(String pTradeTime) {
        return ZonedDateTime.parse(pTradeTime, TRADE_TIME_FORMAT).toInstant().toEpochMilli();
    }

    /** The first and last stored time of a "yyyy-MM-dd" day; IllegalArgumentException for an invalid date. */
    static String[] dayRange(String pDate) {
        try {
            LocalDate.parse(pDate);   // ISO format, i.e. yyyy-MM-dd
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date '" + pDate + "', expected yyyy-MM-dd");
        }
        return new String[] { pDate + " 00:00:00.000", pDate + " 23:59:59.999" };
    }

    static String hashSha512(String pInput) {
        try {
            byte[] tHash = MessageDigest.getInstance("SHA-512").digest(pInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder tHex = new StringBuilder();
            for (byte b : tHash) tHex.append(String.format("%02x", b));
            return tHex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 not available", e);
        }
    }

    static String orEmpty(String pValue) {
        return (pValue != null) ? pValue : "";
    }
}
