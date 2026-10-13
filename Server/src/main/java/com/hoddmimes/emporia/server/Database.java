package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.hoddmimes.emporia.server.me.InternalTrade;
import com.hoddmimes.emporia.server.me.Order;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Database {

    private static final Logger cLog = LogManager.getLogger(Database.class);

    /** Format of the trade time stored in Trades (server local time). */
    private static final DateTimeFormatter TRADE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private MongoClient mMongoClient;
    private MongoDatabase mDatabase;

    public void connect(JsonObject tDbConfig) {
        String tHost = tDbConfig.get("host").getAsString();
        int    tPort = tDbConfig.get("port").getAsInt();
        String tName = tDbConfig.get("name").getAsString();

        String tUri = "mongodb://" + tHost + ":" + tPort;
        cLog.info("Connecting to MongoDB at {}, database: {}", tUri, tName);
        mMongoClient = MongoClients.create(tUri);
        mDatabase = mMongoClient.getDatabase(tName);
        mDatabase.runCommand(new Document("ping", 1));
        cLog.info("MongoDB connection established, database: {}", tName);
    }

    /** Closes the MongoDB connection (web application undeploy). */
    public void close() {
        if (mMongoClient != null) mMongoClient.close();
    }

    // ── Admin ────────────────────────────────────────────────────────────

    public void initializeAdminCollection() {
        Document tExisting = mDatabase.getCollection("Admin").find().first();
        if (tExisting != null) {
            cLog.info("Admin document already exists, credentials unchanged");
            return;
        }

        cLog.info("No admin document found, creating with default credentials");
        String tNow = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Document tAdminDoc = new Document("username", "Admin")
                .append("password", hashSha512("changeit"))
                .append("last-seen", tNow);
        mDatabase.getCollection("Admin").insertOne(tAdminDoc);
        cLog.info("Admin document created with default credentials");
    }

    public boolean verifyAdminLogin(String pUsername, String pPassword) {
        String tInputUpper = pUsername.toUpperCase();
        for (Document tDoc : mDatabase.getCollection("Admin").find()) {
            String tStoredUpper = tDoc.getString("username") != null
                    ? tDoc.getString("username").toUpperCase() : "";
            if (tInputUpper.equals(tStoredUpper)) {
                return hashSha512(pPassword).equals(tDoc.getString("password"));
            }
        }
        return false;
    }

    public boolean changeAdminPassword(String pUsername, String pCurrentPassword, String pNewPassword) {
        String tInputUpper = pUsername.toUpperCase();
        for (Document tDoc : mDatabase.getCollection("Admin").find()) {
            String tStoredUpper = tDoc.getString("username") != null
                    ? tDoc.getString("username").toUpperCase() : "";
            if (tInputUpper.equals(tStoredUpper)) {
                if (!hashSha512(pCurrentPassword).equals(tDoc.getString("password"))) {
                    return false;
                }
                mDatabase.getCollection("Admin").updateOne(
                        new Document("_id", tDoc.getObjectId("_id")),
                        new Document("$set", new Document("password", hashSha512(pNewPassword))));
                return true;
            }
        }
        return false;
    }

    public void updateLastSeen(String pUsername) {
        String tNow = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String tInputUpper = pUsername.toUpperCase();
        for (Document tDoc : mDatabase.getCollection("Admin").find()) {
            String tStoredUpper = tDoc.getString("username") != null
                    ? tDoc.getString("username").toUpperCase() : "";
            if (tInputUpper.equals(tStoredUpper)) {
                mDatabase.getCollection("Admin").updateOne(
                        new Document("_id", tDoc.getObjectId("_id")),
                        new Document("$set", new Document("last-seen", tNow)));
                break;
            }
        }
    }

    // ── Firm CRUD ────────────────────────────────────────────────────────

    public List<Document> listFirms() {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Firm").find()) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public Document getFirm(String pId) {
        return mDatabase.getCollection("Firm")
                .find(new Document("_id", new ObjectId(pId)))
                .first();
    }

    public String createFirm(String pName, String pStatus,
                              String pAddress, String pPhone,
                              String pContact, String pMail) {
        Document tDoc = new Document("name", pName)
                .append("status", pStatus)
                .append("address", pAddress != null ? pAddress : "")
                .append("phone",   pPhone   != null ? pPhone   : "")
                .append("contact", pContact != null ? pContact : "")
                .append("mail",    pMail    != null ? pMail    : "");
        mDatabase.getCollection("Firm").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    public void updateFirm(String pId, String pStatus, String pAddress,
                            String pPhone, String pContact, String pMail) {
        Document tFields = new Document("status",  pStatus)
                .append("address", pAddress != null ? pAddress : "")
                .append("phone",   pPhone   != null ? pPhone   : "")
                .append("contact", pContact != null ? pContact : "")
                .append("mail",    pMail    != null ? pMail    : "");
        mDatabase.getCollection("Firm").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", tFields));
    }

    public void deleteFirm(String pId) {
        mDatabase.getCollection("Firm").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── User CRUD ────────────────────────────────────────────────────────

    public List<Document> listUsers() {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("User").find()) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public Document getUser(String pId) {
        return mDatabase.getCollection("User")
                .find(new Document("_id", new ObjectId(pId)))
                .first();
    }

    public String createUser(String pUserId, String pFirm, String pName,
                              String pMail, String pPhone, String pStatus, String pPassword) {
        Document tDoc = new Document("userId", pUserId != null ? pUserId : "")
                .append("firm",     pFirm     != null ? pFirm     : "")
                .append("name",     pName     != null ? pName     : "")
                .append("mail",     pMail     != null ? pMail     : "")
                .append("phone",    pPhone    != null ? pPhone    : "")
                .append("status",   pStatus   != null ? pStatus   : "INACTIVE")
                .append("password", pPassword != null && !pPassword.isEmpty()
                        ? hashSha512(pPassword) : "");
        mDatabase.getCollection("User").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    public void updateUser(String pId, String pFirm, String pName,
                            String pMail, String pPhone, String pStatus, String pPassword) {
        Document tFields = new Document("firm",   pFirm   != null ? pFirm   : "")
                .append("name",   pName   != null ? pName   : "")
                .append("mail",   pMail   != null ? pMail   : "")
                .append("phone",  pPhone  != null ? pPhone  : "")
                .append("status", pStatus != null ? pStatus : "INACTIVE");
        if (pPassword != null && !pPassword.isEmpty()) {
            tFields.append("password", hashSha512(pPassword));
        }
        mDatabase.getCollection("User").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", tFields));
    }

    public void deleteUser(String pId) {
        mDatabase.getCollection("User").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── Market CRUD ──────────────────────────────────────────────────────

    public List<Document> listMarkets() {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Markets").find()) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public Document getMarket(String pId) {
        return mDatabase.getCollection("Markets")
                .find(new Document("_id", new ObjectId(pId)))
                .first();
    }

    public String createMarket(int pMarketId, String pName, String pDescription, String pType,
                                String pOpen, String pClose, Boolean pInverse,
                                String pPreOpenTime, String pOpenAuctionTime,
                                String pAuctionInterval, String pRandomAuctionInterval,
                                String pTradingStatus) {
        Document tDoc = new Document("marketId",              pMarketId)
                .append("name",                 pName        != null ? pName        : "")
                .append("description",          pDescription != null ? pDescription : "")
                .append("type",                 pType        != null ? pType        : "")
                .append("open",                 pOpen        != null ? pOpen        : "")
                .append("close",                pClose       != null ? pClose       : "")
                .append("inverse",              pInverse     != null ? pInverse     : false)
                .append("preOpenTime",          pPreOpenTime         != null ? pPreOpenTime         : "")
                .append("openAuctionTime",      pOpenAuctionTime     != null ? pOpenAuctionTime     : "")
                .append("auctionInterval",      pAuctionInterval     != null ? pAuctionInterval     : "")
                .append("randomAuctionInterval",pRandomAuctionInterval != null ? pRandomAuctionInterval : "")
                .append("tradingStatus",        pTradingStatus       != null ? pTradingStatus       : "CLOSED");
        mDatabase.getCollection("Markets").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    public void updateMarket(String pId, String pName, String pDescription, String pType,
                              String pOpen, String pClose, Boolean pInverse,
                              String pPreOpenTime, String pOpenAuctionTime,
                              String pAuctionInterval, String pRandomAuctionInterval,
                              String pTradingStatus) {
        Document tFields = new Document("description",          pDescription != null ? pDescription : "")
                .append("type",                 pType        != null ? pType        : "")
                .append("open",                 pOpen        != null ? pOpen        : "")
                .append("close",                pClose       != null ? pClose       : "")
                .append("inverse",              pInverse     != null ? pInverse     : false)
                .append("preOpenTime",          pPreOpenTime         != null ? pPreOpenTime         : "")
                .append("openAuctionTime",      pOpenAuctionTime     != null ? pOpenAuctionTime     : "")
                .append("auctionInterval",      pAuctionInterval     != null ? pAuctionInterval     : "")
                .append("randomAuctionInterval",pRandomAuctionInterval != null ? pRandomAuctionInterval : "")
                .append("tradingStatus",        pTradingStatus       != null ? pTradingStatus       : "CLOSED");
        // The admin edit page does not send the name; an empty name keeps the stored one
        if (pName != null && !pName.isEmpty()) tFields.append("name", pName);
        mDatabase.getCollection("Markets").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", tFields));
    }

    /** Sets a market's tradingStatus and returns the updated document (null if no such market). */
    public Document setMarketStatus(int pMarketId, String pStatus) {
        return mDatabase.getCollection("Markets").findOneAndUpdate(
                new Document("marketId", pMarketId),
                new Document("$set", new Document("tradingStatus", pStatus)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public void deleteMarket(String pId) {
        mDatabase.getCollection("Markets").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── Bulletin market CRUD ─────────────────────────────────────────────

    public List<Document> listBulletinMarkets() {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("BulletinMarkets").find()) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public Document getBulletinMarket(String pId) {
        return mDatabase.getCollection("BulletinMarkets")
                .find(new Document("_id", new ObjectId(pId)))
                .first();
    }

    /** maxOrderTime is "hh:mm". */
    public String createBulletinMarket(int pMarketId, String pName, String pDescription, String pTradingStatus,
                                       String pOpen, String pClose, List<String> pInstruments, String pMaxOrderTime) {
        Document tDoc = new Document("marketId", pMarketId)
                .append("name",          pName)
                .append("description",   pDescription)
                .append("tradingStatus", pTradingStatus)
                .append("open",          pOpen)
                .append("close",         pClose)
                .append("instruments",   pInstruments)
                .append("maxOrderTime",  pMaxOrderTime);
        mDatabase.getCollection("BulletinMarkets").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    /** The name is not changed. */
    public void updateBulletinMarket(String pId, String pDescription, String pTradingStatus,
                                     String pOpen, String pClose, List<String> pInstruments, String pMaxOrderTime) {
        mDatabase.getCollection("BulletinMarkets").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", new Document("description", pDescription)
                        .append("tradingStatus", pTradingStatus)
                        .append("open",          pOpen)
                        .append("close",         pClose)
                        .append("instruments",   pInstruments)
                        .append("maxOrderTime",  pMaxOrderTime)));
    }

    /** Sets a bulletin market's tradingStatus and returns the updated document (null if no such market). */
    public Document setBulletinMarketStatus(int pMarketId, String pStatus) {
        return mDatabase.getCollection("BulletinMarkets").findOneAndUpdate(
                new Document("marketId", pMarketId),
                new Document("$set", new Document("tradingStatus", pStatus)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public void deleteBulletinMarket(String pId) {
        mDatabase.getCollection("BulletinMarkets").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── Bulletins ────────────────────────────────────────────────────────

    /** Inserts or replaces the bulletin with the document's bulletinId (see BulletinBoard). */
    public void saveBulletin(Document pDoc) {
        mDatabase.getCollection("Bulletins").replaceOne(
                new Document("bulletinId", pDoc.getString("bulletinId")), pDoc,
                new ReplaceOptions().upsert(true));
    }

    public void deleteBulletin(String pBulletinId) {
        mDatabase.getCollection("Bulletins").deleteOne(new Document("bulletinId", pBulletinId));
    }

    /** All stored bulletins, oldest first. */
    public List<Document> listBulletins() {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Bulletins").find().sort(new Document("createTime", 1))) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    // ── Instrument CRUD ──────────────────────────────────────────────────

    public List<Document> listInstrumentsByMarket(int pMarketId) {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Instruments")
                .find(new Document("marketId", pMarketId))) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public Document getInstrument(String pId) {
        return mDatabase.getCollection("Instruments")
                .find(new Document("_id", new ObjectId(pId)))
                .first();
    }

    public boolean deleteInstrumentByBusinessId(long pInstrumentId) {
        return mDatabase.getCollection("Instruments")
                .deleteOne(new Document("instrumentId", String.valueOf(pInstrumentId)))
                .getDeletedCount() > 0;
    }

    public String createInstrument(long pInstrumentId, int pMarketId, String pName,
                                    String pInstType, long pTickSize, long pLotSize, String pIsin,
                                    String pTradingStatus, String pExpirationDate, long pStrikePrice,
                                    String pUnderlying, String pOptionType) {
        Document tDoc = new Document("instrumentId",  String.valueOf(pInstrumentId))
                .append("marketId",      pMarketId)
                .append("name",          pName          != null ? pName          : "")
                .append("instType",      pInstType      != null ? pInstType      : "")
                .append("tickSize",      pTickSize)
                .append("lotSize",       pLotSize)
                .append("tradingStatus", pTradingStatus != null ? pTradingStatus : "ACTIVE")
                .append("isin",          pIsin          != null ? pIsin          : "")
                .append("expirationDate",pExpirationDate != null ? pExpirationDate : "")
                .append("strikePrice",   pStrikePrice)
                .append("underlying",    pUnderlying    != null ? pUnderlying    : "")
                .append("optionType",    pOptionType    != null ? pOptionType    : "");
        mDatabase.getCollection("Instruments").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    public void updateInstrument(String pId, int pMarketId, String pName, String pInstType,
                                  long pTickSize, long pLotSize, String pIsin,
                                  String pExpirationDate, long pStrikePrice,
                                  String pUnderlying, String pOptionType) {
        Document tFields = new Document("marketId",       pMarketId)
                .append("instType",      pInstType      != null ? pInstType      : "")
                .append("tickSize",      pTickSize)
                .append("lotSize",       pLotSize)
                .append("isin",          pIsin          != null ? pIsin          : "")
                .append("expirationDate",pExpirationDate != null ? pExpirationDate : "")
                .append("strikePrice",   pStrikePrice)
                .append("underlying",    pUnderlying    != null ? pUnderlying    : "")
                .append("optionType",    pOptionType    != null ? pOptionType    : "");
        // The admin edit page does not send the name; an empty name keeps the stored one
        if (pName != null && !pName.isEmpty()) tFields.append("name", pName);
        mDatabase.getCollection("Instruments").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", tFields));
    }

    public void updateInstrumentStatus(String pId, String pStatus) {
        mDatabase.getCollection("Instruments").updateOne(
                new Document("_id", new ObjectId(pId)),
                new Document("$set", new Document("tradingStatus", pStatus)));
    }

    public void deleteInstrument(String pId) {
        mDatabase.getCollection("Instruments").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── Authorization CRUD ───────────────────────────────────────────────

    public List<Document> listAuthorizationsByFirm(String pFirm) {
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Authorization")
                .find(new Document("firm", pFirm))) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    public boolean authorizationExists(String pFirm, String pMarket) {
        return mDatabase.getCollection("Authorization")
                .find(new Document("firm", pFirm).append("market", pMarket))
                .first() != null;
    }

    public String createAuthorization(String pFirm, String pMarket) {
        Document tDoc = new Document("firm",   pFirm   != null ? pFirm   : "")
                .append("market", pMarket != null ? pMarket : "");
        mDatabase.getCollection("Authorization").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    public void deleteAuthorization(String pId) {
        mDatabase.getCollection("Authorization").deleteOne(
                new Document("_id", new ObjectId(pId)));
    }

    // ── User login validation ─────────────────────────────────────────────

    public JsonObject validateUserLogin(String pFirm, String pUserId, String pPassword) {
        // Validate firm exists and is active
        Document tFirmDoc = getFirmByName(pFirm);
        if (tFirmDoc == null) {
            return loginResult(false, "Firm not found");
        }
        if (!"ACTIVE".equals(tFirmDoc.getString("status"))) {
            return loginResult(false, "Firm is suspended");
        }
        // Validate user exists and belongs to specified firm
        Document tUser = getUserByUserId(pUserId);
        if (tUser == null || !pFirm.equals(tUser.getString("firm"))) {
            return loginResult(false, "Invalid credentials");
        }
        // Validate password
        String tStoredHash = tUser.getString("password");
        if (tStoredHash == null || !hashSha512(pPassword).equals(tStoredHash)) {
            return loginResult(false, "Invalid credentials");
        }
        // Validate user is active
        if (!"ACTIVE".equals(tUser.getString("status"))) {
            return loginResult(false, "User account is suspended");
        }
        JsonObject tResult = new JsonObject();
        tResult.addProperty("success", true);
        tResult.addProperty("userId",  pUserId);
        tResult.addProperty("firm",    pFirm);
        return tResult;
    }

    private Document getUserByUserId(String pUserId) {
        for (Document tDoc : mDatabase.getCollection("User").find()) {
            if (pUserId.equals(tDoc.getString("userId"))) {
                return tDoc;
            }
        }
        return null;
    }

    private Document getFirmByName(String pFirmName) {
        return mDatabase.getCollection("Firm")
                .find(new Document("name", pFirmName))
                .first();
    }

    private JsonObject loginResult(boolean pSuccess, String pMessage) {
        JsonObject tObj = new JsonObject();
        tObj.addProperty("success", pSuccess);
        tObj.addProperty("message", pMessage);
        return tObj;
    }

    // ── Trades ───────────────────────────────────────────────────────────

    /**
     * Stores a trade in the Trades collection and returns its MongoDB id. Ids are stored as in the
     * REST API (trade and order ids as hex strings, the instrumentId as a string like in Instruments),
     * prices and quantities as longs scaled by MatchingEngine.SCALE, and the trade time as the string
     * "yyyy-MM-dd HH:mm:ss.SSS" (server local time).
     */
    public String saveTrade(InternalTrade pTrade) {
        Document tDoc = toTradeDocument(pTrade);
        mDatabase.getCollection("Trades").insertOne(tDoc);
        return tDoc.getObjectId("_id").toHexString();
    }

    /** The Trades document for a trade, as stored by saveTrade (without the MongoDB _id). */
    static Document toTradeDocument(InternalTrade pTrade) {
        Order tBuy  = pTrade.getBuyOrder();
        Order tSell = pTrade.getSellOrder();
        return new Document("tradeNo",       Long.toHexString(pTrade.getTradeNo()))
                .append("marketId",      (int) (pTrade.getInstrumentId() >>> 32))
                .append("instrumentId",  String.valueOf(pTrade.getInstrumentId()))
                .append("price",         pTrade.getPrice())
                .append("quantity",      pTrade.getQuantity())
                .append("tradeTime",     TRADE_TIME_FORMAT.format(Instant.ofEpochMilli(pTrade.getTradeTime())))
                .append("buyOrderId",    Long.toHexString(tBuy.getOrderId()))
                .append("buyAccount",    tBuy.getAccount())
                .append("buyUserRef",    tBuy.getUserRef())
                .append("sellOrderId",   Long.toHexString(tSell.getOrderId()))
                .append("sellAccount",   tSell.getAccount())
                .append("sellUserRef",   tSell.getUserRef());
    }

    /**
     * Converts a Trades document (as stored by saveTrade) back to an InternalTrade, with its stored
     * trade number and time. The document holds only the order ids, accounts and user refs of the
     * two orders, so the trade's buy and sell orders are stand-ins (see InternalTrade).
     */
    public static InternalTrade fromTradeDocument(Document pDoc) {
        return new InternalTrade(
                Long.parseUnsignedLong(pDoc.getString("tradeNo"), 16),
                ZonedDateTime.parse(pDoc.getString("tradeTime"), TRADE_TIME_FORMAT).toInstant().toEpochMilli(),
                Long.parseLong(pDoc.getString("instrumentId")),
                pDoc.getLong("price"),
                pDoc.getLong("quantity"),
                Long.parseUnsignedLong(pDoc.getString("buyOrderId"), 16),
                pDoc.getString("buyAccount"),
                pDoc.getString("buyUserRef"),
                Long.parseUnsignedLong(pDoc.getString("sellOrderId"), 16),
                pDoc.getString("sellAccount"),
                pDoc.getString("sellUserRef"));
    }

    /**
     * The trades stored for a date given as "yyyy-MM-dd", latest first. The trade time is
     * stored as "yyyy-MM-dd HH:mm:ss.SSS", so the day is a string range on it.
     *
     * @throws IllegalArgumentException if the date is not a valid "yyyy-MM-dd" date
     */
    public List<Document> listTradesByDate(String pDate) {
        return findTrades(new Document("tradeTime", tradeDay(pDate)));
    }

    /**
     * The trades stored where the firm is the buyer or the seller, for a date given as "yyyy-MM-dd"
     * or, if the date is null, for all dates; latest first.
     *
     * @throws IllegalArgumentException if the date is not null and not a valid "yyyy-MM-dd" date
     */
    public List<Document> listTradesByFirm(String pFirm, String pDate) {
        Document tFilter = new Document("$or", List.of(new Document("buyAccount", pFirm), new Document("sellAccount", pFirm)));
        if (pDate != null) tFilter.append("tradeTime", tradeDay(pDate));
        return findTrades(tFilter);
    }

    /**
     * Deletes the stored trades with the trade number (InternalTrade.getTradeNo(), stored as hex) and
     * returns how many were deleted. Trade numbers restart at 1 when the server restarts, so trades
     * from different server runs can share a number; all of them are deleted.
     */
    public long deleteTradeByTradeNo(long pTradeNo) {
        return mDatabase.getCollection("Trades")
                .deleteMany(new Document("tradeNo", Long.toHexString(pTradeNo)))
                .getDeletedCount();
    }

    /** The price (scaled) of the instrument's latest stored trade, of any firm and day; 0 if it never traded. */
    public long lastTradePrice(long pInstrumentId) {
        Document tDoc = mDatabase.getCollection("Trades")
                .find(new Document("instrumentId", String.valueOf(pInstrumentId)))
                .sort(new Document("tradeTime", -1).append("_id", -1))
                .first();
        return (tDoc != null) ? tDoc.getLong("price") : 0L;
    }

    private List<Document> findTrades(Document pFilter) {
        // Latest first; _id (increasing on insert) orders trades stored within the same millisecond
        List<Document> tResult = new ArrayList<>();
        for (Document tDoc : mDatabase.getCollection("Trades").find(pFilter).sort(new Document("tradeTime", -1).append("_id", -1))) {
            tResult.add(tDoc);
        }
        return tResult;
    }

    /** A tradeTime condition matching the whole day of a "yyyy-MM-dd" date. */
    private static Document tradeDay(String pDate) {
        try {
            LocalDate.parse(pDate);   // ISO format, i.e. yyyy-MM-dd
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date '" + pDate + "', expected yyyy-MM-dd");
        }
        return new Document("$gte", pDate + " 00:00:00.000").append("$lte", pDate + " 23:59:59.999");
    }

    // ── Utilities ────────────────────────────────────────────────────────

    public MongoDatabase getDatabase() {
        return mDatabase;
    }

    private String hashSha512(String pInput) {
        try {
            MessageDigest tDigest = MessageDigest.getInstance("SHA-512");
            byte[] tHash = tDigest.digest(pInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder tHex = new StringBuilder();
            for (byte b : tHash) {
                tHex.append(String.format("%02x", b));
            }
            return tHex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 not available", e);
        }
    }

    // ── JSON conversion ──────────────────────────────────────────────────

    public JsonObject firmToJson(Document pDoc) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("id",      pDoc.getObjectId("_id").toHexString());
        tJson.addProperty("name",    str(pDoc, "name"));
        tJson.addProperty("status",  str(pDoc, "status"));
        tJson.addProperty("address", str(pDoc, "address"));
        tJson.addProperty("phone",   str(pDoc, "phone"));
        tJson.addProperty("contact", str(pDoc, "contact"));
        tJson.addProperty("mail",    str(pDoc, "mail"));
        return tJson;
    }

    public JsonObject userToJson(Document pDoc) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("mongoId",   pDoc.getObjectId("_id").toHexString());
        tJson.addProperty("id",        str(pDoc, "userId"));
        tJson.addProperty("firm",      str(pDoc, "firm"));
        tJson.addProperty("password",  str(pDoc, "password"));
        tJson.addProperty("name",   str(pDoc, "name"));
        tJson.addProperty("mail",   str(pDoc, "mail"));
        tJson.addProperty("phone",  str(pDoc, "phone"));
        tJson.addProperty("status", str(pDoc, "status"));
        return tJson;
    }

    public JsonObject marketToJson(Document pDoc) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("mongoId",              pDoc.getObjectId("_id").toHexString());
        Integer tMarketId = pDoc.getInteger("marketId");
        tJson.addProperty("id",                   tMarketId != null ? tMarketId : 0);
        tJson.addProperty("name",                 str(pDoc, "name"));
        tJson.addProperty("description",          str(pDoc, "description"));
        tJson.addProperty("type",                 str(pDoc, "type"));
        tJson.addProperty("open",                 str(pDoc, "open"));
        tJson.addProperty("close",                str(pDoc, "close"));
        Boolean tInverse = pDoc.getBoolean("inverse");
        tJson.addProperty("inverse",              tInverse != null ? tInverse : false);
        tJson.addProperty("preOpenTime",          str(pDoc, "preOpenTime"));
        tJson.addProperty("openAuctionTime",      str(pDoc, "openAuctionTime"));
        tJson.addProperty("auctionInterval",      str(pDoc, "auctionInterval"));
        tJson.addProperty("randomAuctionInterval",str(pDoc, "randomAuctionInterval"));
        tJson.addProperty("tradingStatus",        str(pDoc, "tradingStatus"));
        return tJson;
    }

    public JsonObject instrumentToJson(Document pDoc) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("mongoId",       pDoc.getObjectId("_id").toHexString());
        String tInstrIdStr = str(pDoc, "instrumentId");
        long tInstrIdLong = 0L;
        try { if (!tInstrIdStr.isEmpty()) tInstrIdLong = Long.parseLong(tInstrIdStr); } catch (NumberFormatException ignored) {}
        tJson.addProperty("id",            tInstrIdLong);
        Integer tInstrMarketId = pDoc.getInteger("marketId");
        tJson.addProperty("marketId",      tInstrMarketId != null ? tInstrMarketId : 0);
        tJson.addProperty("name",          str(pDoc, "name"));
        tJson.addProperty("instType",      str(pDoc, "instType"));
        Long tTickSize = pDoc.getLong("tickSize");
        tJson.addProperty("tickSize",      tTickSize  != null ? tTickSize  : 0L);
        Long tLotSize  = pDoc.getLong("lotSize");
        tJson.addProperty("lotSize",       tLotSize   != null ? tLotSize   : 0L);
        tJson.addProperty("tradingStatus", str(pDoc, "tradingStatus"));
        tJson.addProperty("isin",          str(pDoc, "isin"));
        tJson.addProperty("expirationDate",str(pDoc, "expirationDate"));
        Long tStrike   = pDoc.getLong("strikePrice");
        tJson.addProperty("strikePrice",   tStrike    != null ? tStrike    : 0L);
        tJson.addProperty("underlying",    str(pDoc, "underlying"));
        tJson.addProperty("optionType",    str(pDoc, "optionType"));
        return tJson;
    }

    public JsonObject authorizationToJson(Document pDoc) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("mongoId", pDoc.getObjectId("_id").toHexString());
        tJson.addProperty("firm",    str(pDoc, "firm"));
        tJson.addProperty("market",  str(pDoc, "market"));
        return tJson;
    }

    private String str(Document pDoc, String pField) {
        String tVal = pDoc.getString(pField);
        return tVal != null ? tVal : "";
    }
}
