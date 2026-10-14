package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.SessionUser;
import com.hoddmimes.emporia.server.me.AddOrderRequest;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import com.hoddmimes.emporia.server.me.Order;
import com.hoddmimes.emporia.server.me.OrderResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Order entry for trading users. The order's account is always the user's firm, and a firm
 * can only see, modify and delete its own orders. Adding or modifying an order requires the
 * firm to be authorized for the instrument's market. Prices and quantities are longs scaled
 * by MatchingEngine.SCALE.
 */
public class OrderEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(OrderEndpoints.class);

    private final RestApiContext mCtx;

    public OrderEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/orders", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (!cLog.isTraceEnabled()) {
                cLog.info("{} user: {} rqst: {} ", ctx.path(), SessionUser.of(ctx), tBody );
            }
            if (tBody == null) return;
            String tFirm = firm(ctx);

            if (!tBody.has("instrumentId")) { error(ctx, 400, "instrumentId is required"); return; }
            if (!tBody.has("side"))         { error(ctx, 400, "side is required");         return; }
            if (!tBody.has("price"))        { error(ctx, 400, "price is required");        return; }
            if (!tBody.has("quantity"))     { error(ctx, 400, "quantity is required");     return; }

            long tInstrumentId = tBody.get("instrumentId").getAsLong();
            if (!checkMarketAccess(ctx, tFirm, tInstrumentId)) return;

            Order.Side tSide;
            try { tSide = Order.Side.valueOf(tBody.get("side").getAsString().toUpperCase()); }
            catch (IllegalArgumentException e) { error(ctx, 400, "side must be BUY or SELL"); return; }

            String tUserRef = tBody.has("userRef") ? tBody.get("userRef").getAsString() : null;
            OrderResponse tRsp = mCtx.matchingEngine.addOrder(new AddOrderRequest(tInstrumentId, tSide,
                    tBody.get("price").getAsLong(), tBody.get("quantity").getAsLong(), tFirm, tUserRef));
            respond(ctx, 201, tRsp);
        });

        app.patch("/api/orders/{orderId}", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            if (!cLog.isTraceEnabled()) {
                cLog.info("{} user: {} rqst: {} ", ctx.path(), SessionUser.of(ctx), tBody );
            }
            String tFirm    = firm(ctx);
            String tOrderId = ctx.pathParam("orderId");
            if (!tBody.has("quantity")) { error(ctx, 400, "quantity is required"); return; }

            long tInstrumentId = mCtx.matchingEngine.getOrderInstrumentId(tFirm, tOrderId);
            if (tInstrumentId == 0) { error(ctx, 404, "Order not found: " + tOrderId); return; }
            if (!checkMarketAccess(ctx, tFirm, tInstrumentId)) return;

            long   tPrice   = tBody.has("price")   ? tBody.get("price").getAsLong()     : 0L;
            String tUserRef = tBody.has("userRef") ? tBody.get("userRef").getAsString() : null;
            respond(ctx, 200, mCtx.matchingEngine.modifyOrder(tFirm, tOrderId, tPrice,
                    tBody.get("quantity").getAsLong(), tUserRef));
        });

        // Deleting is allowed for the order's own firm, even if its market access was revoked, in every
        // market state except CLOSED (checked by the matching engine)
        app.delete("/api/orders/{orderId}", ctx -> {
            String tFirm    = firm(ctx);
            String tOrderId = ctx.pathParam("orderId");
            if (!cLog.isTraceEnabled()) {
                cLog.info("{} (delete order) user: {}", ctx.path(), SessionUser.of(ctx) );
            }
            if (mCtx.matchingEngine.getOrderInstrumentId(tFirm, tOrderId) == 0) {
                error(ctx, 404, "Order not found: " + tOrderId); return;
            }
            respond(ctx, 200, mCtx.matchingEngine.deleteOrder(tFirm, tOrderId));
        });

        // The firm's resting orders, optionally only for one market (?marketId=) and/or instrument (?instrumentId=)
        app.get("/api/orders", ctx -> {
            Integer tMarketId = marketIdParam(ctx);
            if (tMarketId == null) return;
            Long tInstrumentId = instrumentIdParam(ctx);
            if (tInstrumentId == null) return;
            JsonObject tOut = new JsonObject();
            tOut.add("orders", mCtx.matchingEngine.getOrdersByFirm(firm(ctx), tMarketId, tInstrumentId));
            ctx.json(tOut.toString());
        });
    }

    // ── Helpers (shared with TradeEndpoints) ──────────────────────────────

    static String firm(Context ctx) {
        return (String) ctx.req().getSession().getAttribute(RestApiContext.FIRM);
    }

    static void error(Context ctx, int pStatus, String pMessage) {
        JsonObject tOut = new JsonObject();
        tOut.addProperty("error", pMessage);
        ctx.status(pStatus).json(tOut.toString());
    }

    /** The optional instrumentId query parameter (0 if absent); null after replying 400 if invalid. */
    static Long instrumentIdParam(Context ctx) {
        String tParam = ctx.queryParam("instrumentId");
        if (tParam == null || tParam.isBlank()) return 0L;
        try { return Long.parseLong(tParam); }
        catch (NumberFormatException e) { error(ctx, 400, "instrumentId must be an integer"); return null; }
    }

    /** The optional marketId query parameter (0 if absent); null after replying 400 if invalid. */
    static Integer marketIdParam(Context ctx) {
        String tParam = ctx.queryParam("marketId");
        if (tParam == null || tParam.isBlank()) return 0;
        try { return Integer.parseInt(tParam); }
        catch (NumberFormatException e) { error(ctx, 400, "marketId must be an integer"); return null; }
    }

    private static JsonObject parseBody(Context ctx) {
        try { return JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (Exception e) { error(ctx, 400, "Request body must be a JSON object"); return null; }
    }

    /** Replies 404/403 and returns false unless the instrument exists and the firm may trade its market. */
    private boolean checkMarketAccess(Context ctx, String pFirm, long pInstrumentId) {
        var tInstrument = mCtx.instrumentCache.getInstrument(pInstrumentId);
        if (tInstrument == null) { error(ctx, 404, "Instrument not found: " + pInstrumentId); return false; }
        if (!mCtx.isAuthorized(pFirm, tInstrument.marketId())) {
            error(ctx, 403, "Not authorized for market " + tInstrument.marketId()); return false;
        }
        return true;
    }

    private static void respond(Context ctx, int pStatus, OrderResponse pRsp) {
        if (!pRsp.success()) { error(ctx, 400, pRsp.message()); return; }
        JsonObject tOut = new JsonObject();
        tOut.addProperty("orderId",   pRsp.orderId());
        tOut.addProperty("inserted",  pRsp.inserted());
        tOut.addProperty("matched",   pRsp.matched());
        tOut.addProperty("remaining", pRsp.remaining());
        if (pRsp.userRef() != null) tOut.addProperty("userRef", pRsp.userRef());
        ctx.status(pStatus).json(tOut.toString());
    }
}
