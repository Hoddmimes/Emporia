package com.hoddmimes.emporia.server.bdx;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.me.InternalTrade;
import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Distributes broadcast (bdx) messages to the trading clients connected on the /ws WebSocket.
 *
 * A public message goes to every client whose firm is authorized for the message's market, a
 * private message only to the clients of its firm. The authorized markets are resolved when the
 * client connects and replaced by updateAuthorizations when the admin changes the authorizations or
 * the markets; a client that gets a new market is sent its snapshot messages. The latest PriceUpdate per instrument and MarketState per market are kept and
 * sent to a client when it connects. Clients are tracked by HTTP session, so a logout or session
 * timeout can close the session's sockets.
 *
 * publish() only queues the message, so the publisher (the matching engine) never waits for the
 * clients. The distributor's own thread, started with start(), takes the messages off the queue and
 * sends them to the clients. The WebSocket sends are asynchronous too, so a slow client does not hold
 * up the others; a client with more than MAX_PENDING_SENDS unsent messages is disconnected.
 *
 * The distributor publishes a MarketState message itself whenever a market's trading status
 * changes in the instrument cache.
 *
 * Every connection is logged at info level when it is added and when it goes, with an id (ws1, ws2,
 * ..., restarting with the server) that ties the two lines together, the user, firm and remote address;
 * the disconnect line also has the close code and reason (or the server's reason: logout, session
 * timeout, error), how long the client was connected and how many messages it was sent.
 */
public class BdxDistributor extends Thread {

    private static final Logger cLog = LogManager.getLogger(BdxDistributor.class);

    private static final Set<String> SNAPSHOT_TYPES = Set.of(BdxMessage.PRICE_UPDATE, BdxMessage.MARKET_STATE);
    public static final int MAX_PENDING_SENDS = 10000;

    /**
     * A connected client; sender and closer deliver to its WebSocket.
     */
    private static final class Client {
        final String           connectionId;
        final String           httpSessionId;
        final String           user;
        final String           firm;
        final String           remoteAddress;
        volatile Set<Integer>  authorizedMarkets;   // replaced by updateAuthorizations
        final Consumer<String> sender;
        final Consumer<String> closer;
        final long             connectTime = System.currentTimeMillis();
        final AtomicLong       sent        = new AtomicLong();

        Client(String pConnectionId, String pHttpSessionId, String pUser, String pFirm, String pRemoteAddress,
               Set<Integer> pAuthorizedMarkets, Consumer<String> pSender, Consumer<String> pCloser) {
            connectionId      = pConnectionId;
            httpSessionId     = pHttpSessionId;
            user              = pUser;
            firm              = pFirm;
            remoteAddress     = pRemoteAddress;
            authorizedMarkets = pAuthorizedMarkets;
            sender            = pSender;
            closer            = pCloser;
        }

        boolean receives(BdxMessage pMessage) {
            return (pMessage.firm() != null) ? pMessage.firm().equals(firm)
                    : authorizedMarkets.contains(pMessage.marketId());
        }

        void send(String pJson) {
            sent.incrementAndGet();
            sender.accept(pJson);
        }

        /** "ws3 user 'ua' firm 'FA' from 10.0.0.5" */
        String who() {
            return connectionId + " user '" + user + "' firm '" + firm + "' from " + remoteAddress;
        }
    }

    private final Map<Object, Client> mClients = new ConcurrentHashMap<>();
    private final AtomicLong mConnectionSeq = new AtomicLong();
    private final Map<String, BdxMessage> mSnapshot = new ConcurrentHashMap<>();   // topic -> latest message
    private final BlockingQueue<BdxMessage> mSendQueue = new LinkedBlockingQueue<>();

    public BdxDistributor(InstrumentCache pInstrumentCache) {
        super("WsPublisher");
        setDaemon(true);
        pInstrumentCache.addMarketSubscription(new InstrumentCache.MarketCallbacks() {
            @Override
            public void marketStateChange(int pMarketId, String pMarketName, String pState) {
                publish(BdxMessage.market(BdxMessage.MARKET_STATE, pMarketId)
                        .put("marketName", pMarketName).put("tradingStatus", pState));
            }

            @Override
            public void marketAdded(JsonObject pMarket) {
            }

            @Override
            public void marketUpdated(JsonObject pMarket) {
            }
        });
    }

    // ── Clients ───────────────────────────────────────────────────────────

    public void addClient(WsContext pCtx, String pHttpSessionId, String pUser, String pFirm, String pRemoteAddress,
                          Set<Integer> pAuthorizedMarkets) {
        addClient(pCtx, pHttpSessionId, pUser, pFirm, pRemoteAddress, pAuthorizedMarkets,
                new WsSender(pCtx),
                tReason -> pCtx.closeSession(1000, tReason));
    }

    // synchronized with dispatch() so a connecting client can't get an older snapshot after a newer message.
    // pKey identifies the connection for removeClient; pSender sends a message to it without waiting,
    // pCloser closes it with a reason (used by the web application's WebSocket, webapp.BdxWebSocket).
    public synchronized void addClient(Object pKey, String pHttpSessionId, String pUser, String pFirm, String pRemoteAddress,
                                       Set<Integer> pAuthorizedMarkets, Consumer<String> pSender, Consumer<String> pCloser) {
        Client tClient = new Client("ws" + mConnectionSeq.incrementAndGet(), pHttpSessionId, pUser, pFirm, pRemoteAddress,
                pAuthorizedMarkets, pSender, pCloser);
        mClients.put(pKey, tClient);
        cLog.info("WebSocket {} connected ({} clients)", tClient.who(), mClients.size());
        // Bring the new client up to date
        mSnapshot.values().forEach(tMessage -> {
            if (tClient.receives(tMessage)) tClient.send(tMessage.toString());
        });
    }

    /**
     * Removes a client whose WebSocket was closed or failed and logs it with pReason (e.g. the close
     * code and reason). A client already removed by closeClients is not logged again.
     */
    public void removeClient(Object pKey, String pReason) {
        Client tClient = mClients.remove(pKey);
        if (tClient != null) logDisconnect(tClient, pReason);
    }

    /**
     * Closes all WebSocket connections opened from the given HTTP session.
     */
    public void closeClients(String pHttpSessionId, String pReason) {
        mClients.forEach((tKey, tClient) -> {
            if (tClient.httpSessionId.equals(pHttpSessionId) && mClients.remove(tKey) != null) {
                logDisconnect(tClient, "closed by the server: " + pReason);
                try {
                    tClient.closer.accept(pReason);
                } catch (Exception e) {
                    cLog.warn("Failed to close WebSocket of session {}: {}", pHttpSessionId, e.getMessage());
                }
            }
        });
    }

    private void logDisconnect(Client pClient, String pReason) {
        long tSeconds = (System.currentTimeMillis() - pClient.connectTime) / 1000;
        cLog.info("WebSocket {} disconnected: {}; connected {}:{}:{}, {} broadcast messages sent ({} clients)",
                pClient.who(), pReason, tSeconds / 3600, String.format("%02d", tSeconds / 60 % 60),
                String.format("%02d", tSeconds % 60), pClient.sent.get(), mClients.size());
    }

    /** A WebSocket error for the log, e.g. "connection error: ClosedChannelException". */
    public static String errorDescription(Throwable pError) {
        if (pError == null) return "connection error";
        return "connection error: " + pError.getClass().getSimpleName()
                + ((pError.getMessage() != null) ? " " + pError.getMessage() : "");
    }

    /**
     * A close code with its meaning for the log, e.g. "code 1001 (going away) 'Idle timeout'". 1006 is
     * not sent by the client: the connection was lost without a close frame.
     */
    public static String closeDescription(int pCode, String pReason) {
        String tMeaning = switch (pCode) {
            case 1000 -> "normal";
            case 1001 -> "going away";
            case 1005 -> "no code given";
            case 1006 -> "connection lost";
            case 1008 -> "policy violation";
            case 1011 -> "server error";
            default   -> null;
        };
        return "code " + pCode + ((tMeaning != null) ? " (" + tMeaning + ")" : "")
                + ((pReason != null && !pReason.isBlank()) ? " '" + pReason + "'" : "");
    }

    /**
     * Replaces each connected client's authorized markets with pMarketsOfFirm(firm) and sends a client
     * the snapshot messages (PriceUpdate, MarketState) of the markets it gained. Synchronized with
     * dispatch(), like addClient, so a client can't get an older snapshot after a newer message. A
     * client losing a market simply stops receiving its public messages.
     */
    public synchronized void updateAuthorizations(Function<String, Set<Integer>> pMarketsOfFirm) {
        Map<String, Set<Integer>> tByFirm = new HashMap<>();
        mClients.values().forEach(tClient -> {
            Set<Integer> tNew = tByFirm.computeIfAbsent(tClient.firm, pMarketsOfFirm);
            Set<Integer> tOld = tClient.authorizedMarkets;
            if (tNew.equals(tOld)) return;
            tClient.authorizedMarkets = tNew;
            cLog.info("WebSocket {} authorized markets changed from {} to {}", tClient.who(), tOld, tNew);
            mSnapshot.values().forEach(tMessage -> {
                if (!tOld.contains(tMessage.marketId()) && tClient.receives(tMessage)) tClient.send(tMessage.toString());
            });
        });
    }

    public int clientCount() {
        return mClients.size();
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /**
     * Queues the message; the distributor thread sends it to every client that should receive it.
     */
    public void publish(BdxMessage pMessage) {
        mSendQueue.add(pMessage);
    }

    @Override
    public void run() {
        try {
            while (true) {
                dispatch(mSendQueue.take());
            }
        } catch (InterruptedException e) {
            cLog.info("Broadcast distributor stopped");
        }
    }

    /**
     * Sends the queued messages on the calling thread, for tests that don't start the distributor thread.
     */
    void dispatchQueued() {
        BdxMessage tMessage;
        while ((tMessage = mSendQueue.poll()) != null) dispatch(tMessage);
    }

    private synchronized void dispatch(BdxMessage pMessage) {
        if (SNAPSHOT_TYPES.contains(pMessage.type())) mSnapshot.put(pMessage.topic(), pMessage);
        String tJson = pMessage.toString();
        mClients.values().forEach(tClient -> {
            if (tClient.receives(pMessage)) tClient.send(tJson);
        });
    }

    /**
     * Sends a message to one WebSocket without waiting for it to be written. Jetty queues the
     * messages per session, so concurrent sends need no locking.
     */
    public static void send(WsContext pCtx, String pMessage) {
        try {
            pCtx.session.getRemote().sendString(pMessage, WriteCallback.NOOP);
        } catch (Exception e) {
            cLog.warn("Failed to send to WebSocket client {}: {}", pCtx.sessionId(), e.getMessage());
        }
    }

    /**
     * Sends to one client's WebSocket asynchronously. A client that falls more than MAX_PENDING_SENDS
     * messages behind is disconnected, instead of letting Jetty's send queue grow without bound.
     */
    private static class WsSender implements Consumer<String> {
        private final WsContext mCtx;
        private final AtomicInteger mPending = new AtomicInteger();
        private final AtomicBoolean mFailed = new AtomicBoolean();

        WsSender(WsContext pCtx) {
            mCtx = pCtx;
        }

        @Override
        public void accept(String pMessage) {
            if (mFailed.get()) return;
            if (mPending.incrementAndGet() > MAX_PENDING_SENDS) {
                mPending.decrementAndGet();
                if (fail("more than " + MAX_PENDING_SENDS + " messages pending"))
                    mCtx.closeSession(1008, "Client too slow");
                return;
            }
            try {
                mCtx.session.getRemote().sendString(pMessage, new WriteCallback() {
                    @Override
                    public void writeSuccess() {
                        mPending.decrementAndGet();
                    }

                    @Override
                    public void writeFailed(Throwable pEx) {
                        mPending.decrementAndGet();
                        fail(pEx.getMessage());
                    }
                });
            } catch (Exception e) {
                mPending.decrementAndGet();
                fail(e.getMessage());
            }
        }

        // Stops further sends and logs the first failure; Jetty reports the socket closed, which removes the client
        private boolean fail(String pReason) {
            if (!mFailed.compareAndSet(false, true)) return false;
            cLog.warn("Failed to send to WebSocket client {}: {}", mCtx.sessionId(), pReason);
            return true;
        }
    }

}