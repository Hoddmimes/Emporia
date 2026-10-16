package com.hoddmimes.emporia.server.db;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache.InstrumentEntry;
import com.hoddmimes.emporia.server.InstrumentCache.MarketEntry;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.InternalTrade;
import com.hoddmimes.emporia.server.me.Order;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

/**
 * The database in MongoDB (configuration: "type": "mongodb", host, port, name): one collection per kind of
 * record, the databaseId is the document's ObjectId. The trades, bulletin trades and chat messages are
 * written with WriteConcern.JOURNALED (in MongoDB's journal on disk before the call returns).
 */
public class MongoDbDatabase implements Database {

    private static final Logger cLog = LogManager.getLogger(MongoDbDatabase.class);

    private final MongoClient   mMongoClient;
    private final MongoDatabase mDatabase;

    public MongoDbDatabase(JsonObject pConfig) {
        String tHost = pConfig.get("host").getAsString();
        int    tPort = pConfig.get("port").getAsInt();
        String tName = pConfig.get("name").getAsString();

        String tUri = "mongodb://" + tHost + ":" + tPort;
        cLog.info("Connecting to MongoDB at {}, database: {}", tUri, tName);
        mMongoClient = MongoClients.create(tUri);
        mDatabase = mMongoClient.getDatabase(tName);
        mDatabase.runCommand(new Document("ping", 1));
        cLog.info("MongoDB connection established, database: {}", tName);
        createIndexes();
    }

    /**
     * Creates the indexes if they are missing (createIndex does nothing for an existing index). Trades:
     * tradeTime, then _id, which serves both the day range on tradeTime and the latest first sort
     * (tradeTime, _id descending, the index read backwards) in findTrades; and buyAccount and sellAccount,
     * each followed by tradeTime and _id, for a firm's trades (listTradesByFirm: one index per side of
     * its $or, with the same day range and sort).
     */
    private void createIndexes() {
        var tTrades = mDatabase.getCollection("Trades");
        cLog.info("Trades indexes {}, {}, {}",
                tTrades.createIndex(Indexes.ascending("tradeTime", "_id")),
                tTrades.createIndex(Indexes.ascending("buyAccount", "tradeTime", "_id")),
                tTrades.createIndex(Indexes.ascending("sellAccount", "tradeTime", "_id")));
        cLog.info("Chat index {}", mDatabase.getCollection("Chat").createIndex(Indexes.ascending("time", "_id")));
    }

    @Override
    public void close() {
        mMongoClient.close();
    }

    private MongoCollection<Document> collection(String pName) {
        return mDatabase.getCollection(pName);
    }

    private static Bson byId(String pId) {
        return new Document("_id", new ObjectId(pId));   // IllegalArgumentException for a malformed id
    }

    private static String idOf(Document pDoc) {
        return pDoc.getObjectId("_id").toHexString();
    }

    private String insert(String pCollection, Document pDoc) {
        collection(pCollection).insertOne(pDoc);
        return idOf(pDoc);
    }

    /** The documents converted, leaving out those the conversion rejects (null). */
    private static <T> List<Stored<T>> stored(Iterable<Document> pDocs, Function<Document, T> pConvert) {
        List<Stored<T>> tResult = new ArrayList<>();
        for (Document tDoc : pDocs) {
            T tValue = pConvert.apply(tDoc);
            if (tValue != null) tResult.add(new Stored<>(idOf(tDoc), tValue));
        }
        return tResult;
    }

    private static <T> Stored<T> stored(Document pDoc, Function<Document, T> pConvert) {
        T tValue = (pDoc != null) ? pConvert.apply(pDoc) : null;
        return (tValue != null) ? new Stored<>(idOf(pDoc), tValue) : null;
    }

    // ── Admin ────────────────────────────────────────────────────────────

    @Override
    public void initializeAdmin() {
        if (collection("Admin").find().first() != null) {
            cLog.info("Admin document already exists, credentials unchanged");
            return;
        }
        cLog.info("No admin document found, creating with default credentials");
        collection("Admin").insertOne(new Document("username", "Admin")
                .append("password", Database.hashSha512("changeit"))
                .append("last-seen", now()));
        cLog.info("Admin document created with default credentials");
    }

    /** The admin with the user name (case-insensitive), or null. */
    private Document findAdmin(String pUsername) {
        for (Document tDoc : collection("Admin").find()) {
            String tStored = tDoc.getString("username");
            if (tStored != null && tStored.equalsIgnoreCase(pUsername)) return tDoc;
        }
        return null;
    }

    @Override
    public boolean verifyAdminLogin(String pUsername, String pPassword) {
        Document tDoc = findAdmin(pUsername);
        return tDoc != null && Database.hashSha512(pPassword).equals(tDoc.getString("password"));
    }

    @Override
    public boolean changeAdminPassword(String pUsername, String pCurrentPassword, String pNewPassword) {
        Document tDoc = findAdmin(pUsername);
        if (tDoc == null || !Database.hashSha512(pCurrentPassword).equals(tDoc.getString("password"))) return false;
        collection("Admin").updateOne(new Document("_id", tDoc.getObjectId("_id")),
                new Document("$set", new Document("password", Database.hashSha512(pNewPassword))));
        return true;
    }

    @Override
    public void updateLastSeen(String pUsername) {
        Document tDoc = findAdmin(pUsername);
        if (tDoc != null) {
            collection("Admin").updateOne(new Document("_id", tDoc.getObjectId("_id")),
                    new Document("$set", new Document("last-seen", now())));
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // ── Firms ────────────────────────────────────────────────────────────

    private static Firm toFirm(Document pDoc) {
        return new Firm(pDoc.getString("name"), pDoc.getString("status"), pDoc.getString("address"),
                pDoc.getString("phone"), pDoc.getString("contact"), pDoc.getString("mail"));
    }

    @Override
    public List<Stored<Firm>> listFirms() {
        return stored(collection("Firm").find(), MongoDbDatabase::toFirm);
    }

    @Override
    public Stored<Firm> getFirm(String pId) {
        return stored(collection("Firm").find(byId(pId)).first(), MongoDbDatabase::toFirm);
    }

    @Override
    public Firm getFirmByName(String pName) {
        Document tDoc = collection("Firm").find(new Document("name", pName)).first();
        return (tDoc != null) ? toFirm(tDoc) : null;
    }

    @Override
    public String createFirm(String pName, String pStatus, String pAddress, String pPhone, String pContact, String pMail) {
        Firm f = new Firm(pName, pStatus, pAddress, pPhone, pContact, pMail);
        return insert("Firm", new Document("name", f.name())
                .append("status",  f.status())
                .append("address", f.address())
                .append("phone",   f.phone())
                .append("contact", f.contact())
                .append("mail",    f.mail()));
    }

    @Override
    public void updateFirm(String pId, String pStatus, String pAddress, String pPhone, String pContact, String pMail) {
        Firm f = new Firm(null, pStatus, pAddress, pPhone, pContact, pMail);
        collection("Firm").updateOne(byId(pId), new Document("$set", new Document("status", f.status())
                .append("address", f.address())
                .append("phone",   f.phone())
                .append("contact", f.contact())
                .append("mail",    f.mail())));
    }

    @Override
    public String deleteFirm(String pId) {
        Document tFirm = collection("Firm").findOneAndDelete(byId(pId));
        if (tFirm == null) return null;
        String tName = tFirm.getString("name");
        collection("Authorization").deleteMany(new Document("firm", tName));
        return tName;
    }

    // ── Users ────────────────────────────────────────────────────────────

    private static User toUser(Document pDoc) {
        return new User(pDoc.getString("userId"), pDoc.getString("firm"), pDoc.getString("name"), pDoc.getString("mail"),
                pDoc.getString("phone"), pDoc.getString("status"), pDoc.getString("password"));
    }

    @Override
    public List<Stored<User>> listUsers() {
        return stored(collection("User").find(), MongoDbDatabase::toUser);
    }

    @Override
    public Stored<User> getUser(String pId) {
        return stored(collection("User").find(byId(pId)).first(), MongoDbDatabase::toUser);
    }

    @Override
    public User getUserByUserId(String pUserId) {
        Document tDoc = collection("User").find(new Document("userId", pUserId)).first();
        return (tDoc != null) ? toUser(tDoc) : null;
    }

    @Override
    public String createUser(String pUserId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword) {
        User u = new User(pUserId, pFirm, pName, pMail, pPhone, (pStatus != null) ? pStatus : "INACTIVE",
                (pPassword != null && !pPassword.isEmpty()) ? Database.hashSha512(pPassword) : "");
        return insert("User", new Document("userId", u.userId())
                .append("firm",     u.firm())
                .append("name",     u.name())
                .append("mail",     u.mail())
                .append("phone",    u.phone())
                .append("status",   u.status())
                .append("password", u.password()));
    }

    @Override
    public void updateUser(String pId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword) {
        Document tFields = new Document("firm", Database.orEmpty(pFirm))
                .append("name",   Database.orEmpty(pName))
                .append("mail",   Database.orEmpty(pMail))
                .append("phone",  Database.orEmpty(pPhone))
                .append("status", (pStatus != null) ? pStatus : "INACTIVE");
        if (pPassword != null && !pPassword.isEmpty()) tFields.append("password", Database.hashSha512(pPassword));
        collection("User").updateOne(byId(pId), new Document("$set", tFields));
    }

    @Override
    public void deleteUser(String pId) {
        collection("User").deleteOne(byId(pId));
    }

    // ── Markets ──────────────────────────────────────────────────────────

    /** Maps a Markets document; null if it has no marketId. */
    static MarketEntry toMarket(Document pDoc) {
        Integer tMarketId = pDoc.getInteger("marketId");
        if (tMarketId == null) return null;
        Boolean tInverse = pDoc.getBoolean("inverse");
        return new MarketEntry(tMarketId,
                str(pDoc, "name"), str(pDoc, "description"), str(pDoc, "type"),
                str(pDoc, "open"), str(pDoc, "close"), tInverse != null && tInverse,
                str(pDoc, "preOpenTime"), str(pDoc, "openAuctionTime"),
                str(pDoc, "auctionInterval"), str(pDoc, "randomAuctionInterval"),
                str(pDoc, "tradingStatus"));
    }

    /** The fields of a market that an update writes (all but the marketId and name). */
    private static Document marketFields(MarketEntry m) {
        return new Document("description",          m.description())
                .append("type",                 m.type())
                .append("open",                 m.open())
                .append("close",                m.close())
                .append("inverse",              m.inverse())
                .append("preOpenTime",          m.preOpenTime())
                .append("openAuctionTime",      m.openAuctionTime())
                .append("auctionInterval",      m.auctionInterval())
                .append("randomAuctionInterval",m.randomAuctionInterval())
                .append("tradingStatus",        m.tradingStatus());
    }

    @Override
    public List<Stored<MarketEntry>> listMarkets() {
        return stored(collection("Markets").find(), MongoDbDatabase::toMarket);
    }

    @Override
    public Stored<MarketEntry> getMarket(String pId) {
        return stored(collection("Markets").find(byId(pId)).first(), MongoDbDatabase::toMarket);
    }

    @Override
    public String createMarket(MarketEntry pMarket) {
        Document tDoc = new Document("marketId", pMarket.marketId()).append("name", pMarket.name());
        tDoc.putAll(marketFields(pMarket));
        return insert("Markets", tDoc);
    }

    @Override
    public void updateMarket(String pId, MarketEntry pMarket) {
        Document tFields = marketFields(pMarket);
        if (!pMarket.name().isEmpty()) tFields.append("name", pMarket.name());
        collection("Markets").updateOne(byId(pId), new Document("$set", tFields));
    }

    @Override
    public MarketEntry setMarketStatus(int pMarketId, String pStatus) {
        Document tDoc = collection("Markets").findOneAndUpdate(new Document("marketId", pMarketId),
                new Document("$set", new Document("tradingStatus", pStatus)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return (tDoc != null) ? toMarket(tDoc) : null;
    }

    @Override
    public void deleteMarket(String pId) {
        collection("Markets").deleteOne(byId(pId));
    }

    // ── Bulletin markets ─────────────────────────────────────────────────

    /** Maps a BulletinMarkets document; null if it has no marketId. */
    static BulletinMarket toBulletinMarket(Document pDoc) {
        Integer tMarketId = pDoc.getInteger("marketId");
        if (tMarketId == null) return null;
        return BulletinMarket.of(tMarketId, str(pDoc, "name"), str(pDoc, "description"), str(pDoc, "tradingStatus"),
                str(pDoc, "open"), str(pDoc, "close"), pDoc.getList("instruments", String.class, List.of()),
                str(pDoc, "maxOrderTime"));
    }

    private static Document bulletinMarketFields(BulletinMarket m) {
        return new Document("description",   m.description())
                .append("tradingStatus", m.tradingStatus())
                .append("open",          m.open())
                .append("close",         m.close())
                .append("instruments",   m.instruments())
                .append("maxOrderTime",  m.maxOrderTime());
    }

    @Override
    public List<Stored<BulletinMarket>> listBulletinMarkets() {
        return stored(collection("BulletinMarkets").find(), MongoDbDatabase::toBulletinMarket);
    }

    @Override
    public Stored<BulletinMarket> getBulletinMarket(String pId) {
        return stored(collection("BulletinMarkets").find(byId(pId)).first(), MongoDbDatabase::toBulletinMarket);
    }

    @Override
    public String createBulletinMarket(BulletinMarket pMarket) {
        Document tDoc = new Document("marketId", pMarket.marketId()).append("name", pMarket.name());
        tDoc.putAll(bulletinMarketFields(pMarket));
        return insert("BulletinMarkets", tDoc);
    }

    @Override
    public void updateBulletinMarket(String pId, BulletinMarket pMarket) {
        collection("BulletinMarkets").updateOne(byId(pId), new Document("$set", bulletinMarketFields(pMarket)));
    }

    @Override
    public BulletinMarket setBulletinMarketStatus(int pMarketId, String pStatus) {
        Document tDoc = collection("BulletinMarkets").findOneAndUpdate(new Document("marketId", pMarketId),
                new Document("$set", new Document("tradingStatus", pStatus)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return (tDoc != null) ? toBulletinMarket(tDoc) : null;
    }

    @Override
    public void deleteBulletinMarket(String pId) {
        collection("BulletinMarkets").deleteOne(byId(pId));
    }

    // ── Bulletins ────────────────────────────────────────────────────────

    @Override
    public void saveBulletin(StoredBulletin b) {
        Document tDoc = new Document("bulletinId", b.bulletinId())
                .append("marketId",          b.marketId())
                .append("instrument",        b.instrument())
                .append("side",              b.side())
                .append("price",             b.price())
                .append("quantity",          b.quantity())
                .append("remainingQuantity", b.remainingQuantity())
                .append("ownerFirm",         b.ownerFirm())
                .append("ownerUser",         b.ownerUser())
                .append("userRef",           b.userRef())
                .append("clientAccount",     b.clientAccount())
                .append("createTime",        b.createTime())
                .append("expiresAt",         b.expiresAt());
        collection("Bulletins").replaceOne(new Document("bulletinId", b.bulletinId()), tDoc, new ReplaceOptions().upsert(true));
    }

    @Override
    public void deleteBulletin(String pBulletinId) {
        collection("Bulletins").deleteOne(new Document("bulletinId", pBulletinId));
    }

    @Override
    public List<StoredBulletin> listBulletins() {
        List<StoredBulletin> tResult = new ArrayList<>();
        for (Document d : collection("Bulletins").find().sort(new Document("createTime", 1))) {
            tResult.add(new StoredBulletin(d.getString("bulletinId"), d.getInteger("marketId"),
                    d.getString("instrument"), d.getString("side"),
                    d.getLong("price"), d.getLong("quantity"), d.getLong("remainingQuantity"),
                    d.getString("ownerFirm"), d.getString("ownerUser"), d.getString("userRef"),
                    clientAccountOf(d, "clientAccount"),   // the default one for bulletins stored before client accounts
                    d.getLong("createTime"), d.getLong("expiresAt")));
        }
        return tResult;
    }

    // ── Instruments ──────────────────────────────────────────────────────

    /** Maps an Instruments document; null if it has no valid instrumentId. */
    static InstrumentEntry toInstrument(Document pDoc) {
        long tInstrumentId;
        try { tInstrumentId = Long.parseLong(str(pDoc, "instrumentId")); }
        catch (NumberFormatException e) { return null; }
        Integer tMarketId = pDoc.getInteger("marketId");
        return new InstrumentEntry(tInstrumentId, tMarketId != null ? tMarketId : 0,
                str(pDoc, "name"), str(pDoc, "instType"),
                lng(pDoc, "tickSize"), lng(pDoc, "lotSize"),
                str(pDoc, "isin"), str(pDoc, "tradingStatus"), str(pDoc, "expirationDate"),
                lng(pDoc, "strikePrice"), str(pDoc, "underlying"), str(pDoc, "optionType"));
    }

    /** The fields of an instrument that an update writes (all but the instrumentId, name and tradingStatus). */
    private static Document instrumentFields(InstrumentEntry i) {
        return new Document("marketId",      i.marketId())
                .append("instType",      i.instType())
                .append("tickSize",      i.tickSize())
                .append("lotSize",       i.lotSize())
                .append("isin",          i.isin())
                .append("expirationDate",i.expirationDate())
                .append("strikePrice",   i.strikePrice())
                .append("underlying",    i.underlying())
                .append("optionType",    i.optionType());
    }

    @Override
    public List<Stored<InstrumentEntry>> listInstrumentsByMarket(int pMarketId) {
        return stored(collection("Instruments").find(new Document("marketId", pMarketId)), MongoDbDatabase::toInstrument);
    }

    @Override
    public Stored<InstrumentEntry> getInstrument(String pId) {
        return stored(collection("Instruments").find(byId(pId)).first(), MongoDbDatabase::toInstrument);
    }

    @Override
    public String createInstrument(InstrumentEntry pInstrument) {
        Document tDoc = new Document("instrumentId", String.valueOf(pInstrument.instrumentId()))
                .append("name",          pInstrument.name())
                .append("tradingStatus", pInstrument.tradingStatus());
        tDoc.putAll(instrumentFields(pInstrument));
        return insert("Instruments", tDoc);
    }

    @Override
    public void updateInstrument(String pId, InstrumentEntry pInstrument) {
        Document tFields = instrumentFields(pInstrument);
        if (!pInstrument.name().isEmpty()) tFields.append("name", pInstrument.name());
        collection("Instruments").updateOne(byId(pId), new Document("$set", tFields));
    }

    @Override
    public void updateInstrumentStatus(String pId, String pStatus) {
        collection("Instruments").updateOne(byId(pId), new Document("$set", new Document("tradingStatus", pStatus)));
    }

    @Override
    public void deleteInstrument(String pId) {
        collection("Instruments").deleteOne(byId(pId));
    }

    @Override
    public boolean deleteInstrumentByInstrumentId(long pInstrumentId) {
        return collection("Instruments").deleteOne(new Document("instrumentId", String.valueOf(pInstrumentId)))
                .getDeletedCount() > 0;
    }

    // ── Authorizations ───────────────────────────────────────────────────

    private static Authorization toAuthorization(Document pDoc) {
        String tFirm   = pDoc.getString("firm");
        String tMarket = pDoc.getString("market");
        return (tFirm != null && tMarket != null) ? new Authorization(tFirm, tMarket) : null;
    }

    @Override
    public List<Stored<Authorization>> listAuthorizationsByFirm(String pFirm) {
        return stored(collection("Authorization").find(new Document("firm", pFirm)), MongoDbDatabase::toAuthorization);
    }

    @Override
    public List<Authorization> listAuthorizations() {
        return stored(collection("Authorization").find(), MongoDbDatabase::toAuthorization).stream().map(Stored::value).toList();
    }

    @Override
    public boolean authorizationExists(String pFirm, String pMarket) {
        return collection("Authorization").find(new Document("firm", pFirm).append("market", pMarket)).first() != null;
    }

    @Override
    public String createAuthorization(String pFirm, String pMarket) {
        return insert("Authorization", new Document("firm", Database.orEmpty(pFirm)).append("market", Database.orEmpty(pMarket)));
    }

    @Override
    public void deleteAuthorization(String pId) {
        collection("Authorization").deleteOne(byId(pId));
    }

    // ── Trades ───────────────────────────────────────────────────────────

    /**
     * Stores the trades with one journaled write: MongoDB answers once the trades are in its journal on
     * disk (an fsync), so they survive a host failure.
     */
    @Override
    public void saveTrades(List<InternalTrade> pTrades) {
        List<Document> tDocs = new ArrayList<>(pTrades.size());
        for (InternalTrade tTrade : pTrades) tDocs.add(toTradeDocument(tTrade));
        collection("Trades").withWriteConcern(WriteConcern.JOURNALED).insertMany(tDocs);
    }

    /**
     * The Trades document of a trade. Ids are stored as in the REST API (trade and order ids as hex
     * strings, the instrumentId as a string like in Instruments), prices and quantities as scaled longs and
     * the trade time as "yyyy-MM-dd HH:mm:ss.SSS" (server local time).
     */
    static Document toTradeDocument(InternalTrade pTrade) {
        Order tBuy  = pTrade.getBuyOrder();
        Order tSell = pTrade.getSellOrder();
        return new Document("tradeNo",       Long.toHexString(pTrade.getTradeNo()))
                .append("marketId",      (int) (pTrade.getInstrumentId() >>> 32))
                .append("instrumentId",  String.valueOf(pTrade.getInstrumentId()))
                .append("price",         pTrade.getPrice())
                .append("quantity",      pTrade.getQuantity())
                .append("tradeTime",     Database.formatTradeTime(pTrade.getTradeTime()))
                .append("buyOrderId",    Long.toHexString(tBuy.getOrderId()))
                .append("buyAccount",    tBuy.getAccount())
                .append("buyUserRef",    tBuy.getUserRef())
                .append("buyClientAccount",  tBuy.getClientAccount())
                .append("sellOrderId",   Long.toHexString(tSell.getOrderId()))
                .append("sellAccount",   tSell.getAccount())
                .append("sellUserRef",   tSell.getUserRef())
                .append("sellClientAccount", tSell.getClientAccount());
    }

    /**
     * A Trades document back to an InternalTrade, with its stored trade number and time. The document holds
     * only the order ids, accounts, user refs and client accounts of the two orders, so the trade's buy and
     * sell orders are stand-ins (see InternalTrade).
     */
    static InternalTrade fromTradeDocument(Document pDoc) {
        return new InternalTrade(
                Long.parseUnsignedLong(pDoc.getString("tradeNo"), 16),
                Database.parseTradeTime(pDoc.getString("tradeTime")),
                Long.parseLong(pDoc.getString("instrumentId")),
                pDoc.getLong("price"),
                pDoc.getLong("quantity"),
                Long.parseUnsignedLong(pDoc.getString("buyOrderId"), 16),
                pDoc.getString("buyAccount"),
                pDoc.getString("buyUserRef"),
                clientAccountOf(pDoc, "buyClientAccount"),
                Long.parseUnsignedLong(pDoc.getString("sellOrderId"), 16),
                pDoc.getString("sellAccount"),
                pDoc.getString("sellUserRef"),
                clientAccountOf(pDoc, "sellClientAccount"));
    }

    /** A client account field; the default account in documents stored before client accounts. */
    static String clientAccountOf(Document pDoc, String pField) {
        String tAccount = pDoc.getString(pField);
        return (tAccount != null) ? tAccount : AddOrderRequest.DEFAULT_CLIENT_ACCOUNT;
    }

    @Override
    public List<InternalTrade> listTradesByDate(String pDate) {
        return findTrades("Trades", new Document("tradeTime", tradeDay(pDate)), MongoDbDatabase::fromTradeDocument);
    }

    @Override
    public List<InternalTrade> listTradesByFirm(String pFirm, String pDate) {
        return findTrades("Trades", firmFilter(pFirm, pDate), MongoDbDatabase::fromTradeDocument);
    }

    @Override
    public long lastTradePrice(long pInstrumentId) {
        Document tDoc = collection("Trades").find(new Document("instrumentId", String.valueOf(pInstrumentId)))
                .sort(new Document("tradeTime", -1).append("_id", -1)).first();
        return (tDoc != null) ? tDoc.getLong("price") : 0L;
    }

    /** The firm on either side, on the day (all days if pDate is null). */
    private static Document firmFilter(String pFirm, String pDate) {
        Document tFilter = new Document("$or", List.of(new Document("buyAccount", pFirm), new Document("sellAccount", pFirm)));
        if (pDate != null) tFilter.append("tradeTime", tradeDay(pDate));
        return tFilter;
    }

    private <T> List<T> findTrades(String pCollection, Document pFilter, Function<Document, T> pConvert) {
        // Latest first; _id (increasing on insert) orders trades stored within the same millisecond
        List<T> tResult = new ArrayList<>();
        for (Document tDoc : collection(pCollection).find(pFilter).sort(new Document("tradeTime", -1).append("_id", -1))) {
            tResult.add(pConvert.apply(tDoc));
        }
        return tResult;
    }

    /** A tradeTime condition matching the whole day of a "yyyy-MM-dd" date. */
    private static Document tradeDay(String pDate) {
        String[] tRange = Database.dayRange(pDate);
        return new Document("$gte", tRange[0]).append("$lte", tRange[1]);
    }

    // ── Bulletin trades ──────────────────────────────────────────────────

    @Override
    public void saveBulletinTrade(BulletinTrade t) {
        collection("BulletinTrades").withWriteConcern(WriteConcern.JOURNALED).insertOne(new Document("tradeNo", t.tradeNo())
                .append("bulletinId",  t.bulletinId())
                .append("marketId",    t.marketId())
                .append("instrument",  t.instrument())
                .append("price",       t.price())
                .append("quantity",    t.quantity())
                .append("tradeTime",   Database.formatTradeTime(t.tradeTime()))
                .append("buyAccount",  t.buyAccount())
                .append("buyUser",     t.buyUser())
                .append("buyRef",      t.buyRef())
                .append("buyClientAccount",  t.buyClientAccount())
                .append("sellAccount", t.sellAccount())
                .append("sellUser",    t.sellUser())
                .append("sellRef",     t.sellRef())
                .append("sellClientAccount", t.sellClientAccount()));
    }

    private static BulletinTrade toBulletinTrade(Document d) {
        return new BulletinTrade(d.getString("tradeNo"), d.getString("bulletinId"), d.getInteger("marketId"),
                d.getString("instrument"), d.getLong("price"), d.getLong("quantity"),
                Database.parseTradeTime(d.getString("tradeTime")),
                d.getString("buyAccount"),  d.getString("buyUser"),  d.getString("buyRef"),  clientAccountOf(d, "buyClientAccount"),
                d.getString("sellAccount"), d.getString("sellUser"), d.getString("sellRef"), clientAccountOf(d, "sellClientAccount"));
    }

    @Override
    public List<BulletinTrade> listBulletinTradesByFirm(String pFirm, String pDate) {
        return findTrades("BulletinTrades", firmFilter(pFirm, pDate), MongoDbDatabase::toBulletinTrade);
    }

    @Override
    public long lastBulletinTradePrice(int pMarketId, String pInstrument) {
        Document tDoc = collection("BulletinTrades")
                .find(new Document("marketId", pMarketId).append("instrument", pInstrument))
                .sort(new Document("tradeTime", -1).append("_id", -1)).first();
        return (tDoc != null) ? tDoc.getLong("price") : 0L;
    }

    // ── Chat ─────────────────────────────────────────────────────────────

    @Override
    public void saveChatMessage(ChatMessage m) {
        collection("Chat").withWriteConcern(WriteConcern.JOURNALED).insertOne(new Document("chatId", m.chatId())
                .append("time", Database.formatTradeTime(m.time()))
                .append("firm", m.firm())
                .append("user", m.user())
                .append("text", m.text())
                .append("tag",  m.tag()));
    }

    @Override
    public List<ChatMessage> listChatMessages(String pFromDate, String pToDate, String pTag, int pLimit) {
        Document tTime = new Document("$lte", Database.dayRange(pToDate)[1]);
        if (pFromDate != null) tTime.append("$gte", Database.dayRange(pFromDate)[0]);
        Document tFilter = new Document("time", tTime);
        if (pTag != null && !pTag.isEmpty()) {
            tFilter.append("tag", new Document("$regex", escapeRegex(pTag)).append("$options", "i"));
        }
        List<ChatMessage> tOut = new ArrayList<>();
        for (Document d : collection("Chat").find(tFilter).sort(Sorts.descending("time", "_id")).limit(pLimit)) {
            tOut.add(new ChatMessage(d.getString("chatId"), Database.parseTradeTime(d.getString("time")),
                    d.getString("firm"), d.getString("user"), d.getString("text"), Database.orEmpty(d.getString("tag"))));
        }
        Collections.reverse(tOut);
        return tOut;
    }

    /** The text with the regular expression's special characters escaped, so it matches literally. */
    static String escapeRegex(String pText) {
        return pText.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String str(Document pDoc, String pField) {
        return Database.orEmpty(pDoc.getString(pField));
    }

    private static long lng(Document pDoc, String pField) {
        Long tVal = pDoc.getLong(pField);
        return tVal != null ? tVal : 0L;
    }
}
