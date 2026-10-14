package com.hoddmimes.emporia.testclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A session's WebSocket to the server's /ws broadcast (bdx) feed. It is opened with the session's
 * HttpClient, so it carries the logged-in HTTP session's cookie. There is nothing to subscribe to:
 * the server sends every public message for the markets the firm is authorized for and every
 * private message (OwnOrder, OwnTrade) for the firm, starting with the latest PriceUpdate and
 * MarketState snapshots. Each received message is printed. A "ping" every 25 s keeps the
 * connection open while nothing is broadcast (the server's idle timeout is 30 s).
 */
class BdxConnection implements WebSocket.Listener {

    private static final long PING_SECONDS = 25;

    private final String        mName;
    private final boolean       mVerbose;                         // print every broadcast message
    private final StringBuilder mPartial  = new StringBuilder();   // a message split over several frames
    private final AtomicInteger mReceived = new AtomicInteger();
    private final CountDownLatch mFirstPong = new CountDownLatch(1);
    private final ScheduledExecutorService mPinger;
    private WebSocket mSocket;

    private BdxConnection(String pName, boolean pVerbose) {
        mName    = pName;
        mVerbose = pVerbose;
        mPinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bdx-ping-" + pName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Connects to the server's /ws (the URL's http/https becomes ws/wss) and returns once the server
     * has registered the connection for broadcasts. The WebSocket handshake can complete before the
     * server has registered it, so this sends a "ping" and waits for its "pong": the server handles
     * the ping only after its connect handler, which registers the client, has run. Broadcasts
     * caused by the session's first transactions are then not missed. pVerbose: print every broadcast
     * message received (otherwise they are only counted).
     */
    static BdxConnection open(HttpClient pHttp, String pUrl, String pName, boolean pVerbose) throws Exception {
        BdxConnection tConnection = new BdxConnection(pName, pVerbose);
        URI tUri = URI.create(pUrl.replaceFirst("^http", "ws") + "/ws");
        tConnection.mSocket = pHttp.newWebSocketBuilder().buildAsync(tUri, tConnection).get(10, TimeUnit.SECONDS);
        tConnection.mSocket.sendText("ping", true);
        if (!tConnection.mFirstPong.await(10, TimeUnit.SECONDS)) {
            tConnection.close();
            throw new IllegalStateException("bdx WebSocket connected, but no reply to its first ping within 10 s");
        }
        tConnection.mPinger.scheduleAtFixedRate(() -> tConnection.mSocket.sendText("ping", true),
                PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        System.out.println("[" + pName + "] bdx connected to " + tUri);
        return tConnection;
    }

    int received() { return mReceived.get(); }

    /** Stops the pings and closes the WebSocket. */
    void close() {
        mPinger.shutdownNow();
        try {
            mSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // the server may already have closed it (logout, session timeout)
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket pSocket, CharSequence pData, boolean pLast) {
        mPartial.append(pData);
        if (pLast) {
            String tMessage = mPartial.toString();
            mPartial.setLength(0);
            if (tMessage.equals("pong")) {
                mFirstPong.countDown();
            } else {
                mReceived.incrementAndGet();
                if (mVerbose) System.out.println("[" + mName + "] bdx: " + tMessage);
            }
        }
        pSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket pSocket, int pStatusCode, String pReason) {
        mPinger.shutdownNow();
        System.out.println("[" + mName + "] bdx closed: " + pStatusCode + " " + pReason);
        return null;
    }

    @Override
    public void onError(WebSocket pSocket, Throwable pError) {
        mPinger.shutdownNow();
        System.out.println("[" + mName + "] bdx error: " + pError);
    }
}
