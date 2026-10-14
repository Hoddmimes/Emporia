package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.SessionUser;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Set;

/**
 * Markets visible to the logged-in trading user, i.e. those the user's firm is authorized for, and
 * each such market's public data: its trades (anonymous), a price snapshot of its instruments and an
 * instrument's whole order book.
 * Prices and quantities are longs scaled by MatchingEngine.SCALE. A market the firm is not authorized
 * for is refused (403), an unknown one is not found (404).
 */
public class MarketEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public MarketEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/markets", ctx -> {
            String tFirm = (String) ctx.req().getSession().getAttribute(RestApiContext.FIRM);
            Set<Integer> tAuthorized = mCtx.authorizedMarkets(tFirm);
            JsonArray tArray = new JsonArray();
            for (var tMarket : mCtx.instrumentCache.getAllMarkets()) {
                if (tAuthorized.contains(tMarket.marketId())) tArray.add(tMarket.toJson());
            }
            JsonObject tOut = new JsonObject();
            tOut.add("markets", tArray);
            ctx.json(tOut.toString());
        });

        // Today's trades in the market, oldest first, optionally for one instrument (?instrumentId=)
        app.get("/api/markets/{marketId}/trades", ctx -> {
            Integer tMarketId = marketIdPath(ctx);
            if (tMarketId == null) return;
            Long tInstrumentId = OrderEndpoints.instrumentIdParam(ctx);
            if (tInstrumentId == null) return;
            JsonObject tOut = new JsonObject();
            tOut.addProperty("marketId", tMarketId);
            tOut.add("trades", mCtx.matchingEngine.getPublicTrades(tMarketId, tInstrumentId));
            ctx.json(tOut.toString());
        });

        // The current prices of every instrument in the market (what the PriceUpdate bdx messages carry)
        app.get("/api/markets/{marketId}/prices", ctx -> {
            Integer tMarketId = marketIdPath(ctx);
            if (tMarketId == null) return;
            JsonObject tOut = new JsonObject();
            tOut.addProperty("marketId", tMarketId);
            tOut.add("prices", mCtx.matchingEngine.getPrices(tMarketId));
            ctx.json(tOut.toString());
        });

        // A snapshot of one instrument's whole order book, aggregated per price level (anonymous, except
        // that each level tells the firm's own quantity)
        app.get("/api/markets/{marketId}/orderbook/{instrumentId}", ctx -> {
            Integer tMarketId = marketIdPath(ctx);
            if (tMarketId == null) return;
            long tInstrumentId;
            try { tInstrumentId = Long.parseLong(ctx.pathParam("instrumentId")); }
            catch (NumberFormatException e) { OrderEndpoints.error(ctx, 400, "instrumentId must be an integer"); return; }
            var tInstrument = mCtx.instrumentCache.getInstrument(tInstrumentId);
            if (tInstrument == null || tInstrument.marketId() != tMarketId) {
                OrderEndpoints.error(ctx, 404, "Instrument " + tInstrumentId + " not found in market " + tMarketId); return;
            }
            ctx.json(mCtx.matchingEngine.getOrderbook(tInstrumentId, SessionUser.of(ctx).firm()).toString());
        });
    }

    /** The {marketId} path parameter, if the market exists and the firm is authorized for it; null after replying otherwise. */
    private Integer marketIdPath(Context ctx) {
        int tMarketId;
        try { tMarketId = Integer.parseInt(ctx.pathParam("marketId")); }
        catch (NumberFormatException e) { OrderEndpoints.error(ctx, 400, "marketId must be an integer"); return null; }
        if (mCtx.instrumentCache.getMarket(tMarketId) == null) {
            OrderEndpoints.error(ctx, 404, "Market not found: " + tMarketId); return null;
        }
        if (!mCtx.isAuthorized(SessionUser.of(ctx).firm(), tMarketId)) {
            OrderEndpoints.error(ctx, 403, "Not authorized for market " + tMarketId); return null;
        }
        return tMarketId;
    }
}
