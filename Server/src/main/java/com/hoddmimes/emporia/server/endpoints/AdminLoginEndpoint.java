package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AdminLoginEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(AdminLoginEndpoint.class);

    private final RestApiContext mCtx;

    public AdminLoginEndpoint(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/admin/login", ctx -> {
            JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tUsername = tBody.has("username") ? tBody.get("username").getAsString() : "";
            String tPassword = tBody.has("password") ? tBody.get("password").getAsString() : "";
            if (mCtx.database.verifyAdminLogin(tUsername, tPassword)) {
                mCtx.database.updateLastSeen(tUsername);
                var tSession = ctx.req().getSession(true);
                tSession.setAttribute(RestApiContext.ADMIN_USER, tUsername);
                tSession.setMaxInactiveInterval(mCtx.sessionInactivitySeconds);
                cLog.info("Admin '{}' logged in", tUsername);
                ctx.json("{\"success\":true}");
            } else {
                cLog.warn("Failed admin login attempt for '{}'", tUsername);
                ctx.status(401);
                ctx.json("{\"success\":false,\"message\":\"Invalid username or password\"}");
            }
        });
    }
}
