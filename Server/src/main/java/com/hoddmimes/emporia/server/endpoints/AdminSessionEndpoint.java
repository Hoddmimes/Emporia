package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

public class AdminSessionEndpoint implements RestEndpoint {

    public AdminSessionEndpoint(RestApiContext ctx) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/session", ctx -> {
            var tSession = ctx.req().getSession(false);
            String tUser = (tSession != null) ? (String) tSession.getAttribute(RestApiContext.ADMIN_USER) : null;
            if (tUser != null) {
                JsonObject tOut = new JsonObject();
                tOut.addProperty("loggedIn", true);
                tOut.addProperty("username", tUser);
                ctx.json(tOut.toString());
            } else {
                ctx.status(401);
                ctx.json("{\"loggedIn\":false}");
            }
        });
    }
}
