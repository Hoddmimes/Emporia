package com.hoddmimes.emporia.server;

import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributes price updates to the trading clients connected on the /ws WebSocket.
 * Each connection only receives updates for instruments in markets its firm is
 * authorized for; the authorized markets are resolved when the client connects.
 * Clients are tracked by HTTP session so a logout can close the session's sockets.
 * The latest update per instrument is kept and sent to a client when it connects.
 */
public class PriceFeed {

    private static final Logger cLog = LogManager.getLogger(PriceFeed.class);

    private final InstrumentCache mInstrumentCache;
    private record Client(String httpSessionId, Set<Integer> authorizedMarkets) {}

    private final ConcurrentHashMap<WsContext, Client>    mClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PriceUpdate>    mLatest  = new ConcurrentHashMap<>();

    public PriceFeed(InstrumentCache pInstrumentCache) {
        mInstrumentCache = pInstrumentCache;
    }

    // synchronized with publish() so a connecting client can't get an older snapshot after a newer update
    public synchronized void addClient(WsContext pCtx, String pHttpSessionId, Set<Integer> pAuthorizedMarkets) {
        mClients.put(pCtx, new Client(pHttpSessionId, pAuthorizedMarkets));
        // Bring the new client up to date
        mLatest.values().forEach(tUpdate -> {
            InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(tUpdate.instrumentId());
            if (tInstrument != null && pAuthorizedMarkets.contains(tInstrument.marketId())) {
                send(pCtx, tUpdate.toJson().toString());
            }
        });
    }

    public void removeClient(WsContext pCtx) {
        mClients.remove(pCtx);
    }

    /** Closes all WebSocket connections opened from the given HTTP session. */
    public void closeClients(String pHttpSessionId, String pReason) {
        mClients.forEach((tCtx, tClient) -> {
            if (tClient.httpSessionId().equals(pHttpSessionId)) {
                mClients.remove(tCtx);
                try { tCtx.closeSession(1000, pReason); }
                catch (Exception e) { cLog.warn("Failed to close WebSocket {}: {}", tCtx.session.getRemoteAddress(), e.getMessage()); }
            }
        });
    }

    public int clientCount() {
        return mClients.size();
    }

    /** Sends the update to every client authorized for the instrument's market. */
    public synchronized void publish(PriceUpdate pUpdate) {
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(pUpdate.instrumentId());
        if (tInstrument == null) {
            cLog.warn("Price update for unknown instrument {} dropped", pUpdate.instrumentId());
            return;
        }
        mLatest.put(pUpdate.instrumentId(), pUpdate);
        String tMessage = pUpdate.toJson().toString();
        mClients.forEach((tCtx, tClient) -> {
            if (tClient.authorizedMarkets().contains(tInstrument.marketId())) send(tCtx, tMessage);
        });
    }

    public static void send(WsContext pCtx, String pMessage) {
        try {
            // Jetty does not allow concurrent blocking sends on one WebSocket session
            synchronized (pCtx) {
                pCtx.send(pMessage);
            }
        } catch (Exception e) {
            cLog.warn("Failed to send to WebSocket client {}: {}", pCtx.session.getRemoteAddress(), e.getMessage());
        }
    }
}
