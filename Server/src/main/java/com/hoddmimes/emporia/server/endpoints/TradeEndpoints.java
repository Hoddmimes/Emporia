package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

/**
 * The logged-in user's firm's trades (today's, oldest first), optionally only for one market
 * (?marketId=) and/or one instrument (?instrumentId=). The market's public trades are in MarketEndpoints.
 */
public class TradeEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public TradeEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/trades", ctx -> {
            Integer tMarketId = OrderEndpoints.marketIdParam(ctx);
            if (tMarketId == null) return;
            Long tInstrumentId = OrderEndpoints.instrumentIdParam(ctx);
            if (tInstrumentId == null) return;
            JsonObject tOut = new JsonObject();
            tOut.add("trades", mCtx.matchingEngine.getTradesByFirm(OrderEndpoints.firm(ctx), tMarketId, tInstrumentId));
            ctx.json(tOut.toString());
        });
    }
}
