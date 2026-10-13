package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.BuildInfo;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

/** The server's build version and date (public, shown on the login pages). */
public class VersionEndpoint implements RestEndpoint {

    public VersionEndpoint(RestApiContext ctx) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/version", ctx -> {
            JsonObject tOut = new JsonObject();
            tOut.addProperty("version",   BuildInfo.version());
            tOut.addProperty("buildDate", BuildInfo.buildDate());
            ctx.json(tOut.toString());
        });
    }
}
