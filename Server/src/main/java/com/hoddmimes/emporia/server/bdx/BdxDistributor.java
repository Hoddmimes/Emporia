package com.hoddmimes.emporia.server.bdx;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache;
import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Distributes broadcast (bdx) messages to the trading clients connected on the /ws WebSocket.
 *
 * A public message goes to every client whose firm is authorized for the message's market, a
 * private message only to the clients of its firm. The authorized markets are resolved when the
 * client connects. The latest PriceUpdate per instrument and MarketState per market are kept and
 * sent to a client when it connects. Clients are tracked by HTTP session, so a logout or session
 * timeout can close the session's sockets.
 *
 * The distributor publishes a MarketState message itself whenever a market's trading status
 * changes in the instrument cache.
 */
public class BdxDistributor {

    private static final Logger cLog = LogManager.getLogger(BdxDistributor.class);

    private static final Set<String> SNAPSHOT_TYPES = Set.of(BdxMessage.PRICE_UPDATE, BdxMessage.MARKET_STATE);

    /** A connected client; sender and closer deliver to its WebSocket. */
    private record Client(String httpSessionId, String firm, Set<Integer> authorizedMarkets,
                          Consumer<String> sender, Consumer<String> closer) {
        boolean receives(BdxMessage pMessage) {
            return (pMessage.firm() != null) ? pMessage.firm().equals(firm)
                                             : authorizedMarkets.contains(pMessage.marketId());
        }
    }

    private final Map<Object, Client>     mClients  = new ConcurrentHashMap<>();
    private final Map<String, BdxMessage> mSnapshot = new ConcurrentHashMap<>();   // topic -> latest message

    public BdxDistributor(InstrumentCache pInstrumentCache) {
        pInstrumentCache.addMarketSubscription(new InstrumentCache.MarketCallbacks() {
            @Override
            public void marketStateChange(int pMarketId, String pMarketName, String pState) {
                publish(BdxMessage.market(BdxMessage.MARKET_STATE, pMarketId)
                        .put("marketName", pMarketName).put("tradingStatus", pState));
            }
            @Override public void marketAdded(JsonObject pMarket)   {}
            @Override public void marketUpdated(JsonObject pMarket) {}
        });
    }

    // ── Clients ───────────────────────────────────────────────────────────

    public void addClient(WsContext pCtx, String pHttpSessionId, String pFirm, Set<Integer> pAuthorizedMarkets) {
        addClient(pCtx, pHttpSessionId, pFirm, pAuthorizedMarkets,
                tMessage -> send(pCtx, tMessage),
                tReason  -> pCtx.closeSession(1000, tReason));
    }

    // synchronized with publish() so a connecting client can't get an older snapshot after a newer message
    synchronized void addClient(Object pKey, String pHttpSessionId, String pFirm, Set<Integer> pAuthorizedMarkets,
                                Consumer<String> pSender, Consumer<String> pCloser) {
        Client tClient = new Client(pHttpSessionId, pFirm, pAuthorizedMarkets, pSender, pCloser);
        mClients.put(pKey, tClient);
        // Bring the new client up to date
        mSnapshot.values().forEach(tMessage -> {
            if (tClient.receives(tMessage)) tClient.sender().accept(tMessage.toString());
        });
    }

    public void removeClient(Object pKey) {
        mClients.remove(pKey);
    }

    /** Closes all WebSocket connections opened from the given HTTP session. */
    public void closeClients(String pHttpSessionId, String pReason) {
        mClients.forEach((tKey, tClient) -> {
            if (tClient.httpSessionId().equals(pHttpSessionId)) {
                mClients.remove(tKey);
                try { tClient.closer().accept(pReason); }
                catch (Exception e) { cLog.warn("Failed to close WebSocket of session {}: {}", pHttpSessionId, e.getMessage()); }
            }
        });
    }

    public int clientCount() {
        return mClients.size();
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /** Sends the message to every client that should receive it. */
    public synchronized void publish(BdxMessage pMessage) {
        if (SNAPSHOT_TYPES.contains(pMessage.type())) mSnapshot.put(pMessage.topic(), pMessage);
        String tJson = pMessage.toString();
        mClients.values().forEach(tClient -> {
            if (tClient.receives(pMessage)) tClient.sender().accept(tJson);
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
