package com.hoddmimes.emporia.server.endpoints;

import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AdminLogoutEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(AdminLogoutEndpoint.class);

    public AdminLogoutEndpoint(RestApiContext ctx) {}

    @Override
    public void register(Javalin app) {
        app.post("/api/admin/logout", ctx -> {
            var tSession = ctx.req().getSession(false);
            if (tSession != null) {
                cLog.info("Admin '{}' logged out", tSession.getAttribute(RestApiContext.ADMIN_USER));
                RestApiContext.logout(tSession, RestApiContext.ADMIN_USER);
            }
            ctx.json("{\"success\":true}");
        });
    }
}
