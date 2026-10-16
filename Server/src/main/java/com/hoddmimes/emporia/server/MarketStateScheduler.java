package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.bulletin.BulletinBoard;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.hoddmimes.emporia.server.db.Database;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives each market's tradingStatus from its market hours (server local time): on weekdays
 * PREOPEN at {@code preOpenTime} (if set), OPEN at {@code open} and CLOSED at {@code close}.
 * On Saturdays and Sundays markets stay CLOSED: only the CLOSED transition is applied.
 *
 * A transition happens when its time is passed, so a manual change made in the admin
 * interface stands until the next scheduled time. SUSPENDED is a manual state and is never
 * changed by the scheduler, in any mode. Market hours are read from the cache on every check, so edits and
 * new markets take effect without a restart.
 *
 * At startup (normal mode) a market that is OPEN or PREOPEN is set to CLOSED and has to be opened
 * manually (or waits for its next scheduled transition).
 * Test mode overrides the scheduler: every market that is not SUSPENDED is set to OPEN at
 * startup and no market hours are applied.
 *
 * The bulletin markets (held by the BulletinBoard, not the cache) follow the same rules with their
 * open and close times (they have no pre-open); a SUSPENDED bulletin market is never changed.
 */
public class MarketStateScheduler {

    private static final Logger cLog = LogManager.getLogger(MarketStateScheduler.class);

    public static final String OPEN      = "OPEN";
    public static final String PREOPEN   = "PREOPEN";
    public static final String CLOSED    = "CLOSED";
    public static final String SUSPENDED = "SUSPENDED";

    private static final long              CHECK_INTERVAL_MS = 1000L;
    private static final DateTimeFormatter TIME_FORMAT       = DateTimeFormatter.ofPattern("H:mm[:ss]");

    private final Database                 mDatabase;
    private final InstrumentCache          mInstrumentCache;
    private final BulletinBoard            mBulletinBoard;
    private final ScheduledExecutorService mScheduler;
    private LocalDateTime                  mLastCheck;

    public MarketStateScheduler(Database pDatabase, InstrumentCache pInstrumentCache, BulletinBoard pBulletinBoard) {
        mDatabase        = pDatabase;
        mInstrumentCache = pInstrumentCache;
        mBulletinBoard   = pBulletinBoard;
        mScheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MarketStateScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Stops checking the market hours (web application undeploy). */
    public void stop() {
        mScheduler.shutdownNow();
    }

    /** Applies the startup rule for the given mode and starts checking the market hours. */
    public void start(boolean pTestMode) {
        if (pTestMode) {
            for (InstrumentCache.MarketEntry tMarket : mInstrumentCache.getAllMarkets()) {
                setStatus(tMarket, OPEN, "test mode");
            }
            for (BulletinMarket tMarket : mBulletinBoard.getMarkets()) {
                setStatus(tMarket, OPEN, "test mode");
            }
            cLog.info("Market state scheduler disabled (test mode): markets set to OPEN (SUSPENDED kept), market hours not applied");
            return;
        }
        for (InstrumentCache.MarketEntry tMarket : mInstrumentCache.getAllMarkets()) {
            if (!hasValidHours(tMarket)) {
                cLog.warn("Market {} ({}) has no valid market hours (open='{}' close='{}'), its state is not scheduled",
                        tMarket.marketId(), tMarket.name(), tMarket.open(), tMarket.close());
            }
            if (OPEN.equals(tMarket.tradingStatus()) || PREOPEN.equals(tMarket.tradingStatus())) {
                setStatus(tMarket, CLOSED, "server startup, must be opened manually");
            }
        }
        for (BulletinMarket tMarket : mBulletinBoard.getMarkets()) {
            if (!hasValidHours(tMarket.open(), tMarket.close())) {
                cLog.warn("Bulletin market {} ({}) has no valid market hours (open='{}' close='{}'), its state is not scheduled",
                        tMarket.marketId(), tMarket.name(), tMarket.open(), tMarket.close());
            }
            if (OPEN.equals(tMarket.tradingStatus())) {
                setStatus(tMarket, CLOSED, "server startup, must be opened manually");
            }
        }
        mLastCheck = LocalDateTime.now();
        mScheduler.scheduleAtFixedRate(this::check, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        cLog.info("Market state scheduler started");
    }

    private void check() {
        try {
            LocalDateTime tNow = LocalDateTime.now();
            for (InstrumentCache.MarketEntry tMarket : mInstrumentCache.getAllMarkets()) {
                String tState = transitionBetween(tMarket, mLastCheck, tNow);
                if (tState != null) setStatus(tMarket, tState, "market hours");
            }
            for (BulletinMarket tMarket : mBulletinBoard.getMarkets()) {
                String tState = transitionBetween(tMarket.open(), tMarket.close(), null, mLastCheck, tNow);
                if (tState != null) setStatus(tMarket, tState, "market hours");
            }
            mLastCheck = tNow;
        } catch (Exception e) {
            cLog.error("Market state check failed", e);
        }
    }

    /**
     * Writes the status to the database and the cache (which notifies the matching engine).
     * A SUSPENDED market is never changed, in any mode.
     */
    private void setStatus(InstrumentCache.MarketEntry pMarket, String pState, String pReason) {
        if (SUSPENDED.equals(pMarket.tradingStatus()) || pState.equals(pMarket.tradingStatus())) return;
        InstrumentCache.MarketEntry tMarket = mDatabase.setMarketStatus(pMarket.marketId(), pState);
        if (tMarket == null) return;
        mInstrumentCache.putMarket(tMarket);
        cLog.info("Market {} ({}) {} -> {} ({})", pMarket.marketId(), pMarket.name(),
                pMarket.tradingStatus(), pState, pReason);
    }

    /**
     * Writes the status of a bulletin market to the database and the BulletinBoard (which publishes a
     * MarketState). A SUSPENDED bulletin market is never changed, in any mode.
     */
    private void setStatus(BulletinMarket pMarket, String pState, String pReason) {
        if (BulletinMarket.SUSPENDED.equals(pMarket.tradingStatus()) || pState.equals(pMarket.tradingStatus())) return;
        BulletinMarket tMarket = mDatabase.setBulletinMarketStatus(pMarket.marketId(), pState);
        if (tMarket == null) return;
        mBulletinBoard.putMarket(tMarket);
        cLog.info("Bulletin market {} ({}) {} -> {} ({})", pMarket.marketId(), pMarket.name(),
                pMarket.tradingStatus(), pState, pReason);
    }

    // ── Market hours ──────────────────────────────────────────────────────

    static boolean hasValidHours(InstrumentCache.MarketEntry pMarket) {
        return hasValidHours(pMarket.open(), pMarket.close());
    }

    static boolean hasValidHours(String pOpen, String pClose) {
        return parseTime(pOpen) != null && parseTime(pClose) != null;
    }

    /** True if the time is "H:mm" or "H:mm:ss". */
    public static boolean isValidTime(String pTime) {
        return parseTime(pTime) != null;
    }

    /**
     * The state set by the latest scheduled transition in the interval (pFrom, pTo], or null if
     * no transition time was passed. Handles intervals that cross midnight.
     */
    static String transitionBetween(InstrumentCache.MarketEntry pMarket, LocalDateTime pFrom, LocalDateTime pTo) {
        return transitionBetween(pMarket.open(), pMarket.close(), pMarket.preOpenTime(), pFrom, pTo);
    }

    /** As above, for the given open, close and (optional, null or empty if none) pre-open times. */
    static String transitionBetween(String pOpenTime, String pCloseTime, String pPreOpenTime,
                                    LocalDateTime pFrom, LocalDateTime pTo) {
        LocalTime tOpen    = parseTime(pOpenTime);
        LocalTime tClose   = parseTime(pCloseTime);
        LocalTime tPreOpen = parseTime(pPreOpenTime);
        if (tOpen == null || tClose == null) return null;

        Set<LocalDate> tDates = new TreeSet<>(List.of(pFrom.toLocalDate(), pTo.toLocalDate()));
        LocalDateTime tLatest = null;
        String        tState  = null;
        for (LocalDate tDate : tDates) {
            // Markets do not open on weekends; only the close applies
            boolean         tOpens  = !isWeekend(tDate);
            LocalDateTime[] tTimes  = { tOpens && tPreOpen != null ? tDate.atTime(tPreOpen) : null,
                                        tOpens ? tDate.atTime(tOpen) : null,
                                        tDate.atTime(tClose) };
            String[]        tStates = { PREOPEN, OPEN, CLOSED };
            for (int i = 0; i < tTimes.length; i++) {
                LocalDateTime t = tTimes[i];
                if (t == null || !t.isAfter(pFrom) || t.isAfter(pTo)) continue;
                if (tLatest == null || !t.isBefore(tLatest)) { tLatest = t; tState = tStates[i]; }
            }
        }
        return tState;
    }

    static boolean isWeekend(LocalDate pDate) {
        return pDate.getDayOfWeek() == DayOfWeek.SATURDAY || pDate.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** Parses "H:mm" or "H:mm:ss"; null if empty or invalid. */
    private static LocalTime parseTime(String pTime) {
        if (pTime == null || pTime.isBlank()) return null;
        try { return LocalTime.parse(pTime.trim(), TIME_FORMAT); }
        catch (Exception e) { return null; }
    }
}
