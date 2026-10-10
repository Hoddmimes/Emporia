package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

public class AdminPasswordEndpoint implements RestEndpoint {

    private final RestApiContext mCtx;

    public AdminPasswordEndpoint(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/admin/password", ctx -> {
            String tUsername = (String) ctx.req().getSession(false).getAttribute(RestApiContext.ADMIN_USER);
            JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tCurrent  = tBody.has("currentPassword") ? tBody.get("currentPassword").getAsString() : "";
            String tNew      = tBody.has("newPassword")      ? tBody.get("newPassword").getAsString()     : "";
            if (tNew.isEmpty()) {
                ctx.status(400);
                ctx.json("{\"error\":\"New password must not be empty\"}");
                return;
            }
            if (mCtx.database.changeAdminPassword(tUsername, tCurrent, tNew)) {
                ctx.json("{\"success\":true}");
            } else {
                ctx.status(400);
                ctx.json("{\"error\":\"Current password is incorrect\"}");
            }
        });
    }
}
