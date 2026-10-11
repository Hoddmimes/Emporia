package com.hoddmimes.emporia.server.endpoints;

import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogoutEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(LogoutEndpoint.class);

    private final RestApiContext mCtx;

    public LogoutEndpoint(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/logout", ctx -> {
            var tSession = ctx.req().getSession(false);
            if (tSession != null) {
                cLog.info("User '{}' logged out", tSession.getAttribute(RestApiContext.USER));
                mCtx.bdx.closeClients(tSession.getId(), "Logged out");
                RestApiContext.logout(tSession, RestApiContext.USER, RestApiContext.FIRM, RestApiContext.MARKETS);
            }
            ctx.json("{\"success\":true}");
        });
    }
}
