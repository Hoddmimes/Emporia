package com.hoddmimes.emporia.server.endpoints;

import com.hoddmimes.emporia.server.PriceFeed;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The /ws WebSocket on which trading clients receive price updates. The upgrade request
 * must belong to an HTTP session with a logged-in trading user (the same session cookie
 * as the REST calls). The client may send "ping" (answered with "pong"); other messages are ignored.
 */
public class PriceFeedEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(PriceFeedEndpoint.class);

    private final RestApiContext mCtx;

    public PriceFeedEndpoint(RestApiContext ctx) {
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
                mCtx.priceFeed.addClient(ctx, mCtx.authorizedMarkets(tFirm));
                cLog.info("WebSocket connected: user '{}' firm '{}' ({} clients)", tUser, tFirm, mCtx.priceFeed.clientCount());
            });
            ws.onMessage(ctx -> {
                if ("ping".equals(ctx.message())) PriceFeed.send(ctx, "pong");
            });
            ws.onClose(ctx -> {
                mCtx.priceFeed.removeClient(ctx);
                cLog.info("WebSocket closed ({} clients)", mCtx.priceFeed.clientCount());
            });
            ws.onError(ctx -> mCtx.priceFeed.removeClient(ctx));
        });
    }
}
