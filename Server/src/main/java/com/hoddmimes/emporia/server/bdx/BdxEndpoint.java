package com.hoddmimes.emporia.server.bdx;

import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.endpoints.RestEndpoint;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
                throw new UnauthorizedResponse("Not authenticated");
            }
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String tUser = ctx.sessionAttribute(RestApiContext.USER);
                String tFirm = ctx.sessionAttribute(RestApiContext.FIRM);
                var tUpgradeReq = (JettyServerUpgradeRequest) ctx.session.getUpgradeRequest();
                String tHttpSessionId = ((HttpSession) tUpgradeReq.getSession()).getId();
                mCtx.bdx.addClient(ctx, tHttpSessionId, tFirm, mCtx.authorizedMarkets(tFirm));
                ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
                cLog.info("WebSocket connected: user '{}' firm '{}' ({} clients)", tUser, tFirm, mCtx.bdx.clientCount());
            });
            ws.onMessage(ctx -> {
                if ("ping".equals(ctx.message())) BdxDistributor.send(ctx, "pong");
            });
            ws.onClose(ctx -> {
                ctx.disableAutomaticPings();
                mCtx.bdx.removeClient(ctx);
                cLog.info("WebSocket closed ({} clients)", mCtx.bdx.clientCount());
            });
            ws.onError(ctx -> {
                ctx.disableAutomaticPings();
                mCtx.bdx.removeClient(ctx);
            });
        });
    }
}
