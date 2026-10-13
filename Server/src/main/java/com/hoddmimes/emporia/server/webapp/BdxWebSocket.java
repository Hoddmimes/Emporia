package com.hoddmimes.emporia.server.webapp;

import com.hoddmimes.emporia.server.RestApi;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The /ws broadcast (bdx) WebSocket in the web application, as a standard Jakarta WebSocket endpoint
 * served by Tomcat (the standalone server uses Javalin's, bdx.BdxEndpoint, which needs Jetty). It
 * behaves the same: the connection must belong to an HTTP session with a logged-in trading user (here it
 * is closed with 1008 at once otherwise, since a Jakarta endpoint can't refuse the upgrade with 401),
 * "ping" is answered with "pong", the server sends a ping frame every PING_SECONDS, the idle timeout is
 * RestApi.WS_IDLE_TIMEOUT, and the client is registered with the BdxDistributor.
 *
 * Tomcat allows one asynchronous send at a time per connection, so each connection has its own queue:
 * a message is sent when the previous one is written (on the WsPinger thread). More than MAX_PENDING_SENDS queued messages
 * disconnect the client (1008), as in the standalone server.
 */
public class BdxWebSocket extends Endpoint {

    private static final Logger cLog = LogManager.getLogger(BdxWebSocket.class);

    public static final long PING_SECONDS = 20;
    private static final String HTTP_SESSION = "httpSession";

    private final RestApiContext           mCtx;
    private final ScheduledExecutorService mPinger;
    private final Map<Session, Sender>     mSenders = new ConcurrentHashMap<>();

    BdxWebSocket(RestApiContext pCtx, ScheduledExecutorService pPinger) {
        mCtx    = pCtx;
        mPinger = pPinger;
    }

    /** The endpoint's configuration at /ws: one endpoint instance for all connections, the HTTP session kept. */
    static ServerEndpointConfig config(BdxWebSocket pEndpoint) {
        return ServerEndpointConfig.Builder.create(BdxWebSocket.class, "/ws")
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public void modifyHandshake(ServerEndpointConfig pConfig, HandshakeRequest pRequest, HandshakeResponse pResponse) {
                        // The configuration's user properties are per connection in Tomcat
                        if (pRequest.getHttpSession() != null) pConfig.getUserProperties().put(HTTP_SESSION, pRequest.getHttpSession());
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getEndpointInstance(Class<T> pClass) {
                        return (T) pEndpoint;
                    }
                })
                .build();
    }

    @Override
    public void onOpen(Session pSession, EndpointConfig pConfig) {
        HttpSession tHttpSession = (HttpSession) pConfig.getUserProperties().get(HTTP_SESSION);
        String tUser = null, tFirm = null;
        try {
            if (tHttpSession != null) {
                tUser = (String) tHttpSession.getAttribute(RestApiContext.USER);
                tFirm = (String) tHttpSession.getAttribute(RestApiContext.FIRM);
            }
        } catch (IllegalStateException e) {
            // the HTTP session was invalidated meanwhile
        }
        if (tUser == null) {
            close(pSession, CloseReason.CloseCodes.VIOLATED_POLICY, "Not authenticated");
            return;
        }
        pSession.setMaxIdleTimeout(RestApi.WS_IDLE_TIMEOUT.toMillis());
        Sender tSender = new Sender(pSession, mPinger);
        mSenders.put(pSession, tSender);
        pSession.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String pMessage) {
                if ("ping".equals(pMessage)) tSender.accept("pong");
            }
        });
        tSender.pinging = mPinger.scheduleAtFixedRate(tSender::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        mCtx.bdx.addClient(pSession, tHttpSession.getId(), tFirm, mCtx.authorizedMarkets(tFirm), tSender,
                tReason -> close(pSession, CloseReason.CloseCodes.NORMAL_CLOSURE, tReason));
        cLog.info("WebSocket connected: user '{}' firm '{}' ({} clients)", tUser, tFirm, mCtx.bdx.clientCount());
    }

    @Override
    public void onClose(Session pSession, CloseReason pReason) {
        removed(pSession);
        cLog.info("WebSocket closed ({} clients)", mCtx.bdx.clientCount());
    }

    @Override
    public void onError(Session pSession, Throwable pError) {
        removed(pSession);
    }

    private void removed(Session pSession) {
        Sender tSender = mSenders.remove(pSession);
        if (tSender != null && tSender.pinging != null) tSender.pinging.cancel(false);
        mCtx.bdx.removeClient(pSession);
    }

    private static void close(Session pSession, CloseReason.CloseCodes pCode, String pReason) {
        try {
            pSession.close(new CloseReason(pCode, pReason));
        } catch (Exception e) {
            cLog.warn("Failed to close WebSocket {}: {}", pSession.getId(), e.getMessage());
        }
    }

    /** Sends one connection's messages in order, one asynchronous send at a time. */
    private static class Sender implements Consumer<String> {
        private final Session       mSession;
        private final java.util.concurrent.Executor mExecutor;   // runs the next send after a completion
        private final Queue<String> mQueue = new ArrayDeque<>();
        private boolean             mSending;
        private boolean             mFailed;
        volatile java.util.concurrent.ScheduledFuture<?> pinging;

        Sender(Session pSession, java.util.concurrent.Executor pExecutor) {
            mSession  = pSession;
            mExecutor = pExecutor;
        }

        @Override
        public void accept(String pMessage) {
            synchronized (this) {
                if (mFailed) return;
                if (mQueue.size() >= BdxDistributor.MAX_PENDING_SENDS) {
                    fail("more than " + BdxDistributor.MAX_PENDING_SENDS + " messages pending");
                    close(mSession, CloseReason.CloseCodes.VIOLATED_POLICY, "Client too slow");
                    return;
                }
                mQueue.add(pMessage);
                if (mSending) return;           // sent when the current send completes
                mSending = true;
            }
            sendNext();
        }

        private void sendNext() {
            String tMessage;
            synchronized (this) {
                tMessage = mFailed ? null : mQueue.poll();
                if (tMessage == null) { mSending = false; return; }
            }
            try {
                mSession.getAsyncRemote().sendText(tMessage, pResult -> {
                    if (!pResult.isOK()) {
                        synchronized (this) { fail(String.valueOf(pResult.getException())); }
                        return;
                    }
                    // Not on the completing thread: Tomcat may complete a send at once, inside sendText,
                    // so sending the next one here could nest one call per queued message
                    mExecutor.execute(this::sendNext);
                });
            } catch (Exception e) {
                synchronized (this) { fail(e.getMessage()); }
            }
        }

        // A ping frame, unless a message is being sent (which is traffic on the connection as well)
        void ping() {
            synchronized (this) {
                if (mSending || mFailed) return;
                mSending = true;
            }
            try {
                mSession.getAsyncRemote().sendPing(ByteBuffer.allocate(0));
            } catch (Exception e) {
                synchronized (this) { fail(e.getMessage()); }
            }
            sendNext();                          // also clears mSending
        }

        // Stops further sends and logs the first failure; Tomcat reports the socket closed, which removes the client
        private void fail(String pReason) {
            if (mFailed) return;
            mFailed = true;
            mQueue.clear();
            cLog.warn("Failed to send to WebSocket client {}: {}", mSession.getId(), pReason);
        }
    }
}
