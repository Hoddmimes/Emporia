package com.hoddmimes.emporia.testclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * One trading session: logs in, runs its directives {@code repeat} times at {@code txRate}
 * directives per second, and logs out. Each session has its own HttpClient and cookies, i.e.
 * its own HTTP session on the server.
 */
public class TestSession implements Runnable {

    /** The server API uses prices and quantities scaled by this factor (MatchingEngine.SCALE). */
    private static final long SCALE = 100000L;

    private final int        mIndex;
    private final JsonObject mConfig;
    private final String     mUrl;
    private final boolean    mVerbose;
    private final HttpClient mHttp;
    private final String     mFirm;
    private final String     mUser;

    private final Map<String, Long>   mInstrumentIds = new HashMap<>();   // name -> instrumentId
    private final Map<String, String> mOrderIds      = new HashMap<>();   // ref -> orderId

    private int     mSent;
    private int     mOk;
    private int     mFailed;
    private long    mTotalMs;
    private boolean mCompleted;

    public TestSession(int pIndex, JsonObject pConfig, String pUrl, SSLContext pSslContext, boolean pVerbose) {
        mIndex   = pIndex;
        mConfig  = pConfig;
        mUrl     = pUrl;
        mVerbose = pVerbose;
        mFirm    = pConfig.get("firm").getAsString();
        mUser    = pConfig.get("user").getAsString();
        mHttp    = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .sslContext(pSslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String  name()      { return mIndex + ":" + mUser + "@" + mFirm; }
    public int     failed()    { return mFailed; }
    public boolean completed() { return mCompleted; }

    public String summary() {
        return String.format("%-28s %8d %8d %8d %10.1f%s", name(), mSent, mOk, mFailed,
                mSent > 0 ? (double) mTotalMs / mSent : 0.0, mCompleted ? "" : "   (aborted)");
    }

    @Override
    public void run() {
        try {
            if (!login()) return;
            loadInstruments();

            int        tRepeat     = mConfig.has("repeat") ? mConfig.get("repeat").getAsInt()    : 1;
            double     tTxRate     = mConfig.has("txRate") ? mConfig.get("txRate").getAsDouble() : 0;
            long       tIntervalNs = tTxRate > 0 ? (long) (1_000_000_000L / tTxRate) : 0L;
            JsonArray  tDirectives = mConfig.getAsJsonArray("directives");

            long tNext = System.nanoTime();
            for (int r = 0; r < tRepeat; r++) {
                for (JsonElement tDirective : tDirectives) {
                    if (tIntervalNs > 0) {
                        long tWait = tNext - System.nanoTime();
                        if (tWait > 0) Thread.sleep(tWait / 1_000_000, (int) (tWait % 1_000_000));
                        tNext += tIntervalNs;
                    }
                    execute(tDirective.getAsJsonObject());
                }
            }
            mCompleted = true;
            post("/api/logout", "{}");
        } catch (Exception e) {
            System.out.println("[" + name() + "] aborted: " + e);
        }
    }

    // ── Session setup ─────────────────────────────────────────────────────

    private boolean login() throws Exception {
        JsonObject tBody = new JsonObject();
        tBody.addProperty("firm",     mFirm);
        tBody.addProperty("userId",   mUser);
        tBody.addProperty("password", mConfig.get("password").getAsString());
        HttpResponse<String> tRsp = post("/api/login", tBody.toString());
        if (tRsp.statusCode() != 200) {
            System.out.println("[" + name() + "] login failed: " + tRsp.statusCode() + " " + tRsp.body());
            return false;
        }
        System.out.println("[" + name() + "] logged in");
        return true;
    }

    /** Maps instrument names to ids for all markets the firm is authorized for. */
    private void loadInstruments() throws Exception {
        JsonObject tMarkets = JsonParser.parseString(get("/api/markets").body()).getAsJsonObject();
        for (JsonElement tMarket : tMarkets.getAsJsonArray("markets")) {
            int tMarketId = tMarket.getAsJsonObject().get("id").getAsInt();
            JsonObject tInstruments = JsonParser.parseString(get("/api/instruments?marketId=" + tMarketId).body()).getAsJsonObject();
            for (JsonElement tInstrument : tInstruments.getAsJsonArray("instruments")) {
                JsonObject i = tInstrument.getAsJsonObject();
                // A name used in several markets is ambiguous; it must then be given as instrumentId
                mInstrumentIds.merge(i.get("name").getAsString(), i.get("id").getAsLong(), (a, b) -> -1L);
            }
        }
    }

    // ── Directives ────────────────────────────────────────────────────────

    private void execute(JsonObject pDirective) throws Exception {
        String tType = pDirective.has("type") ? pDirective.get("type").getAsString() : "";
        switch (tType) {
            case "AddOrder"    -> addOrder(pDirective);
            case "DeleteOrder" -> deleteOrder(pDirective);
            default            -> fail("unknown directive type '" + tType + "': " + pDirective);
        }
    }

    private void addOrder(JsonObject pDirective) throws Exception {
        long tInstrumentId;
        if (pDirective.has("instrumentId")) {
            tInstrumentId = pDirective.get("instrumentId").getAsLong();
        } else {
            String tName = pDirective.get("instrument").getAsString();
            Long tId = mInstrumentIds.get(tName);
            if (tId == null) { fail("AddOrder: unknown instrument '" + tName + "'");                          return; }
            if (tId < 0)     { fail("AddOrder: instrument name '" + tName + "' is ambiguous, use instrumentId"); return; }
            tInstrumentId = tId;
        }
        JsonObject tBody = new JsonObject();
        tBody.addProperty("instrumentId", tInstrumentId);
        tBody.addProperty("side",         pDirective.get("side").getAsString());
        tBody.addProperty("price",        Math.round(pDirective.get("price").getAsDouble()    * SCALE));
        tBody.addProperty("quantity",     Math.round(pDirective.get("quantity").getAsDouble() * SCALE));
        if (pDirective.has("userRef")) tBody.addProperty("userRef", pDirective.get("userRef").getAsString());

        HttpResponse<String> tRsp = timed(() -> post("/api/orders", tBody.toString()));
        if (tRsp.statusCode() == 201) {
            JsonObject tOrder = JsonParser.parseString(tRsp.body()).getAsJsonObject();
            if (pDirective.has("ref")) mOrderIds.put(pDirective.get("ref").getAsString(), tOrder.get("orderId").getAsString());
            ok("AddOrder", tRsp);
        } else {
            fail("AddOrder " + tRsp.statusCode() + " " + tRsp.body());
        }
    }

    private void deleteOrder(JsonObject pDirective) throws Exception {
        String tRef     = pDirective.get("ref").getAsString();
        String tOrderId = mOrderIds.remove(tRef);
        if (tOrderId == null) { fail("DeleteOrder: no order added with ref '" + tRef + "'"); return; }

        HttpResponse<String> tRsp = timed(() -> send(HttpRequest.newBuilder(URI.create(mUrl + "/api/orders/" + tOrderId)).DELETE()));
        if (tRsp.statusCode() == 200) {
            ok("DeleteOrder", tRsp);
        } else {
            // 404 also happens when the order was fully matched before the delete
            fail("DeleteOrder " + tRef + " (" + tOrderId + ") " + tRsp.statusCode() + " " + tRsp.body());
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private interface Call { HttpResponse<String> call() throws Exception; }

    /** Runs a request that counts as a transaction. */
    private HttpResponse<String> timed(Call pCall) throws Exception {
        long tStart = System.currentTimeMillis();
        HttpResponse<String> tRsp = pCall.call();
        mTotalMs += System.currentTimeMillis() - tStart;
        mSent++;
        return tRsp;
    }

    private HttpResponse<String> get(String pPath) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(mUrl + pPath)).GET());
    }

    private HttpResponse<String> post(String pPath, String pBody) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(mUrl + pPath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pBody)));
    }

    private HttpResponse<String> send(HttpRequest.Builder pRequest) throws Exception {
        return mHttp.send(pRequest.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ok(String pWhat, HttpResponse<String> pRsp) {
        mOk++;
        if (mVerbose) System.out.println("[" + name() + "] " + pWhat + " " + pRsp.statusCode() + " " + pRsp.body());
    }

    private void fail(String pMessage) {
        mFailed++;
        System.out.println("[" + name() + "] " + pMessage);
    }
}
