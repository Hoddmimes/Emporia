package com.hoddmimes.emporia.simulator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The server's /ws broadcast feed, for the events the simulator does not cause itself: its trades
 * (OwnTrade, including those of its resting orders hit by others), offers on its bulletins
 * (BulletinOffer), its bulletin trades (OwnBulletinTrade) and its bulletins' removal by the server
 * (OwnBulletin REMOVED, traded or expired). The other messages are ignored. A lost connection is
 * opened again after 5 s (logging in again first if the session is gone).
 */
class BdxListener implements WebSocket.Listener {

    private static final long PING_SECONDS      = 25;
    private static final long RECONNECT_SECONDS = 5;

    private final Api                      mApi;
    private final ScheduledExecutorService mScheduler;
    private final Map<Long, String>        mInstrumentNames;   // instrumentId -> name
    private final StringBuilder            mPartial = new StringBuilder();
    private WebSocket          mSocket;
    private ScheduledFuture<?> mPinger;

    BdxListener(Api pApi, ScheduledExecutorService pScheduler, Map<Long, String> pInstrumentNames) {
        mApi             = pApi;
        mScheduler       = pScheduler;
        mInstrumentNames = pInstrumentNames;
    }

    synchronized void connect() {
        URI tUri = URI.create(mApi.url().replaceFirst("^http", "ws") + "/ws");
        try {
            WebSocket tSocket = mApi.http().newWebSocketBuilder().buildAsync(tUri, this).get(10, TimeUnit.SECONDS);
            mSocket = tSocket;
            mPinger = mScheduler.scheduleAtFixedRate(() -> tSocket.sendText("ping", true),
                    PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
            Events.print("BDX", "connected to " + tUri);
        } catch (Exception e) {
            Events.print("ERROR", "bdx connect to " + tUri + ": " + e.getMessage());
            reconnectLater(mSocket, true);
        }
    }

    // Called for a failed connect, and on close and error, which can both come for the same connection
    private synchronized void reconnectLater(WebSocket pSocket, boolean pLogin) {
        if (pSocket != mSocket) return;          // a reconnect is already scheduled for it
        mSocket = null;
        if (mPinger != null) mPinger.cancel(false);
        mScheduler.schedule(() -> {
            if (pLogin) {
                // The upgrade is refused (401) without a logged-in session
                try { mApi.login(); } catch (Exception e) { Events.print("ERROR", e.getMessage()); }
            }
            connect();
        }, RECONNECT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public CompletionStage<?> onText(WebSocket pSocket, CharSequence pData, boolean pLast) {
        mPartial.append(pData);
        if (pLast) {
            String tMessage = mPartial.toString();
            mPartial.setLength(0);
            if (!tMessage.equals("pong")) {
                try { handle(JsonParser.parseString(tMessage).getAsJsonObject()); }
                catch (Exception e) { Events.print("ERROR", "bdx message " + tMessage + ": " + e); }
            }
        }
        pSocket.request(1);
        return null;
    }

    private void handle(JsonObject m) {
        switch (m.get("type").getAsString()) {
            case "OwnTrade" -> Events.print("TRADE", String.format("%-10s %-4s %s @ %s  order %s  trade %s",
                    instrument(m), str(m, "side"), str(m, "quantity"), str(m, "price"), str(m, "orderId"), str(m, "tradeNo")));
            case "BulletinOffer" -> {
                if (!"OWNER".equals(str(m, "role"))) return;
                Events.print("BULLETIN OFFER", String.format("%s  bulletin %s  %s offer %s %s @ %s", str(m, "instrument"),
                        str(m, "bulletinId"), str(m, "event"), str(m, "side"), str(m, "quantity"), str(m, "price")));
            }
            case "OwnBulletinTrade" -> Events.print("BULLETIN TRADE", String.format("%s %s %s @ %s  bulletin %s",
                    str(m, "instrument"), str(m, "side"), str(m, "quantity"), str(m, "price"), str(m, "bulletinId")));
            case "OwnBulletin" -> {
                String tState = str(m, "state");
                if ("REMOVED".equals(str(m, "event")) && ("TRADED".equals(tState) || "EXPIRED".equals(tState) || "DELETED".equals(tState))) {
                    Events.print("BULLETIN " + tState, str(m, "instrument") + "  bulletin " + str(m, "bulletinId"));
                }
            }
            default -> { }
        }
    }

    private String instrument(JsonObject m) {
        if (!m.has("instrumentId")) return "?";
        long tId = m.get("instrumentId").getAsLong();
        return mInstrumentNames.getOrDefault(tId, String.valueOf(tId));
    }

    private static String str(JsonObject m, String pField) {
        if (!m.has(pField) || m.get(pField).isJsonNull()) return "-";
        JsonElement e = m.get(pField);
        // Prices and quantities are decimals in bdx messages: 18 rather than 18.0
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsBigDecimal().stripTrailingZeros().toPlainString();
        }
        return e.getAsString();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket pSocket, int pStatusCode, String pReason) {
        if (pSocket != mSocket) return null;
        Events.print("BDX", "closed: " + pStatusCode + " " + pReason + ", reconnecting in " + RECONNECT_SECONDS + " s");
        reconnectLater(pSocket, true);
        return null;
    }

    @Override
    public void onError(WebSocket pSocket, Throwable pError) {
        if (pSocket != mSocket) return;
        Events.print("BDX", "error: " + pError + ", reconnecting in " + RECONNECT_SECONDS + " s");
        reconnectLater(pSocket, true);
    }
}
