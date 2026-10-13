package com.hoddmimes.emporia.server.me;

import com.hoddmimes.emporia.server.InstrumentCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules periodic auction triggers for every AUCTION-type market.
 *
 * <p>Timing model per market:
 * <ol>
 *   <li>A period starts with a nominal duration D (initially {@code auctionInterval}).</li>
 *   <li>After D − R seconds (R = {@code randomAuctionInterval}) the engine picks a random
 *       offset [0, R) and fires the auction that many seconds later.</li>
 *   <li>The unused window (R − offset) carries forward: next period D' = auctionInterval + (R − offset).</li>
 *   <li>Scheduling stops when the remaining time until close is less than one safe window.</li>
 * </ol>
 *
 * <p>Time formats:
 * <ul>
 *   <li>{@code open} / {@code close} — "HH:mm" wall-clock time</li>
 *   <li>{@code auctionInterval} / {@code randomAuctionInterval} — "MM:ss" duration, or plain seconds</li>
 * </ul>
 *
 * <p>Markets and instruments are taken from the cache when {@link #start()} runs, so markets
 * or instruments added later are not scheduled until the server is restarted.
 */
public class AuctionScheduler {

    private static final Logger cLog = LogManager.getLogger(AuctionScheduler.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final MatchingEngine           mMatchingEngine;
    private final InstrumentCache          mInstrumentCache;
    private final ScheduledExecutorService mScheduler;
    private final Random                   mRandom = new Random();

    public AuctionScheduler(MatchingEngine pMatchingEngine, InstrumentCache pInstrumentCache) {
        mMatchingEngine  = pMatchingEngine;
        mInstrumentCache = pInstrumentCache;
        mScheduler       = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> { Thread t = new Thread(r, "AuctionScheduler"); t.setDaemon(true); return t; });
    }

    /** Stops all auction timers (web application undeploy). */
    public void stop() {
        mScheduler.shutdownNow();
    }

    /** Called once at server startup to arm schedulers for all AUCTION markets. */
    public void start() {
        for (InstrumentCache.MarketEntry tMarket : mInstrumentCache.getAllMarkets()) {
            if (!Orderbook.TYPE_AUCTION.equals(tMarket.type())) continue;
            try {
                scheduleMarket(tMarket);
            } catch (Exception e) {
                cLog.error("Failed to schedule auction for market {}: {}", tMarket.marketId(), e.getMessage());
            }
        }
    }

    // ── Per-market initialisation ─────────────────────────────────────────

    private void scheduleMarket(InstrumentCache.MarketEntry pMarket) {
        LocalTime tOpen  = parseWallTime(pMarket.open());
        LocalTime tClose = parseWallTime(pMarket.close());
        if (tOpen == null || tClose == null) {
            cLog.warn("Market {} skipped — cannot parse open='{}' close='{}'",
                    pMarket.marketId(), pMarket.open(), pMarket.close());
            return;
        }

        long tIntervalSec = parseDurationSec(pMarket.auctionInterval());
        long tRandomSec   = parseDurationSec(pMarket.randomAuctionInterval());

        if (tIntervalSec <= 0) {
            cLog.warn("Market {} skipped — auctionInterval must be > 0", pMarket.marketId());
            return;
        }
        if (tRandomSec < 0 || tRandomSec >= tIntervalSec) {
            cLog.warn("Market {} skipped — randomAuctionInterval {}s must be in [0, {})",
                    pMarket.marketId(), tRandomSec, tIntervalSec);
            return;
        }

        // Collect instruments belonging to this market
        List<Long> tInstrumentIds = new ArrayList<>();
        for (InstrumentCache.InstrumentEntry tInst : mInstrumentCache.getInstrumentsByMarket(pMarket.marketId())) {
            tInstrumentIds.add(tInst.instrumentId());
        }
        if (tInstrumentIds.isEmpty()) {
            cLog.info("Market {} has no instruments — auction scheduler idle", pMarket.marketId());
            return;
        }

        // Delay until market opens (0 if already past open)
        LocalTime tNow          = LocalTime.now();
        long tSecsUntilOpen     = tNow.isBefore(tOpen)  ? tNow.until(tOpen,  ChronoUnit.SECONDS) : 0L;

        // Schedule the guaranteed closing auction.
        // It fires at a random moment within [close − randomSec, close).
        long tSecsToClose     = tNow.isBefore(tClose) ? tNow.until(tClose, ChronoUnit.SECONDS) : 0L;
        long tClosingOffset   = (long)(mRandom.nextDouble() * tRandomSec);
        long tClosingFireSec  = Math.max(0L, tSecsToClose - tRandomSec + tClosingOffset);
        scheduleClosingAuction(pMarket.marketId(), tInstrumentIds, tClosingFireSec);

        cLog.info("Auction scheduler armed: market={} open={} close={} interval={}s random={}s instruments={} firstPeriodIn={}s closingAuctionIn={}s",
                pMarket.marketId(), tOpen, tClose, tIntervalSec, tRandomSec,
                tInstrumentIds.size(), tSecsUntilOpen, tClosingFireSec);

        scheduleNextPeriod(pMarket.marketId(), tInstrumentIds, tClose,
                tIntervalSec, tRandomSec,
                tSecsUntilOpen,   // additional delay before this first period begins
                tIntervalSec);    // nominal duration of the first period
    }

    // ── Closing auction ───────────────────────────────────────────────────

    private void scheduleClosingAuction(int pMarketId, List<Long> pInstrumentIds, long pFireAfterSec) {
        mScheduler.schedule(() -> {
            cLog.info("Market {} closing auction firing", pMarketId);
            for (long tInstrumentId : pInstrumentIds) {
                try { mMatchingEngine.triggerAuction(tInstrumentId); }
                catch (Exception e) {
                    cLog.error("Closing auction trigger failed for instrument {}: {}", tInstrumentId, e.getMessage());
                }
            }
        }, pFireAfterSec, TimeUnit.SECONDS);
    }

    // ── Recursive period scheduler ────────────────────────────────────────

    /**
     * @param pMarketId        market to trigger
     * @param pInstrumentIds   instruments in that market
     * @param pClose           wall-clock close time
     * @param pBaseInterval    the constant auctionInterval (used to compute next base period)
     * @param pRandomSec       randomAuctionInterval in seconds
     * @param pLeadDelaySec    extra seconds to wait before the period's own countdown begins
     *                         (non-zero only for the first period, to wait for market open)
     * @param pPeriodDuration  nominal duration of this period (may be > baseInterval due to carry-over)
     */
    private void scheduleNextPeriod(int pMarketId, List<Long> pInstrumentIds,
                                    LocalTime pClose,
                                    long pBaseInterval, long pRandomSec,
                                    long pLeadDelaySec, long pPeriodDuration) {
        // Earliest fire = lead delay + (period duration - random window)
        long tSafeDelay    = pPeriodDuration - pRandomSec;
        long tRandomOffset = (long)(mRandom.nextDouble() * pRandomSec);
        long tFireAfterSec = pLeadDelaySec + tSafeDelay + tRandomOffset;

        mScheduler.schedule(() -> {

            LocalTime tNow = LocalTime.now();

            // Abort if the market is already closed
            if (!tNow.isBefore(pClose)) {
                cLog.info("Market {} auction scheduler: market closed, no more auctions", pMarketId);
                return;
            }

            // Carry-over = the random window that was not consumed
            long tCarryOver  = pRandomSec - tRandomOffset;
            long tNextPeriod = pBaseInterval + tCarryOver;

            cLog.info("Market {} auction firing — randomOffset={}s carry-over={}s nextPeriod={}s",
                    pMarketId, tRandomOffset, tCarryOver, tNextPeriod);

            // Trigger every instrument in this market
            for (long tInstrumentId : pInstrumentIds) {
                try {
                    mMatchingEngine.triggerAuction(tInstrumentId);
                } catch (Exception e) {
                    cLog.error("Auction trigger failed for instrument {}: {}", tInstrumentId, e.getMessage());
                }
            }

            // Stop regular auctions when the remaining time is less than a full next period.
            // The closing [close − randomSec, close) window is handled by the dedicated closing auction task.
            long tSecsToClose = tNow.until(pClose, ChronoUnit.SECONDS);
            if (tSecsToClose < tNextPeriod) {
                cLog.info("Market {} — {}s to close < next period {}s, handing off to closing auction",
                        pMarketId, tSecsToClose, tNextPeriod);
                return;
            }

            // Schedule the next period immediately (no lead delay)
            scheduleNextPeriod(pMarketId, pInstrumentIds, pClose,
                    pBaseInterval, pRandomSec,
                    0L, tNextPeriod);

        }, tFireAfterSec, TimeUnit.SECONDS);
    }

    // ── Parsing helpers ───────────────────────────────────────────────────

    /** Parses wall-clock time in "HH:mm" format. Returns null on failure. */
    private static LocalTime parseWallTime(String pTime) {
        if (pTime == null || pTime.isBlank()) return null;
        try { return LocalTime.parse(pTime.trim(), TIME_FMT); }
        catch (Exception e) { return null; }
    }

    /**
     * Parses a duration to seconds.
     * Accepts "MM:ss" (e.g. "15:00" = 900s, "02:00" = 120s) or plain integer seconds.
     */
    static long parseDurationSec(String pDuration) {
        if (pDuration == null || pDuration.isBlank()) return 0L;
        String tTrimmed = pDuration.trim();
        try {
            if (tTrimmed.contains(":")) {
                String[] tParts = tTrimmed.split(":", 2);
                return Long.parseLong(tParts[0]) * 60L + Long.parseLong(tParts[1]);
            }
            return Long.parseLong(tTrimmed);
        } catch (NumberFormatException e) {
            cLog.warn("Cannot parse duration '{}' — defaulting to 0", pDuration);
            return 0L;
        }
    }
}
