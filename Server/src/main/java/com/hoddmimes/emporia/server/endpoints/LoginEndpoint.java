package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LoginEndpoint implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(LoginEndpoint.class);

    private final RestApiContext mCtx;

    public LoginEndpoint(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/login", ctx -> {
            JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tFirm     = tBody.has("firm")     ? tBody.get("firm").getAsString()     : "";
            String tUserId   = tBody.has("userId")   ? tBody.get("userId").getAsString()   : "";
            String tPassword = tBody.has("password") ? tBody.get("password").getAsString() : "";

            JsonObject tResult = mCtx.database.validateUserLogin(tFirm, tUserId, tPassword);
            if (tResult.get("success").getAsBoolean()) {
                var tSession = ctx.req().getSession(true);
                tSession.setAttribute(RestApiContext.USER, tUserId);
                tSession.setAttribute(RestApiContext.FIRM, tFirm);
                tSession.setAttribute(RestApiContext.MARKETS, mCtx.authorizedMarketsAsJson(tFirm));
                tSession.setMaxInactiveInterval(mCtx.sessionInactivitySeconds);
                cLog.info("User '{}' from firm '{}' logged in", tUserId, tFirm);
                JsonObject tOut = new JsonObject();
                tOut.addProperty("success",   true);
                tOut.addProperty("sessionId", tSession.getId());
                ctx.cookie(RestApiContext.getLoginCookie(tFirm, tUserId, tSession.getId()));
                ctx.json(tOut.toString());
            } else {
                String tErrMsg = tResult.has("message") ? tResult.get("message").getAsString() : "Login failed";
                cLog.warn("Failed login attempt for user '{}' firm '{}': {}", tUserId, tFirm, tErrMsg);
                ctx.status(401).json(RestApiContext.statusMessage(tErrMsg));
            }
        });
    }
}
