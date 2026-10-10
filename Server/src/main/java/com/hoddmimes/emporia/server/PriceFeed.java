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
 */
public class PriceFeed {

    private static final Logger cLog = LogManager.getLogger(PriceFeed.class);

    private final InstrumentCache mInstrumentCache;
    private final ConcurrentHashMap<WsContext, Set<Integer>> mClients = new ConcurrentHashMap<>();

    public PriceFeed(InstrumentCache pInstrumentCache) {
        mInstrumentCache = pInstrumentCache;
    }

    public void addClient(WsContext pCtx, Set<Integer> pAuthorizedMarkets) {
        mClients.put(pCtx, pAuthorizedMarkets);
    }

    public void removeClient(WsContext pCtx) {
        mClients.remove(pCtx);
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
        mClients.forEach((tCtx, tMarkets) -> {
            if (tMarkets.contains(tInstrument.marketId())) send(tCtx, tMessage);
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
