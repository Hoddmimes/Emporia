package com.hoddmimes.emporia.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

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
 * At startup (normal mode) a market that is OPEN is set to CLOSED and has to be opened manually.
 * Test mode overrides the scheduler: every market that is not SUSPENDED is set to OPEN at
 * startup and no market hours are applied.
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
    private final ScheduledExecutorService mScheduler;
    private LocalDateTime                  mLastCheck;

    public MarketStateScheduler(Database pDatabase, InstrumentCache pInstrumentCache) {
        mDatabase        = pDatabase;
        mInstrumentCache = pInstrumentCache;
        mScheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MarketStateScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Applies the startup rule for the given mode and starts checking the market hours. */
    public void start(boolean pTestMode) {
        if (pTestMode) {
            for (InstrumentCache.MarketEntry tMarket : mInstrumentCache.getAllMarkets()) {
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
        Document tDoc = mDatabase.setMarketStatus(pMarket.marketId(), pState);
        if (tDoc == null) return;
        mInstrumentCache.putMarket(tDoc);
        cLog.info("Market {} ({}) {} -> {} ({})", pMarket.marketId(), pMarket.name(),
                pMarket.tradingStatus(), pState, pReason);
    }

    // ── Market hours ──────────────────────────────────────────────────────

    static boolean hasValidHours(InstrumentCache.MarketEntry pMarket) {
        return parseTime(pMarket.open()) != null && parseTime(pMarket.close()) != null;
    }

    /**
     * The state set by the latest scheduled transition in the interval (pFrom, pTo], or null if
     * no transition time was passed. Handles intervals that cross midnight.
     */
    static String transitionBetween(InstrumentCache.MarketEntry pMarket, LocalDateTime pFrom, LocalDateTime pTo) {
        LocalTime tOpen    = parseTime(pMarket.open());
        LocalTime tClose   = parseTime(pMarket.close());
        LocalTime tPreOpen = parseTime(pMarket.preOpenTime());
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
