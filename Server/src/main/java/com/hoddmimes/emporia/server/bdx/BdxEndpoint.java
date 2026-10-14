package com.hoddmimes.emporia.server.bdx;

import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.endpoints.RestEndpoint;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.websocket.WsContext;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * The /ws WebSocket on which trading clients receive broadcast (bdx) messages. The upgrade
 * request must belong to an HTTP session with a logged-in trading user (the same session cookie
 * as the REST calls). The client may send "ping" (answered with "pong"); other messages are ignored.
 * Logging out or the session timing out closes the session's WebSockets.
 *
 * The server sends a WebSocket ping frame every PING_SECONDS. The browser answers it with a pong frame
 * by itself, without running the page's JavaScript, so the connection stays within the idle timeout
 * (RestApi.WS_IDLE_TIMEOUT) also when the page's own "ping" timer is throttled in a background tab.
 *
 * Connections and disconnects are logged at info level by the BdxDistributor; a refused upgrade (no
 * logged-in trading user) is logged here.
 */
public class BdxEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(BdxEndpoint.class);

    /** Interval of the server's WebSocket ping frames; well below RestApi.WS_IDLE_TIMEOUT. */
    public static final long PING_SECONDS = 20;

    private final RestApiContext mCtx;

    public BdxEndpoint(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.wsBeforeUpgrade("/ws", ctx -> {
            var tSession = ctx.req().getSession(false);
            if (tSession == null || tSession.getAttribute(RestApiContext.USER) == null) {
                String tForwarded = ctx.header("X-Forwarded-For");
                cLog.info("WebSocket refused from {}: no logged-in trading user",
                        (tForwarded != null && !tForwarded.isBlank()) ? tForwarded.trim() : ctx.ip());
                throw new UnauthorizedResponse("Not authenticated");
            }
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String tUser = ctx.sessionAttribute(RestApiContext.USER);
                String tFirm = ctx.sessionAttribute(RestApiContext.FIRM);
                var tUpgradeReq = (JettyServerUpgradeRequest) ctx.session.getUpgradeRequest();
                String tHttpSessionId = ((HttpSession) tUpgradeReq.getSession()).getId();
                ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
                mCtx.bdx.addClient(ctx, tHttpSessionId, tUser, tFirm, remoteAddress(ctx), mCtx.authorizedMarkets(tFirm));
            });
            ws.onMessage(ctx -> {
                if ("ping".equals(ctx.message())) BdxDistributor.send(ctx, "pong");
            });
            ws.onClose(ctx -> {
                ctx.disableAutomaticPings();
                mCtx.bdx.removeClient(ctx, BdxDistributor.closeDescription(ctx.status(), ctx.reason()));
            });
            ws.onError(ctx -> {
                ctx.disableAutomaticPings();
                mCtx.bdx.removeClient(ctx, BdxDistributor.errorDescription(ctx.error()));
            });
        });
    }

    /** The client's address: the first X-Forwarded-For address behind a proxy (Apache), else the peer's. */
    private static String remoteAddress(WsContext pCtx) {
        String tForwarded = pCtx.header("X-Forwarded-For");
        if (tForwarded != null && !tForwarded.isBlank()) return tForwarded.split(",")[0].trim();
        SocketAddress tAddress = pCtx.session.getRemoteAddress();
        return (tAddress instanceof InetSocketAddress tInet && tInet.getAddress() != null)
                ? tInet.getAddress().getHostAddress() : String.valueOf(tAddress);
    }
}
