package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

public class SessionEndpoint implements RestEndpoint {

    public SessionEndpoint(RestApiContext ctx) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/session", ctx -> {
            var tSession = ctx.req().getSession(false);
            String tUser = (tSession != null) ? (String) tSession.getAttribute(RestApiContext.USER) : null;
            String tFirm = (tSession != null) ? (String) tSession.getAttribute(RestApiContext.FIRM) : null;
            if (tUser == null) {
                ctx.status(401).json("{\"authenticated\":false}");
            } else {
                JsonObject tOut = new JsonObject();
                tOut.addProperty("authenticated", true);
                tOut.addProperty("userId", tUser);
                tOut.addProperty("firm",   tFirm != null ? tFirm : "");
                JsonArray tMarkets = (JsonArray) tSession.getAttribute(RestApiContext.MARKETS);
                tOut.add("markets", tMarkets != null ? tMarkets : new JsonArray());
                ctx.json(tOut.toString());
            }
        });
    }
}
