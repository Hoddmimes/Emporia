package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.me.InternalTrade;
import io.javalin.Javalin;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The logged-in user's firm's trades, oldest first, optionally only for one market (?marketId=) and/or
 * one instrument (?instrumentId=): today's from the matching engine, or with ?date=yyyy-MM-dd the
 * trades stored in the database for that day. The market's public trades are in MarketEndpoints.
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
            String tFirm = OrderEndpoints.firm(ctx);
            String tDate = ctx.queryParam("date");
            JsonObject tOut = new JsonObject();
            if (tDate == null || tDate.isBlank()) {
                tOut.add("trades", mCtx.matchingEngine.getTradesByFirm(tFirm, tMarketId, tInstrumentId));
            } else {
                List<Document> tDocs;
                try { tDocs = mCtx.database.listTradesByFirm(tFirm, tDate.trim()); }
                catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date must be yyyy-MM-dd"); return; }
                List<InternalTrade> tTrades = new ArrayList<>();
                for (Document tDoc : tDocs) tTrades.add(Database.fromTradeDocument(tDoc));
                Collections.reverse(tTrades);   // stored trades are listed latest first
                tOut.addProperty("date", tDate.trim());
                tOut.add("trades", mCtx.matchingEngine.tradesForFirm(tTrades, tFirm, tMarketId, tInstrumentId));
            }
            ctx.json(tOut.toString());
        });
    }
}
