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
 */
public class PriceFeed {

    private static final Logger cLog = LogManager.getLogger(PriceFeed.class);

    private final InstrumentCache mInstrumentCache;
    private record Client(String httpSessionId, Set<Integer> authorizedMarkets) {}

    private final ConcurrentHashMap<WsContext, Client> mClients = new ConcurrentHashMap<>();

    public PriceFeed(InstrumentCache pInstrumentCache) {
        mInstrumentCache = pInstrumentCache;
    }

    public void addClient(WsContext pCtx, String pHttpSessionId, Set<Integer> pAuthorizedMarkets) {
        mClients.put(pCtx, new Client(pHttpSessionId, pAuthorizedMarkets));
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
    public void publish(PriceUpdate pUpdate) {
        InstrumentCache.InstrumentEntry tInstrument = mInstrumentCache.getInstrument(pUpdate.instrumentId());
        if (tInstrument == null) {
            cLog.warn("Price update for unknown instrument {} dropped", pUpdate.instrumentId());
            return;
        }
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
