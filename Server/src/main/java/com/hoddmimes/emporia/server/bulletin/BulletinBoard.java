package com.hoddmimes.emporia.server.bulletin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.PositionCache;
import com.hoddmimes.emporia.server.audit.AuditLog;
import com.hoddmimes.emporia.server.db.BulletinTrade;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.db.Stored;
import com.hoddmimes.emporia.server.db.StoredBulletin;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The bulletin board: informal OTC bulletins in the bulletin markets (ported from EmporiaX's
 * BulletinBoard/BulletinManager, without IPMC). The bulletin markets (see BulletinMarket) are kept
 * apart from the continuous and auction markets of the InstrumentCache; the board holds them and is
 * updated by the admin endpoints after each database write, like the cache. A bulletin names its
 * tradable item with a free text {@code instrument}, which must be one of the market's instruments
 * (a literal name or a /regex/), has an optional side (BUY or SELL) and an optional price and
 * quantity, and is never matched. Another firm makes an offer on it: on a bulletin with a side the offer
 * is on the opposite side, on one without a side the offering firm chooses BUY or SELL; the owner's firm accepts or rejects the offer, or the offer
 * expires. An accepted offer is a trade at the offer's price and quantity. A bulletin holds at most
 * one pending offer.
 *
 * Bulletins can stay in the book for more than a trading day: they are stored in the Bulletins
 * collection and reloaded at startup, and removed (state EXPIRED) when the market's maxOrderTime
 * has passed since they were added. A pending offer is not stored and is lost on restart. Bulletin
 * trades are stored in the BulletinTrades collection (not in Trades). The offering firm may withdraw
 * its pending offer at any time. Accounts are firms, as for orders: any user of the owner's firm may withdraw
 * the bulletin or answer an offer, and a firm can't make an offer on its own bulletin. Adding a
 * bulletin, making an offer and accepting one require the market to be OPEN; withdrawing and
 * rejecting are always allowed.
 *
 * Every change is published as bdx messages (market topics, prices and quantities in display units):
 * a public, anonymous {@code Bulletin} (event ADDED/UPDATED/REMOVED) and a private {@code OwnBulletin}
 * to the owner's firm; a private {@code BulletinOffer} (event OFFERED/ACCEPTED/REJECTED/EXPIRED/CANCELLED) to both
 * the owner's and the offering firm; and for a trade a public, anonymous {@code BulletinTrade} and a
 * private {@code OwnBulletinTrade} to each side's firm. The firms never learn who the other side is.
 * A public {@code MarketState} is published for each bulletin market at load and on each status change.
 *
 * Prices and quantities in the requests and the REST replies are longs scaled by MatchingEngine.SCALE.
 */
public class BulletinBoard {

    private static final Logger cLog = LogManager.getLogger(BulletinBoard.class);

    public static final long   DEFAULT_OFFER_VALID_SECONDS = 60;

    private static final String EVENT_ADDED   = "ADDED";
    private static final String EVENT_UPDATED = "UPDATED";
    private static final String EVENT_REMOVED = "REMOVED";

    /** The outcome of a request: the HTTP status and either the reply body or an error message. */
    public record Result(int status, JsonObject body, String error) {
        static Result ok(int pStatus, JsonObject pBody) { return new Result(pStatus, pBody, null); }
        static Result error(int pStatus, String pError) { return new Result(pStatus, null, pError); }
        public boolean success() { return error == null; }
    }

    private final Database                 mDatabase;   // null in the unit tests: nothing is stored
    private final PositionCache            mPositions;  // null in the unit tests
    private final Consumer<BdxMessage>     mPublisher;
    private final AuditLog                 mAudit;
    private final Map<Integer, BulletinMarket> mMarkets   = new ConcurrentHashMap<>();
    private final Map<String, BulletinEntry>   mBulletins = new LinkedHashMap<>();   // bulletinId -> bulletin, oldest first
    private final ScheduledExecutorService mExpiryTimer;
    private final long mIdBase = (System.currentTimeMillis() / 1000L) << 32;
    private long mNextId    = 1;
    private long mNextTrade = 1;

    /** Without an audit log and positions (unit tests). */
    public BulletinBoard(Database pDatabase, Consumer<BdxMessage> pPublisher) {
        this(pDatabase, pPublisher, AuditLog.NONE, null);
    }

    /** pPositions (null: none) gets each bulletin trade once it is stored. */
    public BulletinBoard(Database pDatabase, Consumer<BdxMessage> pPublisher, AuditLog pAudit, PositionCache pPositions) {
        mDatabase        = pDatabase;
        mPositions       = pPositions;
        mPublisher       = pPublisher;
        mAudit           = pAudit;
        mExpiryTimer     = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BulletinExpiry");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Markets ───────────────────────────────────────────────────────────

    /** Stops the expiry timer (web application undeploy). */
    public void stop() {
        mExpiryTimer.shutdownNow();
    }

    /** Loads the bulletin markets and the stored bulletins from the database. Called once at startup. */
    public synchronized void load() {
        for (Stored<BulletinMarket> tMarket : mDatabase.listBulletinMarkets()) putMarket(tMarket.value());
        long tNow = System.currentTimeMillis();
        int tExpired = 0;
        for (StoredBulletin tStored : mDatabase.listBulletins()) {
            BulletinEntry tEntry = BulletinEntry.fromStored(tStored);
            mBulletins.put(tEntry.bulletinId, tEntry);
            if (tEntry.expiresAt <= tNow) {
                remove(tEntry, BulletinEntry.STATE_EXPIRED);
                tExpired++;
            } else {
                scheduleExpiry(tEntry);
            }
        }
        cLog.info("BulletinBoard: {} bulletin markets and {} bulletins loaded ({} expired while the server was down)",
                mMarkets.size(), mBulletins.size(), tExpired);
    }

    /**
     * Adds or replaces a bulletin market, as read back from the database (call after the database
     * write; null is ignored). A MarketState is published for a new market and when the status changes. A changed
     * maxOrderTime applies to bulletins added after the change.
     */
    public synchronized void putMarket(BulletinMarket pMarket) {
        if (pMarket == null) return;
        BulletinMarket tMarket = pMarket;
        BulletinMarket tPrev = mMarkets.put(tMarket.marketId(), tMarket);
        if (tPrev == null || !tPrev.tradingStatus().equals(tMarket.tradingStatus())) {
            mPublisher.accept(BdxMessage.market(BdxMessage.MARKET_STATE, tMarket.marketId())
                    .put("marketName", tMarket.name()).put("tradingStatus", tMarket.tradingStatus()));
        }
    }

    /** Removes a bulletin market (call after the database delete; the admin first checks that it has no bulletins). */
    public synchronized void removeMarket(int pMarketId) {
        mMarkets.remove(pMarketId);
    }

    /**
     * Admin: deletes every bulletin in the market, of all firms and in any market state (state DELETED,
     * also removed from the database); a pending offer is cancelled (BulletinOffer event CANCELLED).
     * Returns the number of bulletins deleted.
     */
    public synchronized int deleteAllBulletins(int pMarketId) {
        List<BulletinEntry> tEntries = mBulletins.values().stream().filter(b -> b.marketId == pMarketId).toList();
        for (BulletinEntry tEntry : tEntries) {
            if (BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) publishOffer("CANCELLED", tEntry);
            remove(tEntry, BulletinEntry.STATE_DELETED);
        }
        cLog.info("Admin deleted all bulletins in market {}: {} bulletin(s)", pMarketId, tEntries.size());
        return tEntries.size();
    }

    // ── Market supervision (admin) ────────────────────────────────────────

    /**
     * The bulletins of all firms, or of one firm (pFirm null: all; a firm matches as owner or as the maker of
     * the pending offer), in one bulletin market or (pMarketId 0) all of them, oldest first: every field,
     * with the owner's firm, user, userRef and client account and the pending offer's firm, user, offerRef
     * and client account.
     */
    public synchronized JsonArray getAllBulletins(int pMarketId, String pFirm) {
        JsonArray tOut = new JsonArray();
        for (BulletinEntry e : mBulletins.values()) {
            if (pMarketId != 0 && e.marketId != pMarketId) continue;
            if (pFirm != null && !pFirm.equals(e.ownerFirm) && !pFirm.equals(e.offerFirm)) continue;
            BulletinMarket tMarket = mMarkets.get(e.marketId);
            JsonObject j = new JsonObject();
            j.addProperty("bulletinId",        e.bulletinId);
            j.addProperty("marketId",          e.marketId);
            j.addProperty("marketName",        (tMarket != null) ? tMarket.name() : String.valueOf(e.marketId));
            j.addProperty("instrument",        e.instrument);
            j.addProperty("side",              e.side);
            j.addProperty("price",             e.price);
            j.addProperty("quantity",          e.quantity);
            j.addProperty("remainingQuantity", e.remainingQuantity);
            j.addProperty("state",             e.state);
            j.addProperty("createTime",        e.createTime);
            j.addProperty("expiresAt",         e.expiresAt);
            j.addProperty("firm",              e.ownerFirm);
            j.addProperty("user",              e.ownerUser);
            j.addProperty("userRef",           e.userRef);
            j.addProperty("clientAccount",     e.clientAccount);
            if (BulletinEntry.STATE_OFFER_PENDING.equals(e.state)) {
                JsonObject o = new JsonObject();
                o.addProperty("firm",          e.offerFirm);
                o.addProperty("user",          e.offerUser);
                o.addProperty("side",          e.offerSide);
                o.addProperty("price",         e.offerPrice);
                o.addProperty("quantity",      e.offerQuantity);
                o.addProperty("expiresAt",     e.offerExpiresAt);
                o.addProperty("offerRef",      e.offerRef);
                o.addProperty("clientAccount", e.offerClientAccount);
                j.add("offer", o);
            }
            tOut.add(j);
        }
        return tOut;
    }

    /**
     * Deletes one bulletin of any firm, in any market state (state DELETED, also removed from the database);
     * a pending offer is cancelled (BulletinOffer event CANCELLED). False if there is no such bulletin.
     */
    public synchronized boolean supervisionDeleteBulletin(String pBulletinId) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null) return false;
        if (BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) publishOffer("CANCELLED", tEntry);
        remove(tEntry, BulletinEntry.STATE_DELETED);
        return true;
    }

    /**
     * Makes the bulletin trade reversing pOriginal (BulletinTrade.reversal: same bulletin, instrument, price
     * and quantity, the seller buying back from the buyer), stores it durably and sends each firm its
     * OwnBulletinTrade (with reversalOf), then returns it. No public BulletinTrade: it is no market trade, and
     * Database.lastBulletinTradePrice ignores it. The caller checks that pOriginal may be reversed.
     */
    public synchronized BulletinTrade reverseTrade(BulletinTrade pOriginal) {
        BulletinTrade tReversal = pOriginal.reversal(Long.toHexString(mIdBase + mNextTrade++), System.currentTimeMillis());
        store(tReversal);
        auditTrade(tReversal);
        publishOwnReversal(tReversal, tReversal.buyAccount(),  "BUY",  tReversal.buyRef(),  tReversal.buyClientAccount());
        publishOwnReversal(tReversal, tReversal.sellAccount(), "SELL", tReversal.sellRef(), tReversal.sellClientAccount());
        cLog.info("Bulletin trade {} reversed by trade {}: {} buys {}@{} {} from {}", pOriginal.tradeNo(), tReversal.tradeNo(),
                tReversal.buyAccount(), unscale(tReversal.quantity()), unscale(tReversal.price()), tReversal.instrument(),
                tReversal.sellAccount());
        return tReversal;
    }

    private void publishOwnReversal(BulletinTrade pTrade, String pFirm, String pSide, String pRef, String pClientAccount) {
        mPublisher.accept(BdxMessage.forFirm(BdxMessage.OWN_BULLETIN_TRADE, pTrade.marketId(), pFirm)
                .put("bulletinId", pTrade.bulletinId())
                .put("instrument", pTrade.instrument())
                .put("side",       pSide)
                .put("price",      unscale(pTrade.price()))
                .put("quantity",   unscale(pTrade.quantity()))
                .put("tradeNo",    pTrade.tradeNo())
                .put("tradeTime",  pTrade.tradeTime())
                .put("ref",        pRef)
                .put("clientAccount", pClientAccount)
                .put("reversalOf", pTrade.reversalOf()));
    }

    public BulletinMarket getMarket(int pMarketId) {
        return mMarkets.get(pMarketId);
    }

    public Collection<BulletinMarket> getMarkets() {
        return mMarkets.values();
    }

    /** The highest bulletin marketId + 1 (see RestApiContext.nextMarketId). */
    public int nextMarketId() {
        return mMarkets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /** The number of bulletins in the market. */
    public synchronized int bulletinCount(int pMarketId) {
        return (int) mBulletins.values().stream().filter(b -> b.marketId == pMarketId).count();
    }

    // ── Requests ──────────────────────────────────────────────────────────

    public synchronized Result addBulletin(String pFirm, String pUser, int pMarketId, String pInstrument,
                                           String pSide, long pPrice, long pQuantity, String pUserRef) {
        return addBulletin(pFirm, pUser, pMarketId, pInstrument, pSide, pPrice, pQuantity, pUserRef, null);
    }

    /** Adds a bulletin; pClientAccount is the owner's client account (null: the default one). */
    public synchronized Result addBulletin(String pFirm, String pUser, int pMarketId, String pInstrument,
                                           String pSide, long pPrice, long pQuantity, String pUserRef,
                                           String pClientAccount) {
        String tErr = checkMarketOpen(pMarketId);
        if (tErr != null) return Result.error(400, tErr);
        if (pInstrument == null || pInstrument.isBlank()) return Result.error(400, "instrument is required");
        BulletinMarket tMarket = mMarkets.get(pMarketId);
        if (!tMarket.allows(pInstrument.trim())) {
            return Result.error(400, "Instrument '" + pInstrument.trim() + "' is not traded in bulletin market " + tMarket.name());
        }
        if (pSide != null && !"BUY".equals(pSide) && !"SELL".equals(pSide)) {
            return Result.error(400, "side must be BUY or SELL, or not given");
        }
        if (pPrice < 0)    return Result.error(400, "price must not be negative");
        if (pQuantity < 0) return Result.error(400, "quantity must not be negative");

        long tNow = System.currentTimeMillis();
        BulletinEntry tEntry = new BulletinEntry(Long.toHexString(mIdBase + mNextId++), pMarketId, pInstrument.trim(),
                pSide, pPrice, pQuantity, pQuantity, pFirm, pUser, pUserRef, clientAccountOrDefault(pClientAccount),
                tNow, tNow + tMarket.maxOrderTimeMillis());
        store(tEntry);
        mBulletins.put(tEntry.bulletinId, tEntry);
        scheduleExpiry(tEntry);
        publishBulletin(EVENT_ADDED, tEntry);
        cLog.info("Bulletin {} added by {}/{}: market {} {} '{}' {}@{}", tEntry.bulletinId, pFirm, pUser,
                pMarketId, (pSide != null) ? pSide : "BUY/SELL", tEntry.instrument, unscale(pQuantity), unscale(pPrice));

        JsonObject tOut = new JsonObject();
        tOut.addProperty("bulletinId", tEntry.bulletinId);
        tOut.addProperty("state",      tEntry.state);
        tOut.addProperty("expiresAt",  tEntry.expiresAt);
        if (pUserRef != null) tOut.addProperty("userRef", pUserRef);
        return Result.ok(201, tOut);
    }

    /** Withdraws a bulletin of the firm; not while an offer is pending. */
    public synchronized Result withdrawBulletin(String pFirm, String pBulletinId) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null || !tEntry.ownerFirm.equals(pFirm)) return notFound(pBulletinId);
        if (!BulletinEntry.STATE_ACTIVE.equals(tEntry.state)) {
            return Result.error(400, "Bulletin " + pBulletinId + " has a pending offer; answer it before withdrawing");
        }
        remove(tEntry, BulletinEntry.STATE_WITHDRAWN);
        cLog.info("Bulletin {} withdrawn by {}", pBulletinId, pFirm);
        return Result.ok(200, idAndState(tEntry));
    }

    /** An offer on the opposite side of the bulletin's side, see below. */
    public synchronized Result makeOffer(String pFirm, String pUser, String pBulletinId, long pPrice, long pQuantity,
                                         String pOfferRef, long pValidSeconds) {
        return makeOffer(pFirm, pUser, pBulletinId, null, pPrice, pQuantity, pOfferRef, pValidSeconds);
    }

    /** An offer without a client account (the default one), see below. */
    public synchronized Result makeOffer(String pFirm, String pUser, String pBulletinId, String pSide,
                                         long pPrice, long pQuantity, String pOfferRef, long pValidSeconds) {
        return makeOffer(pFirm, pUser, pBulletinId, pSide, pPrice, pQuantity, pOfferRef, null, pValidSeconds);
    }

    /**
     * An offer from another firm, on side pSide, at the given price and quantity, valid for pValidSeconds
     * (DEFAULT_OFFER_VALID_SECONDS if 0). The quantity can't exceed the bulletin's remaining quantity. On a
     * bulletin with a side the offer is on the opposite side (pSide may be null, otherwise it must be that
     * side); on a bulletin without a side pSide (BUY or SELL) is required. pClientAccount is the offering
     * firm's client account (null: the default one).
     */
    public synchronized Result makeOffer(String pFirm, String pUser, String pBulletinId, String pSide,
                                         long pPrice, long pQuantity, String pOfferRef, String pClientAccount,
                                         long pValidSeconds) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null) return notFound(pBulletinId);
        String tErr = checkMarketOpen(tEntry.marketId);
        if (tErr != null) return Result.error(400, tErr);
        if (tEntry.ownerFirm.equals(pFirm)) return Result.error(400, "A firm can't make an offer on its own bulletin");
        if (!BulletinEntry.STATE_ACTIVE.equals(tEntry.state)) {
            return Result.error(400, "Bulletin " + pBulletinId + " already has a pending offer");
        }
        if (pPrice <= 0)    return Result.error(400, "price must be greater than zero");
        if (pQuantity <= 0) return Result.error(400, "quantity must be greater than zero");
        if (tEntry.quantity > 0 && pQuantity > tEntry.remainingQuantity) {
            return Result.error(400, "quantity exceeds the bulletin's remaining quantity " + tEntry.remainingQuantity);
        }
        if (pValidSeconds < 0) return Result.error(400, "validTime must not be negative");
        String tOfferSide;
        if (tEntry.side != null) {
            tOfferSide = BulletinEntry.opposite(tEntry.side);
            if (pSide != null && !pSide.equals(tOfferSide)) {
                return Result.error(400, "side must be " + tOfferSide + ", the opposite of the bulletin's side " + tEntry.side);
            }
        } else {
            if (!"BUY".equals(pSide) && !"SELL".equals(pSide)) {
                return Result.error(400, "side (BUY or SELL) is required for an offer on a bulletin without a side");
            }
            tOfferSide = pSide;
        }

        long tValidSeconds = (pValidSeconds > 0) ? pValidSeconds : DEFAULT_OFFER_VALID_SECONDS;
        long tOfferSeq     = ++tEntry.offerSeq;
        tEntry.state          = BulletinEntry.STATE_OFFER_PENDING;
        tEntry.offerFirm      = pFirm;
        tEntry.offerUser      = pUser;
        tEntry.offerSide      = tOfferSide;
        tEntry.offerPrice     = pPrice;
        tEntry.offerQuantity  = pQuantity;
        tEntry.offerRef       = pOfferRef;
        tEntry.offerClientAccount = clientAccountOrDefault(pClientAccount);
        tEntry.offerExpiresAt = System.currentTimeMillis() + tValidSeconds * 1000L;
        tEntry.offerExpiry    = mExpiryTimer.schedule(() -> expireOffer(pBulletinId, tOfferSeq),
                                                      tValidSeconds, TimeUnit.SECONDS);

        publishBulletin(EVENT_UPDATED, tEntry);
        publishOffer("OFFERED", tEntry);
        cLog.info("Offer on bulletin {} by {}/{}: {} {}@{}, valid {} s", pBulletinId, pFirm, pUser, tOfferSide,
                unscale(pQuantity), unscale(pPrice), tValidSeconds);
        return Result.ok(200, idAndState(tEntry));
    }

    /**
     * The offering firm withdraws its pending offer (BulletinOffer event CANCELLED); any user of the firm
     * may, in any market state. Another firm's offer, or no pending offer, is reported as not found.
     */
    public synchronized Result withdrawOffer(String pFirm, String pBulletinId) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null) return notFound(pBulletinId);
        if (!BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state) || !pFirm.equals(tEntry.offerFirm)) {
            return Result.error(404, "Your firm has no pending offer on bulletin " + pBulletinId);
        }
        publishOffer("CANCELLED", tEntry);
        cLog.info("Offer on bulletin {} withdrawn by {}", pBulletinId, pFirm);
        reopen(tEntry);
        return Result.ok(200, idAndState(tEntry));
    }

    /** The owner's firm accepts (a trade) or rejects the pending offer. */
    public synchronized Result answerOffer(String pFirm, String pBulletinId, boolean pAccept) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null || !tEntry.ownerFirm.equals(pFirm)) return notFound(pBulletinId);
        if (!BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) {
            return Result.error(400, "Bulletin " + pBulletinId + " has no pending offer");
        }
        if (!pAccept) {
            publishOffer("REJECTED", tEntry);
            cLog.info("Offer on bulletin {} rejected by {}", pBulletinId, pFirm);
            reopen(tEntry);
            return Result.ok(200, idAndState(tEntry));
        }

        String tErr = checkMarketOpen(tEntry.marketId);
        if (tErr != null) return Result.error(400, tErr);
        publishOffer("ACCEPTED", tEntry);
        trade(tEntry);
        if (tEntry.quantity > 0) tEntry.remainingQuantity -= tEntry.offerQuantity;
        if (tEntry.quantity == 0 || tEntry.remainingQuantity <= 0) {
            // Fully traded; a bulletin without a quantity is done after its first trade
            remove(tEntry, BulletinEntry.STATE_TRADED);
        } else {
            store(tEntry);
            reopen(tEntry);
        }
        return Result.ok(200, idAndState(tEntry));
    }

    /**
     * The bulletins in the given markets, oldest first. Everyone sees the public fields; the owner's
     * firm also sees userRef, user, clientAccount and a pending offer, the offering firm its own offer
     * (with its offerRef and clientAccount).
     */
    public synchronized JsonArray getBulletins(String pFirm, Set<Integer> pMarketIds) {
        JsonArray tOut = new JsonArray();
        for (BulletinEntry tEntry : mBulletins.values()) {
            if (!pMarketIds.contains(tEntry.marketId)) continue;
            JsonObject tJson = new JsonObject();
            tJson.addProperty("bulletinId",        tEntry.bulletinId);
            tJson.addProperty("marketId",          tEntry.marketId);
            tJson.addProperty("instrument",        tEntry.instrument);
            tJson.addProperty("side",              tEntry.side);
            tJson.addProperty("price",             tEntry.price);
            tJson.addProperty("quantity",          tEntry.quantity);
            tJson.addProperty("remainingQuantity", tEntry.remainingQuantity);
            tJson.addProperty("state",             tEntry.state);
            tJson.addProperty("createTime",        tEntry.createTime);
            tJson.addProperty("expiresAt",         tEntry.expiresAt);
            boolean tOwner = tEntry.ownerFirm.equals(pFirm);
            tJson.addProperty("own", tOwner);
            if (tOwner) {
                tJson.addProperty("user", tEntry.ownerUser);
                if (tEntry.userRef != null) tJson.addProperty("userRef", tEntry.userRef);
                tJson.addProperty("clientAccount", tEntry.clientAccount);
            }
            boolean tOfferer = pFirm.equals(tEntry.offerFirm);
            if ((tOwner || tOfferer) && BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) {
                JsonObject tOffer = new JsonObject();
                tOffer.addProperty("side",      tEntry.offerSide);
                tOffer.addProperty("price",     tEntry.offerPrice);
                tOffer.addProperty("quantity",  tEntry.offerQuantity);
                tOffer.addProperty("expiresAt", tEntry.offerExpiresAt);
                tOffer.addProperty("own",       tOfferer);
                if (tOfferer && tEntry.offerRef != null) tOffer.addProperty("offerRef", tEntry.offerRef);
                if (tOfferer) tOffer.addProperty("clientAccount", tEntry.offerClientAccount);
                tJson.add("offer", tOffer);
            }
            tOut.add(tJson);
        }
        return tOut;
    }

    /** The market id of a bulletin, or 0 if it doesn't exist. */
    public synchronized int getMarketId(String pBulletinId) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        return (tEntry != null) ? tEntry.marketId : 0;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Rejects the offer if it is still the pending one when its time is up. */
    private synchronized void expireOffer(String pBulletinId, long pOfferSeq) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null || tEntry.offerSeq != pOfferSeq
                || !BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) return;
        publishOffer("EXPIRED", tEntry);
        cLog.info("Offer on bulletin {} expired", pBulletinId);
        reopen(tEntry);
    }

    /** Removes the bulletin when the market's maxOrderTime has passed; a pending offer expires with it. */
    private synchronized void expireBulletin(String pBulletinId) {
        BulletinEntry tEntry = mBulletins.get(pBulletinId);
        if (tEntry == null) return;
        if (BulletinEntry.STATE_OFFER_PENDING.equals(tEntry.state)) publishOffer("EXPIRED", tEntry);
        remove(tEntry, BulletinEntry.STATE_EXPIRED);
        cLog.info("Bulletin {} expired (max order time of market {})", pBulletinId, tEntry.marketId);
    }

    private void scheduleExpiry(BulletinEntry pEntry) {
        long tDelay = Math.max(0, pEntry.expiresAt - System.currentTimeMillis());
        pEntry.expiry = mExpiryTimer.schedule(() -> expireBulletin(pEntry.bulletinId), tDelay, TimeUnit.MILLISECONDS);
    }

    /** Takes the bulletin off the board and out of the database, in its final state. */
    private void remove(BulletinEntry pEntry, String pState) {
        if (pEntry.expiry != null) pEntry.expiry.cancel(false);
        pEntry.clearOffer();
        pEntry.state = pState;
        mBulletins.remove(pEntry.bulletinId);
        if (mDatabase != null) mDatabase.deleteBulletin(pEntry.bulletinId);
        publishBulletin(EVENT_REMOVED, pEntry);
    }

    private static String clientAccountOrDefault(String pClientAccount) {
        return (pClientAccount != null) ? pClientAccount : AddOrderRequest.DEFAULT_CLIENT_ACCOUNT;
    }

    private void store(BulletinEntry pEntry) {
        if (mDatabase != null) mDatabase.saveBulletin(pEntry.toStored());
    }

    /** Clears the pending offer and opens the bulletin for offers again. */
    private void reopen(BulletinEntry pEntry) {
        pEntry.clearOffer();
        pEntry.state = BulletinEntry.STATE_ACTIVE;
        publishBulletin(EVENT_UPDATED, pEntry);
    }

    private void trade(BulletinEntry pEntry) {
        String tTradeNo   = Long.toHexString(mIdBase + mNextTrade++);
        long   tTradeTime = System.currentTimeMillis();
        // Stored (journaled) before anyone is told about the trade
        BulletinTrade tTrade = tradeRecord(pEntry, tTradeNo, tTradeTime);
        store(tTrade);
        auditTrade(tTrade);
        mPublisher.accept(BdxMessage.market(BdxMessage.BULLETIN_TRADE, pEntry.marketId)
                .put("bulletinId", pEntry.bulletinId)
                .put("instrument", pEntry.instrument)
                .put("price",      unscale(pEntry.offerPrice))
                .put("quantity",   unscale(pEntry.offerQuantity))
                .put("tradeNo",    tTradeNo)
                .put("tradeTime",  tTradeTime));
        publishOwnTrade(pEntry, pEntry.ownerFirm, pEntry.ownerTradeSide(), "userRef",  pEntry.userRef,  pEntry.clientAccount,
                tTradeNo, tTradeTime);
        publishOwnTrade(pEntry, pEntry.offerFirm, pEntry.offerSide,        "offerRef", pEntry.offerRef, pEntry.offerClientAccount,
                tTradeNo, tTradeTime);
        cLog.info("Bulletin {} traded {}@{}: {} {} / {} {}", pEntry.bulletinId,
                unscale(pEntry.offerQuantity), unscale(pEntry.offerPrice),
                pEntry.ownerFirm, pEntry.ownerTradeSide(), pEntry.offerFirm, pEntry.offerSide);
    }

    /** Queues the bulletin trade's audit message: both sides with their firm, trader, reference and client account. */
    private void auditTrade(BulletinTrade pTrade) {
        JsonObject tData = new JsonObject();
        tData.addProperty("tradeNo",    pTrade.tradeNo());
        if (pTrade.isReversal()) tData.addProperty("reversalOf", pTrade.reversalOf());
        tData.addProperty("bulletinId", pTrade.bulletinId());
        tData.addProperty("marketId",   pTrade.marketId());
        tData.addProperty("instrument", pTrade.instrument());
        tData.add("price",    AuditLog.unscaled(pTrade.price()));
        tData.add("quantity", AuditLog.unscaled(pTrade.quantity()));
        tData.add("buy",  auditSide(pTrade.buyAccount(),  pTrade.buyUser(),  pTrade.buyRef(),  pTrade.buyClientAccount()));
        tData.add("sell", auditSide(pTrade.sellAccount(), pTrade.sellUser(), pTrade.sellRef(), pTrade.sellClientAccount()));
        mAudit.queue(AuditLog.BULLETIN_TRADE, null, null, tData, null);
    }

    private static JsonObject auditSide(String pFirm, String pUser, String pRef, String pClientAccount) {
        JsonObject tSide = new JsonObject();
        tSide.addProperty("firm",          pFirm);
        tSide.addProperty("user",          pUser);
        tSide.addProperty("ref",           pRef);
        tSide.addProperty("clientAccount", clientAccountOrDefault(pClientAccount));
        return tSide;
    }

    /** Stores the bulletin trade durably, then adds it to the positions. */
    private void store(BulletinTrade pTrade) {
        if (mDatabase == null) return;
        mDatabase.saveBulletinTrade(pTrade);
        if (mPositions != null) mPositions.addBulletinTrade(pTrade);
    }

    /** The stored bulletin trade of a trade on the pending offer (see Database.saveBulletinTrade). */
    static BulletinTrade tradeRecord(BulletinEntry pEntry, String pTradeNo, long pTradeTime) {
        boolean tOwnerBuys = "BUY".equals(pEntry.ownerTradeSide());
        return new BulletinTrade(pTradeNo, pEntry.bulletinId, pEntry.marketId, pEntry.instrument,
                pEntry.offerPrice, pEntry.offerQuantity, pTradeTime,
                tOwnerBuys ? pEntry.ownerFirm : pEntry.offerFirm,
                tOwnerBuys ? pEntry.ownerUser : pEntry.offerUser,
                tOwnerBuys ? pEntry.userRef   : pEntry.offerRef,
                tOwnerBuys ? pEntry.clientAccount : pEntry.offerClientAccount,
                tOwnerBuys ? pEntry.offerFirm : pEntry.ownerFirm,
                tOwnerBuys ? pEntry.offerUser : pEntry.ownerUser,
                tOwnerBuys ? pEntry.offerRef  : pEntry.userRef,
                tOwnerBuys ? pEntry.offerClientAccount : pEntry.clientAccount);
    }

    private void publishOwnTrade(BulletinEntry pEntry, String pFirm, String pSide, String pRefName, String pRef,
                                 String pClientAccount, String pTradeNo, long pTradeTime) {
        mPublisher.accept(BdxMessage.forFirm(BdxMessage.OWN_BULLETIN_TRADE, pEntry.marketId, pFirm)
                .put("bulletinId", pEntry.bulletinId)
                .put("instrument", pEntry.instrument)
                .put("side",       pSide)
                .put("price",      unscale(pEntry.offerPrice))
                .put("quantity",   unscale(pEntry.offerQuantity))
                .put("tradeNo",    pTradeNo)
                .put("tradeTime",  pTradeTime)
                .put(pRefName,     pRef)
                .put("clientAccount", pClientAccount));
    }

    /** The public Bulletin and the private OwnBulletin to the owner's firm. */
    private void publishBulletin(String pEvent, BulletinEntry pEntry) {
        mPublisher.accept(bulletinMessage(BdxMessage.market(BdxMessage.BULLETIN, pEntry.marketId), pEvent, pEntry));
        mPublisher.accept(bulletinMessage(BdxMessage.forFirm(BdxMessage.OWN_BULLETIN, pEntry.marketId, pEntry.ownerFirm), pEvent, pEntry)
                .put("user",    pEntry.ownerUser)
                .put("userRef", pEntry.userRef)
                .put("clientAccount", pEntry.clientAccount));
    }

    private static BdxMessage bulletinMessage(BdxMessage pMessage, String pEvent, BulletinEntry pEntry) {
        return pMessage.put("event",             pEvent)
                       .put("bulletinId",        pEntry.bulletinId)
                       .put("instrument",        pEntry.instrument)
                       .put("side",              pEntry.side)   // null when the bulletin has no side
                       .put("price",             (pEntry.price    > 0) ? unscale(pEntry.price)    : null)
                       .put("quantity",          (pEntry.quantity > 0) ? unscale(pEntry.quantity) : null)
                       .put("remainingQuantity", (pEntry.quantity > 0) ? unscale(pEntry.remainingQuantity) : null)
                       .put("state",             pEntry.state)
                       .put("expiresAt",         pEntry.expiresAt);
    }

    /** The pending offer's BulletinOffer, to the owner's firm (role OWNER) and to the offering firm (role OFFERER). */
    private void publishOffer(String pEvent, BulletinEntry pEntry) {
        mPublisher.accept(offerMessage(pEntry.ownerFirm, "OWNER", pEvent, pEntry)
                .put("userRef",  pEntry.userRef)
                .put("clientAccount", pEntry.clientAccount));
        mPublisher.accept(offerMessage(pEntry.offerFirm, "OFFERER", pEvent, pEntry)
                .put("offerRef", pEntry.offerRef)
                .put("clientAccount", pEntry.offerClientAccount));
    }

    private static BdxMessage offerMessage(String pFirm, String pRole, String pEvent, BulletinEntry pEntry) {
        return BdxMessage.forFirm(BdxMessage.BULLETIN_OFFER, pEntry.marketId, pFirm)
                .put("event",      pEvent)
                .put("role",       pRole)
                .put("bulletinId", pEntry.bulletinId)
                .put("instrument", pEntry.instrument)
                .put("side",       pEntry.offerSide)     // the offer's side
                .put("price",      unscale(pEntry.offerPrice))
                .put("quantity",   unscale(pEntry.offerQuantity))
                .put("expiresAt",  pEntry.offerExpiresAt);
    }

    /** Null if the bulletin market exists and is OPEN, otherwise why not. */
    private String checkMarketOpen(int pMarketId) {
        BulletinMarket tMarket = mMarkets.get(pMarketId);
        if (tMarket == null) return "Bulletin market not found: " + pMarketId;
        if (!tMarket.isOpen()) {
            return "Market " + tMarket.name() + " is " + tMarket.tradingStatus() + ", bulletins are only traded when it is OPEN";
        }
        return null;
    }

    private static Result notFound(String pBulletinId) {
        return Result.error(404, "Bulletin not found: " + pBulletinId);
    }

    private static JsonObject idAndState(BulletinEntry pEntry) {
        JsonObject tOut = new JsonObject();
        tOut.addProperty("bulletinId",        pEntry.bulletinId);
        tOut.addProperty("state",             pEntry.state);
        tOut.addProperty("remainingQuantity", pEntry.remainingQuantity);
        return tOut;
    }

    private static double unscale(long pValue) {
        return (double) pValue / MatchingEngine.SCALE;
    }
}
