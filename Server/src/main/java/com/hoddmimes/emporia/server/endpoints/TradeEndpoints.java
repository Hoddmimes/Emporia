package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

/**
 * The logged-in user's firm's trades, optionally for one instrument (?instrumentId=).
 */
public class TradeEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public TradeEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/trades", ctx -> {
            Long tInstrumentId = OrderEndpoints.instrumentIdParam(ctx);
            if (tInstrumentId == null) return;
            JsonObject tOut = new JsonObject();
            tOut.add("trades", mCtx.matchingEngine.getTradesByFirm(OrderEndpoints.firm(ctx), tInstrumentId));
            ctx.json(tOut.toString());
        });
    }
}
