package com.hoddmimes.emporia.server;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.db.Stored;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory cache of all markets and instruments, loaded from the database at startup.
 * The database stays the master: every change is written to the database first and then
 * applied here (see the admin endpoints). Subscribers are notified of each change.
 * Thread-safe: backed by ConcurrentHashMap.
 */
public class InstrumentCache {

    private static final Logger cLog = LogManager.getLogger(InstrumentCache.class);

    public interface MarketCallbacks {
        void marketStateChange(int pMarketId, String pMarketName, String pState);
        void marketAdded(JsonObject pMarket);
        void marketUpdated(JsonObject pMarket);
    }

    public interface InstrumentCallbacks {
        void instrumentStateChanged(long pInstrumentId, String pInstrumentName, String pState);
        void instrumentAdded(JsonObject pInstrument);
        void instrumentUpdated(JsonObject pInstrument);
    }

    // ── Value types ───────────────────────────────────────────────────────

    public record MarketEntry(int marketId, String name, String description, String type,
                              String open, String close, boolean inverse,
                              String preOpenTime, String openAuctionTime,
                              String auctionInterval, String randomAuctionInterval,
                              String tradingStatus) {


        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("id",                    marketId);
            j.addProperty("name",                  name);
            j.addProperty("description",           description);
            j.addProperty("type",                  type);
            j.addProperty("open",                  open);
            j.addProperty("close",                 close);
            j.addProperty("inverse",               inverse);
            j.addProperty("preOpenTime",           preOpenTime);
            j.addProperty("openAuctionTime",       openAuctionTime);
            j.addProperty("auctionInterval",       auctionInterval);
            j.addProperty("randomAuctionInterval", randomAuctionInterval);
            j.addProperty("tradingStatus",         tradingStatus);
            return j;
        }
    }

    public record InstrumentEntry(long instrumentId, int marketId, String name, String instType,
                                  long tickSize, long lotSize, String isin, String tradingStatus,
                                  String expirationDate, long strikePrice,
                                  String underlying, String optionType) {


        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("id",             instrumentId);
            j.addProperty("marketId",       marketId);
            j.addProperty("name",           name);
            j.addProperty("instType",       instType);
            j.addProperty("tickSize",       tickSize);
            j.addProperty("lotSize",        lotSize);
            j.addProperty("isin",           isin);
            j.addProperty("tradingStatus",  tradingStatus);
            j.addProperty("expirationDate", expirationDate);
            j.addProperty("strikePrice",    strikePrice);
            j.addProperty("underlying",     underlying);
            j.addProperty("optionType",     optionType);
            return j;
        }
    }


    // ── State ─────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<Integer, MarketEntry>  mMarkets     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, InstrumentEntry> mInstruments = new ConcurrentHashMap<>();

    private final List<MarketCallbacks>     mMarketSubscriptions     = new CopyOnWriteArrayList<>();
    private final List<InstrumentCallbacks> mInstrumentSubscriptions = new CopyOnWriteArrayList<>();

    // ── Load ──────────────────────────────────────────────────────────────

    /** Loads all markets and instruments from the database. Called once at startup. */
    public synchronized void load(Database pDatabase) {
        mMarkets.clear();
        for (Stored<MarketEntry> tMarket : pDatabase.listMarkets()) {
            mMarkets.put(tMarket.value().marketId(), tMarket.value());
        }
        mInstruments.clear();
        for (int tMarketId : mMarkets.keySet()) {
            for (Stored<InstrumentEntry> tInstrument : pDatabase.listInstrumentsByMarket(tMarketId)) {
                mInstruments.put(tInstrument.value().instrumentId(), tInstrument.value());
            }
        }
        cLog.info("InstrumentCache: {} markets and {} instruments loaded", mMarkets.size(), mInstruments.size());
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    public MarketEntry getMarket(int pMarketId) {
        return mMarkets.get(pMarketId);
    }

    public MarketEntry getMarketByName(String pName) {
        for (MarketEntry tEntry : mMarkets.values()) {
            if (tEntry.name().equals(pName)) return tEntry;
        }
        return null;
    }

    public Collection<MarketEntry> getAllMarkets() {
        return mMarkets.values();
    }

    public InstrumentEntry getInstrument(long pInstrumentId) {
        return mInstruments.get(pInstrumentId);
    }

    public List<InstrumentEntry> getInstrumentsByMarket(int pMarketId) {
        List<InstrumentEntry> tResult = new ArrayList<>();
        for (InstrumentEntry tEntry : mInstruments.values()) {
            if (tEntry.marketId() == pMarketId) tResult.add(tEntry);
        }
        return tResult;
    }

    /** A new market's marketId: the highest existing one + 1. */
    public int nextMarketId() {
        return mMarkets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /**
     * The next instrumentId for the given market.
     * Encoding: high 32 bits = marketId, low 32 bits = per-market sequence (1-based).
     */
    public long nextInstrumentId(int pMarketId) {
        long tMaxSeq = 0L;
        for (long tId : mInstruments.keySet()) {
            if ((int) (tId >>> 32) == pMarketId) tMaxSeq = Math.max(tMaxSeq, tId & 0xFFFFFFFFL);
        }
        return ((long) pMarketId << 32) | (tMaxSeq + 1);
    }

    // ── Subscriptions ─────────────────────────────────────────────────────

    public void addMarketSubscription(MarketCallbacks pCallbacks)        { mMarketSubscriptions.add(pCallbacks); }
    public void removeMarketSubscription(MarketCallbacks pCallbacks)     { mMarketSubscriptions.remove(pCallbacks); }
    public void addInstrumentSubscription(InstrumentCallbacks pCallbacks)    { mInstrumentSubscriptions.add(pCallbacks); }
    public void removeInstrumentSubscription(InstrumentCallbacks pCallbacks) { mInstrumentSubscriptions.remove(pCallbacks); }

    // ── Mutators (call after the change is written to the database) ──────

    /** Adds or replaces the market (as read back from the database) and notifies subscribers; null is ignored. */
    public void putMarket(MarketEntry pEntry) {
        if (pEntry == null) return;
        MarketEntry tEntry = pEntry;
        MarketEntry tPrev = mMarkets.put(tEntry.marketId(), tEntry);
        for (MarketCallbacks tCb : mMarketSubscriptions) {
            if (tPrev == null) {
                tCb.marketAdded(tEntry.toJson());
            } else if (!tPrev.tradingStatus().equals(tEntry.tradingStatus())) {
                tCb.marketStateChange(tEntry.marketId(), tEntry.name(), tEntry.tradingStatus());
            } else {
                tCb.marketUpdated(tEntry.toJson());
            }
        }
    }

    public void removeMarket(int pMarketId) {
        mMarkets.remove(pMarketId);
    }

    /** Adds or replaces the instrument (as read back from the database) and notifies subscribers; null is ignored. */
    public void putInstrument(InstrumentEntry pEntry) {
        if (pEntry == null) return;
        InstrumentEntry tEntry = pEntry;
        InstrumentEntry tPrev = mInstruments.put(tEntry.instrumentId(), tEntry);
        for (InstrumentCallbacks tCb : mInstrumentSubscriptions) {
            if (tPrev == null) {
                tCb.instrumentAdded(tEntry.toJson());
            } else if (!tPrev.tradingStatus().equals(tEntry.tradingStatus())) {
                tCb.instrumentStateChanged(tEntry.instrumentId(), tEntry.name(), tEntry.tradingStatus());
            } else {
                tCb.instrumentUpdated(tEntry.toJson());
            }
        }
    }

    public void removeInstrument(long pInstrumentId) {
        mInstruments.remove(pInstrumentId);
    }
}
