package com.hoddmimes.emporia.server.bdx;

import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.endpoints.RestEndpoint;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The /ws WebSocket on which trading clients receive broadcast (bdx) messages. The upgrade
 * request must belong to an HTTP session with a logged-in trading user (the same session cookie
 * as the REST calls). The client may send "ping" (answered with "pong"); other messages are ignored.
 * Logging out or the session timing out closes the session's WebSockets.
 */
public class BdxEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(BdxEndpoint.class);

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
                cLog.info("WebSocket connected: user '{}' firm '{}' ({} clients)", tUser, tFirm, mCtx.bdx.clientCount());
            });
            ws.onMessage(ctx -> {
                if ("ping".equals(ctx.message())) BdxDistributor.send(ctx, "pong");
            });
            ws.onClose(ctx -> {
                mCtx.bdx.removeClient(ctx);
                cLog.info("WebSocket closed ({} clients)", mCtx.bdx.clientCount());
            });
            ws.onError(ctx -> mCtx.bdx.removeClient(ctx));
        });
    }
}
