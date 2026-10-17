package com.hoddmimes.emporia.server.db;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hoddmimes.emporia.server.InstrumentCache.InstrumentEntry;
import com.hoddmimes.emporia.server.InstrumentCache.MarketEntry;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.InternalTrade;
import com.hoddmimes.emporia.server.me.Order;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The database in an SQLite file (configuration: "type": "sqlite", "file": the database file, relative to
 * the working directory; created with its tables if missing). The tables have the names and columns of
 * the MongoDB collections and fields; the records' databaseId is the column id, made by DatabaseIds like a
 * MongoDB ObjectId. The trades, bulletin trades and chat messages have an autoincrement seq, which orders
 * those stored within the same millisecond.
 *
 * One connection, used by one thread at a time (the methods are synchronized; SQLite writes one
 * transaction at a time anyway). WAL journal with synchronous=FULL: each commit is fsynced, so a stored
 * trade survives a host failure; saveTrades stores its batch in one transaction, one fsync for all.
 */
public class SqliteDatabase implements Database {

    private static final Logger cLog = LogManager.getLogger(SqliteDatabase.class);
    private static final Gson   GSON = new Gson();

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS Admin (id TEXT PRIMARY KEY, username TEXT NOT NULL, password TEXT NOT NULL,
            lastSeen TEXT)""",
        """
        CREATE TABLE IF NOT EXISTS Firm (id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT, address TEXT,
            phone TEXT, contact TEXT, mail TEXT)""",
        "CREATE INDEX IF NOT EXISTS Firm_name ON Firm (name)",
        """
        CREATE TABLE IF NOT EXISTS User (id TEXT PRIMARY KEY, userId TEXT NOT NULL, firm TEXT, name TEXT,
            mail TEXT, phone TEXT, status TEXT, password TEXT)""",
        "CREATE INDEX IF NOT EXISTS User_userId ON User (userId)",
        """
        CREATE TABLE IF NOT EXISTS Markets (id TEXT PRIMARY KEY, marketId INTEGER NOT NULL, name TEXT,
            description TEXT, type TEXT, open TEXT, close TEXT, inverse INTEGER, preOpenTime TEXT,
            openAuctionTime TEXT, auctionInterval TEXT, randomAuctionInterval TEXT, tradingStatus TEXT)""",
        "CREATE INDEX IF NOT EXISTS Markets_marketId ON Markets (marketId)",
        """
        CREATE TABLE IF NOT EXISTS BulletinMarkets (id TEXT PRIMARY KEY, marketId INTEGER NOT NULL, name TEXT,
            description TEXT, tradingStatus TEXT, open TEXT, close TEXT,
            instruments TEXT, maxOrderTime TEXT)""",
        "CREATE INDEX IF NOT EXISTS BulletinMarkets_marketId ON BulletinMarkets (marketId)",
        """
        CREATE TABLE IF NOT EXISTS Bulletins (bulletinId TEXT PRIMARY KEY, marketId INTEGER, instrument TEXT,
            side TEXT, price INTEGER, quantity INTEGER, remainingQuantity INTEGER, ownerFirm TEXT, ownerUser TEXT,
            userRef TEXT, clientAccount TEXT, createTime INTEGER, expiresAt INTEGER)""",
        """
        CREATE TABLE IF NOT EXISTS Instruments (id TEXT PRIMARY KEY, instrumentId TEXT NOT NULL,
            marketId INTEGER, name TEXT, instType TEXT, tickSize INTEGER, lotSize INTEGER, tradingStatus TEXT,
            isin TEXT, expirationDate TEXT, strikePrice INTEGER, underlying TEXT, optionType TEXT)""",
        "CREATE INDEX IF NOT EXISTS Instruments_marketId ON Instruments (marketId)",
        "CREATE INDEX IF NOT EXISTS Instruments_instrumentId ON Instruments (instrumentId)",
        "CREATE TABLE IF NOT EXISTS Authorization (id TEXT PRIMARY KEY, firm TEXT NOT NULL, market TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS Authorization_firm ON Authorization (firm)",
        """
        CREATE TABLE IF NOT EXISTS Trades (seq INTEGER PRIMARY KEY AUTOINCREMENT, tradeNo TEXT,
            marketId INTEGER, instrumentId TEXT, price INTEGER, quantity INTEGER, tradeTime TEXT,
            buyOrderId TEXT, buyAccount TEXT, buyUserRef TEXT, buyClientAccount TEXT,
            sellOrderId TEXT, sellAccount TEXT, sellUserRef TEXT, sellClientAccount TEXT,
            buyUser TEXT, sellUser TEXT, reversalOf TEXT)""",
        "CREATE INDEX IF NOT EXISTS Trades_tradeTime ON Trades (tradeTime, seq)",
        "CREATE INDEX IF NOT EXISTS Trades_buyAccount ON Trades (buyAccount, tradeTime, seq)",
        "CREATE INDEX IF NOT EXISTS Trades_sellAccount ON Trades (sellAccount, tradeTime, seq)",
        "CREATE INDEX IF NOT EXISTS Trades_instrumentId ON Trades (instrumentId, tradeTime, seq)",
        """
        CREATE TABLE IF NOT EXISTS BulletinTrades (seq INTEGER PRIMARY KEY AUTOINCREMENT, tradeNo TEXT,
            bulletinId TEXT, marketId INTEGER, instrument TEXT, price INTEGER, quantity INTEGER, tradeTime TEXT,
            buyAccount TEXT, buyUser TEXT, buyRef TEXT, buyClientAccount TEXT,
            sellAccount TEXT, sellUser TEXT, sellRef TEXT, sellClientAccount TEXT)""",
        "CREATE INDEX IF NOT EXISTS BulletinTrades_buyAccount ON BulletinTrades (buyAccount, tradeTime, seq)",
        "CREATE INDEX IF NOT EXISTS BulletinTrades_sellAccount ON BulletinTrades (sellAccount, tradeTime, seq)",
        "CREATE INDEX IF NOT EXISTS BulletinTrades_instrument ON BulletinTrades (marketId, instrument, tradeTime, seq)",
        """
        CREATE TABLE IF NOT EXISTS Chat (seq INTEGER PRIMARY KEY AUTOINCREMENT, chatId TEXT, time TEXT,
            firm TEXT, user TEXT, text TEXT, tag TEXT)""",
        "CREATE INDEX IF NOT EXISTS Chat_time ON Chat (time, seq)",
    };

    /**
     * Columns added to a table after its first version, as table and column definition: added at startup to
     * the tables of an older database file (CREATE TABLE IF NOT EXISTS leaves an existing table as it is).
     * A new column is added both here and in its CREATE TABLE.
     */
    private static final String[][] ADDED_COLUMNS = {
        { "Trades", "buyUser TEXT" },
        { "Trades", "sellUser TEXT" },
        { "Trades", "reversalOf TEXT" },
    };

    /** Indexes on added columns, created after the columns. */
    private static final String[] ADDED_INDEXES = {
        "CREATE INDEX IF NOT EXISTS Trades_reversalOf ON Trades (reversalOf) WHERE reversalOf IS NOT NULL",
    };

    private final Connection mConnection;

    public SqliteDatabase(JsonObject pConfig) {
        String tFile = pConfig.has("file") ? pConfig.get("file").getAsString() : "emporia.db";
        cLog.info("Opening SQLite database {}", Path.of(tFile).toAbsolutePath().normalize());
        try {
            mConnection = DriverManager.getConnection("jdbc:sqlite:" + tFile);
            try (Statement s = mConnection.createStatement()) {
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = FULL");
                s.execute("PRAGMA busy_timeout = 10000");
                for (String tSql : SCHEMA) s.execute(tSql);
                addMissingColumns(s);
                for (String tSql : ADDED_INDEXES) s.execute(tSql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open the SQLite database " + tFile + ": " + e.getMessage(), e);
        }
        cLog.info("SQLite database open");
    }

    /** Adds the ADDED_COLUMNS a table of an older database file does not have yet. */
    private static void addMissingColumns(Statement pStmt) throws SQLException {
        for (String[] tAdded : ADDED_COLUMNS) {
            String tColumn = tAdded[1].split(" ")[0];
            boolean tExists = false;
            try (ResultSet rs = pStmt.executeQuery("PRAGMA table_info(" + tAdded[0] + ")")) {
                while (rs.next()) if (tColumn.equals(rs.getString("name"))) tExists = true;
            }
            if (!tExists) {
                pStmt.execute("ALTER TABLE " + tAdded[0] + " ADD COLUMN " + tAdded[1]);
                cLog.info("SQLite database: column {}.{} added", tAdded[0], tColumn);
            }
        }
    }

    @Override
    public synchronized void close() {
        try { mConnection.close(); }
        catch (SQLException e) { cLog.warn("Closing the SQLite database failed: {}", e.getMessage()); }
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Row<T> { T map(ResultSet pRs) throws SQLException; }

    @FunctionalInterface
    private interface Work<T> { T run() throws SQLException; }

    private static IllegalStateException failed(SQLException e) {
        return new IllegalStateException("SQLite: " + e.getMessage(), e);
    }

    private static void bind(PreparedStatement pStmt, Object... pParams) throws SQLException {
        for (int i = 0; i < pParams.length; i++) {
            Object tValue = pParams[i];
            pStmt.setObject(i + 1, (tValue instanceof Boolean b) ? (b ? 1 : 0) : tValue);
        }
    }

    /** Executes an INSERT, UPDATE or DELETE; returns the number of rows changed. */
    private synchronized int update(String pSql, Object... pParams) {
        try (PreparedStatement tStmt = mConnection.prepareStatement(pSql)) {
            bind(tStmt, pParams);
            return tStmt.executeUpdate();
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private synchronized <T> List<T> query(String pSql, Row<T> pRow, Object... pParams) {
        try (PreparedStatement tStmt = mConnection.prepareStatement(pSql)) {
            bind(tStmt, pParams);
            List<T> tResult = new ArrayList<>();
            try (ResultSet tRs = tStmt.executeQuery()) {
                while (tRs.next()) {
                    T tValue = pRow.map(tRs);
                    if (tValue != null) tResult.add(tValue);
                }
            }
            return tResult;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private <T> T queryOne(String pSql, Row<T> pRow, Object... pParams) {
        List<T> tResult = query(pSql, pRow, pParams);
        return tResult.isEmpty() ? null : tResult.get(0);
    }

    /** Runs the work in one transaction (one commit, one fsync). */
    private synchronized <T> T transaction(Work<T> pWork) {
        try {
            mConnection.setAutoCommit(false);
            try {
                T tResult = pWork.run();
                mConnection.commit();
                return tResult;
            } catch (SQLException | RuntimeException e) {
                mConnection.rollback();
                throw e;
            } finally {
                mConnection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    /** A row with its databaseId (column id). */
    private static <T> Row<Stored<T>> stored(Row<T> pRow) {
        return rs -> {
            T tValue = pRow.map(rs);
            return (tValue != null) ? new Stored<>(rs.getString("id"), tValue) : null;
        };
    }

    private static String str(ResultSet pRs, String pColumn) throws SQLException {
        return Database.orEmpty(pRs.getString(pColumn));
    }

    // ── Admin ────────────────────────────────────────────────────────────

    @Override
    public void initializeAdmin() {
        if (queryOne("SELECT id FROM Admin LIMIT 1", rs -> rs.getString(1)) != null) {
            cLog.info("Admin already exists, credentials unchanged");
            return;
        }
        update("INSERT INTO Admin (id, username, password, lastSeen) VALUES (?, ?, ?, ?)",
                DatabaseIds.newId(), "Admin", Database.hashSha512("changeit"), now());
        cLog.info("Admin created with default credentials");
    }

    /** The id and password hash of the admin with the user name (case-insensitive), or null. */
    private String[] findAdmin(String pUsername) {
        for (String[] tAdmin : query("SELECT id, username, password FROM Admin",
                rs -> new String[] { rs.getString("id"), rs.getString("username"), rs.getString("password") })) {
            if (tAdmin[1].equalsIgnoreCase(pUsername)) return new String[] { tAdmin[0], tAdmin[2] };
        }
        return null;
    }

    @Override
    public boolean verifyAdminLogin(String pUsername, String pPassword) {
        String[] tAdmin = findAdmin(pUsername);
        return tAdmin != null && Database.hashSha512(pPassword).equals(tAdmin[1]);
    }

    @Override
    public boolean changeAdminPassword(String pUsername, String pCurrentPassword, String pNewPassword) {
        String[] tAdmin = findAdmin(pUsername);
        if (tAdmin == null || !Database.hashSha512(pCurrentPassword).equals(tAdmin[1])) return false;
        update("UPDATE Admin SET password = ? WHERE id = ?", Database.hashSha512(pNewPassword), tAdmin[0]);
        return true;
    }

    @Override
    public void updateLastSeen(String pUsername) {
        String[] tAdmin = findAdmin(pUsername);
        if (tAdmin != null) update("UPDATE Admin SET lastSeen = ? WHERE id = ?", now(), tAdmin[0]);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // ── Firms ────────────────────────────────────────────────────────────

    private static Firm toFirm(ResultSet rs) throws SQLException {
        return new Firm(rs.getString("name"), rs.getString("status"), rs.getString("address"),
                rs.getString("phone"), rs.getString("contact"), rs.getString("mail"));
    }

    @Override
    public List<Stored<Firm>> listFirms() {
        return query("SELECT * FROM Firm ORDER BY rowid", stored(SqliteDatabase::toFirm));
    }

    @Override
    public Stored<Firm> getFirm(String pId) {
        return queryOne("SELECT * FROM Firm WHERE id = ?", stored(SqliteDatabase::toFirm), DatabaseIds.check(pId));
    }

    @Override
    public Firm getFirmByName(String pName) {
        return queryOne("SELECT * FROM Firm WHERE name = ? LIMIT 1", SqliteDatabase::toFirm, pName);
    }

    @Override
    public String createFirm(String pName, String pStatus, String pAddress, String pPhone, String pContact, String pMail) {
        Firm f = new Firm(pName, pStatus, pAddress, pPhone, pContact, pMail);
        String tId = DatabaseIds.newId();
        update("INSERT INTO Firm (id, name, status, address, phone, contact, mail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                tId, f.name(), f.status(), f.address(), f.phone(), f.contact(), f.mail());
        return tId;
    }

    @Override
    public void updateFirm(String pId, String pStatus, String pAddress, String pPhone, String pContact, String pMail) {
        Firm f = new Firm(null, pStatus, pAddress, pPhone, pContact, pMail);
        update("UPDATE Firm SET status = ?, address = ?, phone = ?, contact = ?, mail = ? WHERE id = ?",
                f.status(), f.address(), f.phone(), f.contact(), f.mail(), DatabaseIds.check(pId));
    }

    @Override
    public String deleteFirm(String pId) {
        String tId = DatabaseIds.check(pId);
        return transaction(() -> {
            Stored<Firm> tFirm = getFirm(tId);
            if (tFirm == null) return null;
            update("DELETE FROM Firm WHERE id = ?", tId);
            update("DELETE FROM Authorization WHERE firm = ?", tFirm.value().name());
            return tFirm.value().name();
        });
    }

    // ── Users ────────────────────────────────────────────────────────────

    private static User toUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("userId"), rs.getString("firm"), rs.getString("name"), rs.getString("mail"),
                rs.getString("phone"), rs.getString("status"), rs.getString("password"));
    }

    @Override
    public List<Stored<User>> listUsers() {
        return query("SELECT * FROM User ORDER BY rowid", stored(SqliteDatabase::toUser));
    }

    @Override
    public Stored<User> getUser(String pId) {
        return queryOne("SELECT * FROM User WHERE id = ?", stored(SqliteDatabase::toUser), DatabaseIds.check(pId));
    }

    @Override
    public User getUserByUserId(String pUserId) {
        return queryOne("SELECT * FROM User WHERE userId = ? LIMIT 1", SqliteDatabase::toUser, pUserId);
    }

    @Override
    public String createUser(String pUserId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword) {
        User u = new User(pUserId, pFirm, pName, pMail, pPhone, (pStatus != null) ? pStatus : "INACTIVE",
                (pPassword != null && !pPassword.isEmpty()) ? Database.hashSha512(pPassword) : "");
        String tId = DatabaseIds.newId();
        update("INSERT INTO User (id, userId, firm, name, mail, phone, status, password) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tId, u.userId(), u.firm(), u.name(), u.mail(), u.phone(), u.status(), u.password());
        return tId;
    }

    @Override
    public void updateUser(String pId, String pFirm, String pName, String pMail, String pPhone, String pStatus, String pPassword) {
        String tId     = DatabaseIds.check(pId);
        String tStatus = (pStatus != null) ? pStatus : "INACTIVE";
        if (pPassword != null && !pPassword.isEmpty()) {
            update("UPDATE User SET firm = ?, name = ?, mail = ?, phone = ?, status = ?, password = ? WHERE id = ?",
                    Database.orEmpty(pFirm), Database.orEmpty(pName), Database.orEmpty(pMail), Database.orEmpty(pPhone),
                    tStatus, Database.hashSha512(pPassword), tId);
        } else {
            update("UPDATE User SET firm = ?, name = ?, mail = ?, phone = ?, status = ? WHERE id = ?",
                    Database.orEmpty(pFirm), Database.orEmpty(pName), Database.orEmpty(pMail), Database.orEmpty(pPhone),
                    tStatus, tId);
        }
    }

    @Override
    public void deleteUser(String pId) {
        update("DELETE FROM User WHERE id = ?", DatabaseIds.check(pId));
    }

    // ── Markets ──────────────────────────────────────────────────────────

    private static MarketEntry toMarket(ResultSet rs) throws SQLException {
        return new MarketEntry(rs.getInt("marketId"), str(rs, "name"), str(rs, "description"), str(rs, "type"),
                str(rs, "open"), str(rs, "close"), rs.getInt("inverse") != 0,
                str(rs, "preOpenTime"), str(rs, "openAuctionTime"),
                str(rs, "auctionInterval"), str(rs, "randomAuctionInterval"), str(rs, "tradingStatus"));
    }

    @Override
    public List<Stored<MarketEntry>> listMarkets() {
        return query("SELECT * FROM Markets ORDER BY rowid", stored(SqliteDatabase::toMarket));
    }

    @Override
    public Stored<MarketEntry> getMarket(String pId) {
        return queryOne("SELECT * FROM Markets WHERE id = ?", stored(SqliteDatabase::toMarket), DatabaseIds.check(pId));
    }

    @Override
    public String createMarket(MarketEntry m) {
        String tId = DatabaseIds.newId();
        update("""
                INSERT INTO Markets (id, marketId, name, description, type, open, close, inverse, preOpenTime,
                    openAuctionTime, auctionInterval, randomAuctionInterval, tradingStatus)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                tId, m.marketId(), m.name(), m.description(), m.type(), m.open(), m.close(), m.inverse(),
                m.preOpenTime(), m.openAuctionTime(), m.auctionInterval(), m.randomAuctionInterval(), m.tradingStatus());
        return tId;
    }

    @Override
    public void updateMarket(String pId, MarketEntry m) {
        String tId = DatabaseIds.check(pId);
        update("""
                UPDATE Markets SET name = CASE WHEN ? = '' THEN name ELSE ? END, description = ?, type = ?, open = ?,
                    close = ?, inverse = ?, preOpenTime = ?, openAuctionTime = ?, auctionInterval = ?,
                    randomAuctionInterval = ?, tradingStatus = ?
                WHERE id = ?""",
                m.name(), m.name(), m.description(), m.type(), m.open(), m.close(), m.inverse(), m.preOpenTime(),
                m.openAuctionTime(), m.auctionInterval(), m.randomAuctionInterval(), m.tradingStatus(), tId);
    }

    @Override
    public MarketEntry setMarketStatus(int pMarketId, String pStatus) {
        return transaction(() -> {
            if (update("UPDATE Markets SET tradingStatus = ? WHERE marketId = ?", pStatus, pMarketId) == 0) return null;
            return queryOne("SELECT * FROM Markets WHERE marketId = ? LIMIT 1", SqliteDatabase::toMarket, pMarketId);
        });
    }

    @Override
    public void deleteMarket(String pId) {
        update("DELETE FROM Markets WHERE id = ?", DatabaseIds.check(pId));
    }

    // ── Bulletin markets ─────────────────────────────────────────────────

    private static BulletinMarket toBulletinMarket(ResultSet rs) throws SQLException {
        List<String> tInstruments = GSON.fromJson(rs.getString("instruments"), new TypeToken<List<String>>() {}.getType());
        return BulletinMarket.of(rs.getInt("marketId"), str(rs, "name"), str(rs, "description"), str(rs, "tradingStatus"),
                str(rs, "open"), str(rs, "close"), (tInstruments != null) ? tInstruments : List.of(), str(rs, "maxOrderTime"));
    }

    @Override
    public List<Stored<BulletinMarket>> listBulletinMarkets() {
        return query("SELECT * FROM BulletinMarkets ORDER BY rowid", stored(SqliteDatabase::toBulletinMarket));
    }

    @Override
    public Stored<BulletinMarket> getBulletinMarket(String pId) {
        return queryOne("SELECT * FROM BulletinMarkets WHERE id = ?", stored(SqliteDatabase::toBulletinMarket),
                DatabaseIds.check(pId));
    }

    @Override
    public String createBulletinMarket(BulletinMarket m) {
        String tId = DatabaseIds.newId();
        update("""
                INSERT INTO BulletinMarkets (id, marketId, name, description, tradingStatus, open, close, instruments,
                    maxOrderTime)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                tId, m.marketId(), m.name(), m.description(), m.tradingStatus(), m.open(), m.close(),
                GSON.toJson(m.instruments()), m.maxOrderTime());
        return tId;
    }

    @Override
    public void updateBulletinMarket(String pId, BulletinMarket m) {
        update("""
                UPDATE BulletinMarkets SET description = ?, tradingStatus = ?, open = ?, close = ?, instruments = ?,
                    maxOrderTime = ?
                WHERE id = ?""",
                m.description(), m.tradingStatus(), m.open(), m.close(), GSON.toJson(m.instruments()), m.maxOrderTime(),
                DatabaseIds.check(pId));
    }

    @Override
    public BulletinMarket setBulletinMarketStatus(int pMarketId, String pStatus) {
        return transaction(() -> {
            if (update("UPDATE BulletinMarkets SET tradingStatus = ? WHERE marketId = ?", pStatus, pMarketId) == 0) return null;
            return queryOne("SELECT * FROM BulletinMarkets WHERE marketId = ? LIMIT 1", SqliteDatabase::toBulletinMarket, pMarketId);
        });
    }

    @Override
    public void deleteBulletinMarket(String pId) {
        update("DELETE FROM BulletinMarkets WHERE id = ?", DatabaseIds.check(pId));
    }

    // ── Bulletins ────────────────────────────────────────────────────────

    @Override
    public void saveBulletin(StoredBulletin b) {
        update("""
                INSERT OR REPLACE INTO Bulletins (bulletinId, marketId, instrument, side, price, quantity,
                    remainingQuantity, ownerFirm, ownerUser, userRef, clientAccount, createTime, expiresAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                b.bulletinId(), b.marketId(), b.instrument(), b.side(), b.price(), b.quantity(), b.remainingQuantity(),
                b.ownerFirm(), b.ownerUser(), b.userRef(), b.clientAccount(), b.createTime(), b.expiresAt());
    }

    @Override
    public void deleteBulletin(String pBulletinId) {
        update("DELETE FROM Bulletins WHERE bulletinId = ?", pBulletinId);
    }

    @Override
    public List<StoredBulletin> listBulletins() {
        return query("SELECT * FROM Bulletins ORDER BY createTime", rs -> new StoredBulletin(rs.getString("bulletinId"),
                rs.getInt("marketId"), rs.getString("instrument"), rs.getString("side"),
                rs.getLong("price"), rs.getLong("quantity"), rs.getLong("remainingQuantity"),
                rs.getString("ownerFirm"), rs.getString("ownerUser"), rs.getString("userRef"), rs.getString("clientAccount"),
                rs.getLong("createTime"), rs.getLong("expiresAt")));
    }

    // ── Instruments ──────────────────────────────────────────────────────

    private static InstrumentEntry toInstrument(ResultSet rs) throws SQLException {
        long tInstrumentId;
        try { tInstrumentId = Long.parseLong(str(rs, "instrumentId")); }
        catch (NumberFormatException e) { return null; }
        return new InstrumentEntry(tInstrumentId, rs.getInt("marketId"), str(rs, "name"), str(rs, "instType"),
                rs.getLong("tickSize"), rs.getLong("lotSize"), str(rs, "isin"), str(rs, "tradingStatus"),
                str(rs, "expirationDate"), rs.getLong("strikePrice"), str(rs, "underlying"), str(rs, "optionType"));
    }

    @Override
    public List<Stored<InstrumentEntry>> listInstrumentsByMarket(int pMarketId) {
        return query("SELECT * FROM Instruments WHERE marketId = ? ORDER BY rowid", stored(SqliteDatabase::toInstrument), pMarketId);
    }

    @Override
    public Stored<InstrumentEntry> getInstrument(String pId) {
        return queryOne("SELECT * FROM Instruments WHERE id = ?", stored(SqliteDatabase::toInstrument), DatabaseIds.check(pId));
    }

    @Override
    public String createInstrument(InstrumentEntry i) {
        String tId = DatabaseIds.newId();
        update("""
                INSERT INTO Instruments (id, instrumentId, marketId, name, instType, tickSize, lotSize, tradingStatus,
                    isin, expirationDate, strikePrice, underlying, optionType)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                tId, String.valueOf(i.instrumentId()), i.marketId(), i.name(), i.instType(), i.tickSize(), i.lotSize(),
                i.tradingStatus(), i.isin(), i.expirationDate(), i.strikePrice(), i.underlying(), i.optionType());
        return tId;
    }

    @Override
    public void updateInstrument(String pId, InstrumentEntry i) {
        String tId = DatabaseIds.check(pId);
        update("""
                UPDATE Instruments SET name = CASE WHEN ? = '' THEN name ELSE ? END, marketId = ?, instType = ?,
                    tickSize = ?, lotSize = ?, isin = ?, expirationDate = ?, strikePrice = ?, underlying = ?, optionType = ?
                WHERE id = ?""",
                i.name(), i.name(), i.marketId(), i.instType(), i.tickSize(), i.lotSize(), i.isin(), i.expirationDate(),
                i.strikePrice(), i.underlying(), i.optionType(), tId);
    }

    @Override
    public void updateInstrumentStatus(String pId, String pStatus) {
        update("UPDATE Instruments SET tradingStatus = ? WHERE id = ?", pStatus, DatabaseIds.check(pId));
    }

    @Override
    public void deleteInstrument(String pId) {
        update("DELETE FROM Instruments WHERE id = ?", DatabaseIds.check(pId));
    }

    @Override
    public boolean deleteInstrumentByInstrumentId(long pInstrumentId) {
        // One row, like MongoDB's deleteOne
        return update("DELETE FROM Instruments WHERE rowid = (SELECT rowid FROM Instruments WHERE instrumentId = ? LIMIT 1)",
                String.valueOf(pInstrumentId)) > 0;
    }

    // ── Authorizations ───────────────────────────────────────────────────

    private static Authorization toAuthorization(ResultSet rs) throws SQLException {
        return new Authorization(rs.getString("firm"), rs.getString("market"));
    }

    @Override
    public List<Stored<Authorization>> listAuthorizationsByFirm(String pFirm) {
        return query("SELECT * FROM Authorization WHERE firm = ? ORDER BY rowid", stored(SqliteDatabase::toAuthorization), pFirm);
    }

    @Override
    public List<Authorization> listAuthorizations() {
        return query("SELECT * FROM Authorization ORDER BY rowid", SqliteDatabase::toAuthorization);
    }

    @Override
    public boolean authorizationExists(String pFirm, String pMarket) {
        return queryOne("SELECT id FROM Authorization WHERE firm = ? AND market = ? LIMIT 1", rs -> rs.getString(1),
                pFirm, pMarket) != null;
    }

    @Override
    public String createAuthorization(String pFirm, String pMarket) {
        String tId = DatabaseIds.newId();
        update("INSERT INTO Authorization (id, firm, market) VALUES (?, ?, ?)",
                tId, Database.orEmpty(pFirm), Database.orEmpty(pMarket));
        return tId;
    }

    @Override
    public void deleteAuthorization(String pId) {
        update("DELETE FROM Authorization WHERE id = ?", DatabaseIds.check(pId));
    }

    // ── Trades ───────────────────────────────────────────────────────────

    @Override
    public void saveTrades(List<InternalTrade> pTrades) {
        transaction(() -> {
            try (PreparedStatement tStmt = mConnection.prepareStatement("""
                    INSERT INTO Trades (tradeNo, marketId, instrumentId, price, quantity, tradeTime,
                        buyOrderId, buyAccount, buyUserRef, buyClientAccount,
                        sellOrderId, sellAccount, sellUserRef, sellClientAccount, buyUser, sellUser, reversalOf)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (InternalTrade t : pTrades) {
                    Order tBuy  = t.getBuyOrder();
                    Order tSell = t.getSellOrder();
                    bind(tStmt, Long.toHexString(t.getTradeNo()), (int) (t.getInstrumentId() >>> 32),
                            String.valueOf(t.getInstrumentId()), t.getPrice(), t.getQuantity(),
                            Database.formatTradeTime(t.getTradeTime()),
                            Long.toHexString(tBuy.getOrderId()),  tBuy.getAccount(),  tBuy.getUserRef(),  tBuy.getClientAccount(),
                            Long.toHexString(tSell.getOrderId()), tSell.getAccount(), tSell.getUserRef(), tSell.getClientAccount(),
                            tBuy.getUser(), tSell.getUser(), t.getReversalOf());
                    tStmt.addBatch();
                }
                tStmt.executeBatch();
            }
            return null;
        });
    }

    private static InternalTrade toTrade(ResultSet rs) throws SQLException {
        return new InternalTrade(
                Long.parseUnsignedLong(rs.getString("tradeNo"), 16),
                Database.parseTradeTime(rs.getString("tradeTime")),
                Long.parseLong(rs.getString("instrumentId")),
                rs.getLong("price"),
                rs.getLong("quantity"),
                Long.parseUnsignedLong(rs.getString("buyOrderId"), 16),
                rs.getString("buyAccount"), rs.getString("buyUserRef"), rs.getString("buyClientAccount"), rs.getString("buyUser"),
                Long.parseUnsignedLong(rs.getString("sellOrderId"), 16),
                rs.getString("sellAccount"), rs.getString("sellUserRef"), rs.getString("sellClientAccount"), rs.getString("sellUser"),
                rs.getString("reversalOf"));
    }

    @Override
    public List<InternalTrade> listTradesByDate(String pDate) {
        String[] tDay = Database.dayRange(pDate);
        return query("SELECT * FROM Trades WHERE tradeTime BETWEEN ? AND ? ORDER BY tradeTime DESC, seq DESC",
                SqliteDatabase::toTrade, tDay[0], tDay[1]);
    }

    @Override
    public List<InternalTrade> listTradesByFirm(String pFirm, String pDate) {
        return query(firmQuery("Trades", pDate), SqliteDatabase::toTrade, firmParams(pFirm, pDate));
    }

    /** The firm's trades in the table on the day (all days if pDate is null), latest first. */
    private static String firmQuery(String pTable, String pDate) {
        return "SELECT * FROM " + pTable + " WHERE (buyAccount = ? OR sellAccount = ?)"
                + ((pDate != null) ? " AND tradeTime BETWEEN ? AND ?" : "") + " ORDER BY tradeTime DESC, seq DESC";
    }

    private static Object[] firmParams(String pFirm, String pDate) {
        if (pDate == null) return new Object[] { pFirm, pFirm };
        String[] tDay = Database.dayRange(pDate);
        return new Object[] { pFirm, pFirm, tDay[0], tDay[1] };
    }

    @Override
    public long lastTradePrice(long pInstrumentId) {
        Long tPrice = queryOne("SELECT price FROM Trades WHERE instrumentId = ? AND reversalOf IS NULL ORDER BY tradeTime DESC, seq DESC LIMIT 1",
                rs -> rs.getLong(1), String.valueOf(pInstrumentId));
        return (tPrice != null) ? tPrice : 0L;
    }

    @Override
    public List<InternalTrade> listReversalTrades() {
        return query("SELECT * FROM Trades WHERE reversalOf IS NOT NULL ORDER BY tradeTime DESC, seq DESC", SqliteDatabase::toTrade);
    }

    // ── Bulletin trades ──────────────────────────────────────────────────

    @Override
    public void saveBulletinTrade(BulletinTrade t) {
        update("""
                INSERT INTO BulletinTrades (tradeNo, bulletinId, marketId, instrument, price, quantity, tradeTime,
                    buyAccount, buyUser, buyRef, buyClientAccount, sellAccount, sellUser, sellRef, sellClientAccount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                t.tradeNo(), t.bulletinId(), t.marketId(), t.instrument(), t.price(), t.quantity(),
                Database.formatTradeTime(t.tradeTime()),
                t.buyAccount(),  t.buyUser(),  t.buyRef(),  t.buyClientAccount(),
                t.sellAccount(), t.sellUser(), t.sellRef(), t.sellClientAccount());
    }

    @Override
    public List<BulletinTrade> listBulletinTradesByFirm(String pFirm, String pDate) {
        return query(firmQuery("BulletinTrades", pDate), rs -> new BulletinTrade(rs.getString("tradeNo"),
                rs.getString("bulletinId"), rs.getInt("marketId"), rs.getString("instrument"),
                rs.getLong("price"), rs.getLong("quantity"), Database.parseTradeTime(rs.getString("tradeTime")),
                rs.getString("buyAccount"),  rs.getString("buyUser"),  rs.getString("buyRef"),  rs.getString("buyClientAccount"),
                rs.getString("sellAccount"), rs.getString("sellUser"), rs.getString("sellRef"), rs.getString("sellClientAccount")),
                firmParams(pFirm, pDate));
    }

    @Override
    public long lastBulletinTradePrice(int pMarketId, String pInstrument) {
        Long tPrice = queryOne("""
                SELECT price FROM BulletinTrades WHERE marketId = ? AND instrument = ?
                ORDER BY tradeTime DESC, seq DESC LIMIT 1""", rs -> rs.getLong(1), pMarketId, pInstrument);
        return (tPrice != null) ? tPrice : 0L;
    }

    // ── Chat ─────────────────────────────────────────────────────────────

    @Override
    public void saveChatMessage(ChatMessage m) {
        update("INSERT INTO Chat (chatId, time, firm, user, text, tag) VALUES (?, ?, ?, ?, ?, ?)",
                m.chatId(), Database.formatTradeTime(m.time()), m.firm(), m.user(), m.text(), m.tag());
    }

    /** The tag condition is a LIKE, which is case-insensitive for ASCII letters only (MongoDB: all letters). */
    @Override
    public List<ChatMessage> listChatMessages(String pFromDate, String pToDate, String pTag, int pLimit) {
        StringBuilder tSql = new StringBuilder("SELECT * FROM Chat WHERE time <= ?");
        List<Object> tParams = new ArrayList<>(List.of(Database.dayRange(pToDate)[1]));
        if (pFromDate != null) {
            tSql.append(" AND time >= ?");
            tParams.add(Database.dayRange(pFromDate)[0]);
        }
        if (pTag != null && !pTag.isEmpty()) {
            tSql.append(" AND tag LIKE ? ESCAPE '\\'");
            tParams.add("%" + escapeLike(pTag) + "%");
        }
        tSql.append(" ORDER BY time DESC, seq DESC LIMIT ?");
        tParams.add(pLimit);
        List<ChatMessage> tOut = query(tSql.toString(), rs -> new ChatMessage(rs.getString("chatId"),
                Database.parseTradeTime(rs.getString("time")), rs.getString("firm"), rs.getString("user"),
                rs.getString("text"), str(rs, "tag")), tParams.toArray());
        Collections.reverse(tOut);
        return new ArrayList<>(tOut);
    }

    /** The text with LIKE's wildcards (and the escape character) escaped, so it matches literally. */
    static String escapeLike(String pText) {
        return pText.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
