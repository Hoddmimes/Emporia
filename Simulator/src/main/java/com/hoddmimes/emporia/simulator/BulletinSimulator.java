package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonObject;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Bulletins for one instrument of a bulletin market, one at a time: after a random time between 10 s
 * and timeToCreate seconds a bulletin is added (POST /api/bulletins), and after a random time between
 * 10 s and timeToLive seconds it is withdrawn (DELETE /api/bulletins/{bulletinId}); then the next one
 * is created the same way. The side is the configured one, or random if none is given. The price is
 * the mid price plus or minus a random amount up to maxPriceDeviation (rounded to 0.01), and the quantity
 * random between minVolume and maxVolume (whole units); a bulletin without midPrice has no price, one
 * without minVolume/maxVolume no quantity. A bulletin that is already gone when it is to be withdrawn
 * (traded or expired) is reported as such; one with a pending offer can not be withdrawn, which is
 * tried again every 5 s.
 */
class BulletinSimulator {

    private static final long SCALE           = 100000L;
    private static final long MIN_SECONDS     = 10;
    private static final long RETRY_SECONDS   = 5;
    private static final long PRICE_STEP      = SCALE / 100;     // bulletin prices rounded to 0.01

    private final Api    mApi;
    private final ScheduledExecutorService mScheduler;
    private final int    mMarketId;
    private final String mMarketName;
    private final String mInstrument;
    private final String mSide;                  // null = random
    private final long   mMidPrice;              // scaled, 0 = no price
    private final long   mMaxDeviation;          // scaled
    private final long   mMinVolume;             // whole units, 0 = no quantity
    private final long   mMaxVolume;
    private final long   mTimeToCreate;          // seconds
    private final long   mTimeToLive;            // seconds

    private long   mCount;                       // bulletins created, for userRef

    BulletinSimulator(Api pApi, ScheduledExecutorService pScheduler, JsonObject pConfig, int pMarketId, String pMarketName) {
        mApi          = pApi;
        mScheduler    = pScheduler;
        mMarketId     = pMarketId;
        mMarketName   = pMarketName;
        mInstrument   = Config.string(pConfig, "instrument");
        mSide         = pConfig.has("side") ? pConfig.get("side").getAsString().toUpperCase() : null;
        mMidPrice     = Config.scaled(pConfig, "midPrice", 0);
        mMaxDeviation = Config.scaled(pConfig, "maxPriceDeviation", 0);
        mMinVolume    = Math.round(Config.decimal(pConfig, "minVolume", 0));
        mMaxVolume    = Math.round(Config.decimal(pConfig, "maxVolume", mMinVolume));
        mTimeToCreate = Config.integer(pConfig, "timeToCreate", 60);
        mTimeToLive   = Config.integer(pConfig, "timeToLive", 300);
        if (mSide != null && !mSide.equals("BUY") && !mSide.equals("SELL")) {
            throw new IllegalArgumentException(mInstrument + ": side must be BUY or SELL");
        }
        if (mMaxVolume < mMinVolume) throw new IllegalArgumentException(mInstrument + ": maxVolume < minVolume");
    }

    String name() { return mMarketName + "/" + mInstrument; }

    void start() { scheduleCreate(); }

    /** A random number of seconds between 10 and pMax (pMax below 10: between pMax and 10). */
    private static long randomSeconds(long pMax) {
        long tLow = Math.min(MIN_SECONDS, pMax), tHigh = Math.max(MIN_SECONDS, pMax);
        return ThreadLocalRandom.current().nextLong(Math.max(1, tLow), Math.max(1, tHigh) + 1);
    }

    private void scheduleCreate() {
        mScheduler.schedule(this::create, randomSeconds(mTimeToCreate), TimeUnit.SECONDS);
    }

    private void create() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String tSide = (mSide != null) ? mSide : (r.nextBoolean() ? "BUY" : "SELL");
        JsonObject tBody = new JsonObject();
        tBody.addProperty("marketId",   mMarketId);
        tBody.addProperty("instrument", mInstrument);
        tBody.addProperty("side",       tSide);
        long tPrice = 0, tQuantity = 0;
        if (mMidPrice > 0) {
            long tOffset = (mMaxDeviation > 0) ? r.nextLong(-mMaxDeviation, mMaxDeviation + 1) : 0;
            tPrice = Math.max(PRICE_STEP, Math.round((mMidPrice + tOffset) / (double) PRICE_STEP) * PRICE_STEP);
            tBody.addProperty("price", tPrice);
        }
        if (mMinVolume > 0) {
            tQuantity = r.nextLong(mMinVolume, mMaxVolume + 1) * SCALE;
            tBody.addProperty("quantity", tQuantity);
        }
        tBody.addProperty("userRef", "sim-b" + (++mCount));
        try {
            Api.Reply tReply = mApi.post("/api/bulletins", tBody);
            if (!tReply.ok()) {
                Events.print("ERROR", name() + ": add bulletin: " + tReply.error());
                scheduleCreate();
                return;
            }
            String tBulletinId = tReply.object().get("bulletinId").getAsString();
            long   tLife       = randomSeconds(mTimeToLive);
            Events.print("BULLETIN CREATE", String.format("%s %s %s @ %s  bulletin %s  lives %d s", name(), tSide,
                    tQuantity > 0 ? ContinuousSimulator.fmt(tQuantity) : "-",
                    tPrice > 0 ? ContinuousSimulator.fmt(tPrice) : "-", tBulletinId, tLife));
            mScheduler.schedule(() -> withdraw(tBulletinId), tLife, TimeUnit.SECONDS);
        } catch (Exception e) {
            Events.print("ERROR", name() + ": add bulletin: " + e);
            scheduleCreate();
        }
    }

    private void withdraw(String pBulletinId) {
        try {
            Api.Reply tReply = mApi.delete("/api/bulletins/" + pBulletinId);
            if (tReply.ok()) {
                Events.print("BULLETIN DELETE", name() + "  bulletin " + pBulletinId);
            } else if (tReply.status() == 404) {
                Events.print("BULLETIN GONE", name() + "  bulletin " + pBulletinId + " (traded or expired)");
            } else if (tReply.text().contains("pending offer")) {
                Events.print("BULLETIN WAIT", name() + "  bulletin " + pBulletinId + " has a pending offer, retrying in " + RETRY_SECONDS + " s");
                mScheduler.schedule(() -> withdraw(pBulletinId), RETRY_SECONDS, TimeUnit.SECONDS);
                return;
            } else {
                Events.print("ERROR", name() + ": withdraw bulletin " + pBulletinId + ": " + tReply.error());
            }
        } catch (Exception e) {
            Events.print("ERROR", name() + ": withdraw bulletin " + pBulletinId + ": " + e);
        }
        scheduleCreate();
    }
}
