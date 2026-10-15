package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.SessionUser;
import com.hoddmimes.emporia.server.bulletin.BulletinBoard;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.Set;

/**
 * The bulletin board for trading users (see BulletinBoard), and the list of bulletin markets. Adding a bulletin, making an offer and
 * listing bulletins require the firm to be authorized for the market; withdrawing a bulletin and
 * answering an offer are for the bulletin owner's firm only, withdrawing an offer for the offering firm
 * only (another firm's bulletin or offer is reported as
 * not found). Prices and quantities are longs scaled by MatchingEngine.SCALE. Changes are pushed to
 * the clients as bdx messages on /ws.
 */
public class BulletinEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(BulletinEndpoints.class);

    private final RestApiContext mCtx;

    public BulletinEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        // The bulletin markets the firm is authorized for, with their instruments and maxOrderTime ("hh:mm")
        app.get("/api/bulletin-markets", ctx -> {
            Set<Integer> tAuthorized = mCtx.authorizedMarkets(SessionUser.of(ctx).firm());
            JsonArray tOut = new JsonArray();
            mCtx.bulletinBoard.getMarkets().stream()
                    .filter(m -> tAuthorized.contains(m.marketId()))
                    .sorted(Comparator.comparing(BulletinMarket::name))
                    .forEach(m -> tOut.add(m.toJson()));
            ctx.json(tOut.toString());
        });

        app.post("/api/bulletins", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} user: {} rqst: {}", ctx.path(), SessionUser.of(ctx), tBody);
            }
            SessionUser tUser = SessionUser.of(ctx);
            if (!tBody.has("marketId"))   { OrderEndpoints.error(ctx, 400, "marketId is required");   return; }
            if (!tBody.has("instrument")) { OrderEndpoints.error(ctx, 400, "instrument is required"); return; }
            int tMarketId = tBody.get("marketId").getAsInt();
            if (!checkMarketAccess(ctx, tUser.firm(), tMarketId)) return;
            String tClientAccount;
            try { tClientAccount = OrderEndpoints.clientAccountOf(tBody); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, e.getMessage()); return; }

            respond(ctx, mCtx.bulletinBoard.addBulletin(tUser.firm(), tUser.userId(), tMarketId,
                    tBody.get("instrument").getAsString(),
                    optionalSide(tBody),
                    tBody.has("price")    ? tBody.get("price").getAsLong()    : 0L,
                    tBody.has("quantity") ? tBody.get("quantity").getAsLong() : 0L,
                    tBody.has("userRef")  ? tBody.get("userRef").getAsString() : null,
                    tClientAccount));
        });

        app.get("/api/bulletins", ctx -> {
            String tFirm = SessionUser.of(ctx).firm();
            Set<Integer> tMarkets = mCtx.authorizedMarkets(tFirm);
            String tParam = ctx.queryParam("marketId");
            if (tParam != null && !tParam.isBlank()) {
                int tMarketId;
                try { tMarketId = Integer.parseInt(tParam); }
                catch (NumberFormatException e) { OrderEndpoints.error(ctx, 400, "marketId must be an integer"); return; }
                if (!checkMarketAccess(ctx, tFirm, tMarketId)) return;
                tMarkets = Set.of(tMarketId);
            }
            JsonObject tOut = new JsonObject();
            tOut.add("bulletins", mCtx.bulletinBoard.getBulletins(tFirm, tMarkets));
            ctx.json(tOut.toString());
        });

        app.delete("/api/bulletins/{bulletinId}", ctx -> {
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} (withdraw bulletin) user: {}", ctx.path(), SessionUser.of(ctx));
            }
            respond(ctx, mCtx.bulletinBoard.withdrawBulletin(SessionUser.of(ctx).firm(), ctx.pathParam("bulletinId")));
        });

        app.post("/api/bulletins/{bulletinId}/offer", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} user: {} rqst: {}", ctx.path(), SessionUser.of(ctx), tBody);
            }
            SessionUser tUser = SessionUser.of(ctx);
            String tBulletinId = ctx.pathParam("bulletinId");
            if (!tBody.has("price"))    { OrderEndpoints.error(ctx, 400, "price is required");    return; }
            if (!tBody.has("quantity")) { OrderEndpoints.error(ctx, 400, "quantity is required"); return; }
            int tMarketId = mCtx.bulletinBoard.getMarketId(tBulletinId);
            if (tMarketId == 0) { OrderEndpoints.error(ctx, 404, "Bulletin not found: " + tBulletinId); return; }
            if (!checkMarketAccess(ctx, tUser.firm(), tMarketId)) return;
            String tClientAccount;
            try { tClientAccount = OrderEndpoints.clientAccountOf(tBody); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, e.getMessage()); return; }

            respond(ctx, mCtx.bulletinBoard.makeOffer(tUser.firm(), tUser.userId(), tBulletinId, optionalSide(tBody),
                    tBody.get("price").getAsLong(), tBody.get("quantity").getAsLong(),
                    tBody.has("offerRef")  ? tBody.get("offerRef").getAsString() : null,
                    tClientAccount,
                    tBody.has("validTime") ? tBody.get("validTime").getAsLong()  : 0L));
        });

        // The offering firm withdraws its pending offer
        app.delete("/api/bulletins/{bulletinId}/offer", ctx -> {
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} (withdraw offer) user: {}", ctx.path(), SessionUser.of(ctx));
            }
            respond(ctx, mCtx.bulletinBoard.withdrawOffer(SessionUser.of(ctx).firm(), ctx.pathParam("bulletinId")));
        });

        app.post("/api/bulletins/{bulletinId}/response", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            if (cLog.isTraceEnabled()) {
                cLog.trace("{} user: {} rqst: {}", ctx.path(), SessionUser.of(ctx), tBody);
            }
            if (!tBody.has("accept")) { OrderEndpoints.error(ctx, 400, "accept (true/false) is required"); return; }
            respond(ctx, mCtx.bulletinBoard.answerOffer(SessionUser.of(ctx).firm(), ctx.pathParam("bulletinId"),
                    tBody.get("accept").getAsBoolean()));
        });
    }

    /** The body's side in upper case, or null if it is missing, JSON null or empty (no side). */
    private static String optionalSide(JsonObject pBody) {
        if (!pBody.has("side") || pBody.get("side").isJsonNull()) return null;
        String tSide = pBody.get("side").getAsString().trim();
        return tSide.isEmpty() ? null : tSide.toUpperCase();
    }

    private static JsonObject parseBody(Context ctx) {
        try { return JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (Exception e) { OrderEndpoints.error(ctx, 400, "Request body must be a JSON object"); return null; }
    }

    /** Replies 403 and returns false unless the firm is authorized for the market. */
    private boolean checkMarketAccess(Context ctx, String pFirm, int pMarketId) {
        if (!mCtx.isAuthorized(pFirm, pMarketId)) {
            OrderEndpoints.error(ctx, 403, "Not authorized for market " + pMarketId); return false;
        }
        return true;
    }

    private static void respond(Context ctx, BulletinBoard.Result pResult) {
        if (!pResult.success()) { OrderEndpoints.error(ctx, pResult.status(), pResult.error()); return; }
        ctx.status(pResult.status()).json(pResult.body().toString());
    }
}
