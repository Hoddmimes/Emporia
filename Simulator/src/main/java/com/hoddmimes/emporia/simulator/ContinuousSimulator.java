package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Order flow for one instrument of a continuous market. Each step (one transaction of the configuration's
 * txRate, sent for an instrument chosen at random) it reads
 * the simulator's own resting orders in the instrument (GET /api/orders) and the best prices
 * (GET /api/markets/{marketId}/prices), and then:
 * <ul>
 *   <li>adds a passive order on a side that has fewer than minDepth orders;</li>
 *   <li>deletes a random order on a side that has more than maxDepth orders;</li>
 *   <li>otherwise adds an order on a side below maxDepth or deletes one on a side above minDepth, at random.</li>
 * </ul>
 * Every tradeRate-th order is a matching order instead: it buys at the best ask (or sells at the best bid),
 * at most the volume there, so it trades and does not rest. A passive order's price is the mid price minus
 * (bid) or plus (ask) a random amount between one tick and maxPriceDeviation, never crossing the other side
 * of the book; its volume is random between minVolume and maxVolume. Prices and volumes are rounded to the
 * instrument's tickSize and lotSize. The depth counts the simulator's own orders only (the API has no
 * full order book).
 */
class ContinuousSimulator implements Runnable {

    private static final long SCALE = 100000L;   // the API's prices and quantities are scaled by this

    private final Api    mApi;
    private final String mName;
    private final int    mMarketId;
    private final long   mInstrumentId;
    private final long   mTickSize;
    private final long   mLotSize;

    private final int    mTradeRate;
    private final int    mMinDepth;
    private final int    mMaxDepth;
    private final long   mMidPrice;              // scaled
    private final long   mMaxDeviation;          // scaled
    private final long   mMinVolume;             // scaled
    private final long   mMaxVolume;             // scaled

    private long   mOrders;                      // orders sent, for tradeRate
    private String mLastError;                   // the same error is printed once, until something succeeds

    private record Own(String orderId, String side, long price, long quantity) {}

    ContinuousSimulator(Api pApi, JsonObject pConfig, JsonObject pInstrument) {
        mApi          = pApi;
        mName         = pInstrument.get("name").getAsString();
        mMarketId     = pInstrument.get("marketId").getAsInt();
        mInstrumentId = pInstrument.get("id").getAsLong();
        mTickSize     = Math.max(1, pInstrument.get("tickSize").getAsLong());
        mLotSize      = Math.max(1, pInstrument.get("lotSize").getAsLong());

        mTradeRate    = Config.integer(pConfig, "tradeRate", 0);
        mMinDepth     = Config.integer(pConfig, "minDepth", 5);
        mMaxDepth     = Config.integer(pConfig, "maxDepth", 15);
        mMidPrice     = Config.scaled(pConfig, "midPrice");
        mMaxDeviation = Config.scaled(pConfig, "maxPriceDeviation");
        mMinVolume    = Config.scaled(pConfig, "minVolume");
        mMaxVolume    = Config.scaled(pConfig, "maxVolume");
        if (mMinDepth < 0 || mMaxDepth < mMinDepth || mMaxDepth < 1) {
            throw new IllegalArgumentException(mName + ": need 0 <= minDepth <= maxDepth and maxDepth >= 1");
        }
        if (mMidPrice <= 0 || mMaxDeviation <= 0 || mMinVolume <= 0 || mMaxVolume < mMinVolume) {
            throw new IllegalArgumentException(mName + ": need midPrice, maxPriceDeviation and minVolume > 0 and maxVolume >= minVolume");
        }
    }

    String name() { return mName; }

    @Override
    public void run() {
        try {
            step();
        } catch (Exception e) {
            error(e.toString());
        }
    }

    private void step() throws Exception {
        Api.Reply tOrdersReply = mApi.get("/api/orders?instrumentId=" + mInstrumentId);
        if (!tOrdersReply.ok()) { error("list orders: " + tOrdersReply.error()); return; }
        List<Own> tBids = new ArrayList<>(), tAsks = new ArrayList<>();
        for (JsonElement e : tOrdersReply.object().getAsJsonArray("orders")) {
            JsonObject o = e.getAsJsonObject();
            Own tOwn = new Own(o.get("orderId").getAsString(), o.get("side").getAsString(),
                    o.get("price").getAsLong(), o.get("quantity").getAsLong());
            (tOwn.side().equals("BUY") ? tBids : tAsks).add(tOwn);
        }
        JsonObject tPrices = prices();
        if (tPrices == null) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        String tAction;
        if (tBids.size() < mMinDepth)       tAction = "BUY";
        else if (tAsks.size() < mMinDepth)  tAction = "SELL";
        else if (tBids.size() > mMaxDepth)  tAction = "DELETE BUY";
        else if (tAsks.size() > mMaxDepth)  tAction = "DELETE SELL";
        else {
            List<String> tChoices = new ArrayList<>();
            if (tBids.size() < mMaxDepth) tChoices.add("BUY");
            if (tAsks.size() < mMaxDepth) tChoices.add("SELL");
            if (tBids.size() > mMinDepth) tChoices.add("DELETE BUY");
            if (tAsks.size() > mMinDepth) tChoices.add("DELETE SELL");
            if (tChoices.isEmpty()) return;      // minDepth == maxDepth and both sides full
            tAction = tChoices.get(r.nextInt(tChoices.size()));
        }

        if (tAction.startsWith("DELETE")) {
            List<Own> tSide = tAction.endsWith("BUY") ? tBids : tAsks;
            deleteOrder(tSide.get(r.nextInt(tSide.size())));
            return;
        }
        mOrders++;
        if (mTradeRate > 0 && mOrders % mTradeRate == 0 && matchingOrder(tPrices)) return;
        passiveOrder(tAction, tPrices);
    }

    /** The instrument's best prices (scaled; JSON null where there is none), or null after an error. */
    private JsonObject prices() throws Exception {
        Api.Reply tReply = mApi.get("/api/markets/" + mMarketId + "/prices");
        if (!tReply.ok()) { error("prices: " + tReply.error()); return null; }
        for (JsonElement e : tReply.object().getAsJsonArray("prices")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("instrumentId").getAsLong() == mInstrumentId) return p;
        }
        error("no prices for instrument " + mInstrumentId);
        return null;
    }

    private static Long value(JsonObject pPrices, String pField) {
        JsonElement e = pPrices.get(pField);
        return (e == null || e.isJsonNull()) ? null : e.getAsLong();
    }

    private void passiveOrder(String pSide, JsonObject pPrices) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long tOffset = roundUp(r.nextLong(mTickSize, Math.max(mTickSize, mMaxDeviation) + 1), mTickSize);
        long tPrice;
        if (pSide.equals("BUY")) {
            tPrice = roundDown(mMidPrice - tOffset, mTickSize);
            Long tAsk = value(pPrices, "askPx");
            if (tAsk != null && tPrice >= tAsk) tPrice = tAsk - mTickSize;   // stay passive
        } else {
            tPrice = roundUp(mMidPrice + tOffset, mTickSize);
            Long tBid = value(pPrices, "bidPx");
            if (tBid != null && tPrice <= tBid) tPrice = tBid + mTickSize;
        }
        if (tPrice <= 0) tPrice = mTickSize;
        addOrder("CREATE", pSide, tPrice, volume());
    }

    /** A buy at the best ask or a sell at the best bid (random side, if both exist); false if neither exists. */
    private boolean matchingOrder(JsonObject pPrices) throws Exception {
        Long tBidPx = value(pPrices, "bidPx"), tAskPx = value(pPrices, "askPx");
        List<String> tSides = new ArrayList<>();
        if (tAskPx != null) tSides.add("BUY");
        if (tBidPx != null) tSides.add("SELL");
        if (tSides.isEmpty()) return false;
        String tSide = tSides.get(ThreadLocalRandom.current().nextInt(tSides.size()));
        long tPrice     = tSide.equals("BUY") ? tAskPx : tBidPx;
        long tAvailable = tSide.equals("BUY") ? value(pPrices, "askVol") : value(pPrices, "bidVol");
        long tQuantity  = Math.min(volume(), roundDown(tAvailable, mLotSize));   // no rest left in the book
        if (tQuantity <= 0) return false;
        addOrder("MATCH", tSide, tPrice, tQuantity);
        return true;
    }

    private void addOrder(String pEvent, String pSide, long pPrice, long pQuantity) throws Exception {
        JsonObject tBody = new JsonObject();
        tBody.addProperty("instrumentId", mInstrumentId);
        tBody.addProperty("side",         pSide);
        tBody.addProperty("price",        pPrice);
        tBody.addProperty("quantity",     pQuantity);
        tBody.addProperty("userRef",      "sim-" + mOrders);
        Api.Reply tReply = mApi.post("/api/orders", tBody);
        if (!tReply.ok()) { error("add " + pSide + " " + fmt(pQuantity) + " @ " + fmt(pPrice) + ": " + tReply.error()); return; }
        mLastError = null;
        JsonObject o = tReply.object();
        long tMatched = o.get("matched").getAsLong();
        Events.print(pEvent, String.format("%-10s %-4s %s @ %s  order %s%s", mName, pSide, fmt(pQuantity), fmt(pPrice),
                o.get("orderId").getAsString(), tMatched > 0 ? "  matched " + fmt(tMatched) : ""));
    }

    private void deleteOrder(Own pOrder) throws Exception {
        Api.Reply tReply = mApi.delete("/api/orders/" + pOrder.orderId());
        if (!tReply.ok()) { error("delete order " + pOrder.orderId() + ": " + tReply.error()); return; }
        mLastError = null;
        Events.print("DELETE", String.format("%-10s %-4s %s @ %s  order %s", mName, pOrder.side(),
                fmt(pOrder.quantity()), fmt(pOrder.price()), pOrder.orderId()));
    }

    private long volume() {
        long tVolume = ThreadLocalRandom.current().nextLong(mMinVolume, mMaxVolume + 1);
        return Math.max(mLotSize, roundDown(tVolume, mLotSize));
    }

    private void error(String pError) {
        if (pError.equals(mLastError)) return;   // e.g. every step while the market is closed
        mLastError = pError;
        Events.print("ERROR", mName + ": " + pError);
    }

    private static long roundDown(long pValue, long pStep) { return Math.floorDiv(pValue, pStep) * pStep; }
    private static long roundUp(long pValue, long pStep)   { return -Math.floorDiv(-pValue, pStep) * pStep; }

    static String fmt(long pScaled) {
        return java.math.BigDecimal.valueOf(pScaled, 5).stripTrailingZeros().toPlainString();
    }
}
