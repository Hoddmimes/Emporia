package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.db.BulletinTrade;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.me.InternalTrade;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The firms' positions in memory, so GET /api/portfolio reads no trades from the database. A position per
 * instrument and client account is the sum of all the firm's stored trades in it, of every day (bought
 * quantity minus sold quantity); a trade between two orders of the firm itself counts on both sides, so
 * it nets 0 within one client account and moves the position between two different ones. Bulletin trades
 * give positions of their own per bulletin market, free text instrument and client account. Reversal
 * trades count like any other trade (they have the sides swapped), so a reversed trade nets out.
 * Besides the positions it keeps the price of each instrument's latest stored trade (and of each bulletin
 * market's instrument text), reversals not counted, as Database.lastTradePrice/lastBulletinTradePrice.
 *
 * Loaded at startup from all stored trades (load), then kept current by the matching engine's
 * TradePersister and the BulletinBoard, which add each trade once it is stored (so a position never
 * includes a trade that is not on disk); the trades in the database stay the master store, and a trade
 * changed directly in the database applies only after a restart. Thread-safe: the methods are synchronized.
 */
public class PositionCache {

    private static final Logger cLog = LogManager.getLogger(PositionCache.class);

    /** A position in an instrument and client account: the quantities bought and sold (scaled), position = bought - sold. */
    public record Position(long instrumentId, int marketId, String clientAccount, long bought, long sold) {
        public long position() { return bought - sold; }
    }

    /** A position in a bulletin market's free text instrument and a client account. */
    public record BulletinPosition(int marketId, String instrument, String clientAccount, long bought, long sold) {
        public long position() { return bought - sold; }
    }

    // firm -> key -> position, in the order the positions first appeared
    private final Map<String, Map<List<Object>, Position>>         mPositions         = new HashMap<>();   // key: instrument id, client account
    private final Map<String, Map<List<Object>, BulletinPosition>> mBulletinPositions = new HashMap<>();   // key: market, instrument, client account
    private final Map<Long, Long>                                  mLastPrice         = new HashMap<>();   // instrument id -> price
    private final Map<List<Object>, Long>                          mLastBulletinPrice = new HashMap<>();   // (market, instrument) -> price

    /** Reads all stored trades and bulletin trades, oldest first, replacing what is held. */
    public synchronized void load(Database pDatabase) {
        long tStart = System.currentTimeMillis();
        mPositions.clear();
        mBulletinPositions.clear();
        mLastPrice.clear();
        mLastBulletinPrice.clear();
        long[] tCount = new long[2];
        pDatabase.forEachTrade(t -> { addTrade(t); tCount[0]++; });
        pDatabase.forEachBulletinTrade(t -> { addBulletinTrade(t); tCount[1]++; });
        cLog.info("Positions loaded from {} trade(s) and {} bulletin trade(s) in {} ms", tCount[0], tCount[1],
                System.currentTimeMillis() - tStart);
    }

    /** Adds stored trades, in the order they were made. */
    public synchronized void addTrades(List<InternalTrade> pTrades) {
        for (InternalTrade tTrade : pTrades) addTrade(tTrade);
    }

    public synchronized void addTrade(InternalTrade pTrade) {
        long tInstrumentId = pTrade.getInstrumentId();
        long tQuantity     = pTrade.getQuantity();
        add(pTrade.getBuyOrder().getAccount(),  tInstrumentId, pTrade.getBuyOrder().getClientAccount(),  tQuantity, 0L);
        add(pTrade.getSellOrder().getAccount(), tInstrumentId, pTrade.getSellOrder().getClientAccount(), 0L, tQuantity);
        if (!pTrade.isReversal()) mLastPrice.put(tInstrumentId, pTrade.getPrice());
    }

    private void add(String pFirm, long pInstrumentId, String pClientAccount, long pBought, long pSold) {
        mPositions.computeIfAbsent(pFirm, f -> new LinkedHashMap<>()).merge(List.of(pInstrumentId, pClientAccount),
                new Position(pInstrumentId, (int) (pInstrumentId >>> 32), pClientAccount, pBought, pSold),
                (a, b) -> new Position(a.instrumentId(), a.marketId(), a.clientAccount(), a.bought() + b.bought(), a.sold() + b.sold()));
    }

    /** Adds a stored bulletin trade. */
    public synchronized void addBulletinTrade(BulletinTrade pTrade) {
        addBulletin(pTrade.buyAccount(),  pTrade, pTrade.buyClientAccount(),  pTrade.quantity(), 0L);
        addBulletin(pTrade.sellAccount(), pTrade, pTrade.sellClientAccount(), 0L, pTrade.quantity());
        if (!pTrade.isReversal()) mLastBulletinPrice.put(List.of(pTrade.marketId(), pTrade.instrument()), pTrade.price());
    }

    private void addBulletin(String pFirm, BulletinTrade pTrade, String pClientAccount, long pBought, long pSold) {
        mBulletinPositions.computeIfAbsent(pFirm, f -> new LinkedHashMap<>()).merge(List.of(pTrade.marketId(), pTrade.instrument(), pClientAccount),
                new BulletinPosition(pTrade.marketId(), pTrade.instrument(), pClientAccount, pBought, pSold),
                (a, b) -> new BulletinPosition(a.marketId(), a.instrument(), a.clientAccount(), a.bought() + b.bought(), a.sold() + b.sold()));
    }

    /** The firm's positions, in the order they first appeared, positions of 0 included. */
    public synchronized List<Position> positions(String pFirm) {
        Map<List<Object>, Position> tPositions = mPositions.get(pFirm);
        return (tPositions != null) ? new ArrayList<>(tPositions.values()) : List.of();
    }

    /** The firm's bulletin positions, in the order they first appeared, positions of 0 included. */
    public synchronized List<BulletinPosition> bulletinPositions(String pFirm) {
        Map<List<Object>, BulletinPosition> tPositions = mBulletinPositions.get(pFirm);
        return (tPositions != null) ? new ArrayList<>(tPositions.values()) : List.of();
    }

    /** The price of the instrument's latest stored trade, reversals not counted; 0 if it never traded. */
    public synchronized long lastTradePrice(long pInstrumentId) {
        return mLastPrice.getOrDefault(pInstrumentId, 0L);
    }

    /** The price of the latest bulletin trade in the instrument text of the bulletin market, reversals not counted; 0 if none. */
    public synchronized long lastBulletinTradePrice(int pMarketId, String pInstrument) {
        return mLastBulletinPrice.getOrDefault(List.of(pMarketId, pInstrument), 0L);
    }
}
