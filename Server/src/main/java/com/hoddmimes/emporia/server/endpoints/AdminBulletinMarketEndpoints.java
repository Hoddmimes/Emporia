package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.MarketStateScheduler;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Admin CRUD for the bulletin markets (MongoDB collection BulletinMarkets, see BulletinMarket).
 * Body fields: name (create only), description, tradingStatus (OPEN, CLOSED or SUSPENDED, default CLOSED),
 * open and close (required, "H:mm" or "H:mm:ss", server local time; see MarketStateScheduler),
 * instruments (an array of literal names and /regex/ entries, at least one) and maxOrderTime
 * ("hh:mm", greater than 0:00). Each write goes to MongoDB first and then to the BulletinBoard.
 */
public class AdminBulletinMarketEndpoints implements RestEndpoint {

    private static final Set<String> STATUSES = Set.of(BulletinMarket.OPEN, BulletinMarket.CLOSED, BulletinMarket.SUSPENDED);

    private final RestApiContext mCtx;

    public AdminBulletinMarketEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    /** The validated body fields. */
    private record Fields(String description, String tradingStatus, String open, String close,
                          List<String> instruments, String maxOrderTime) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/bulletin-markets", ctx -> {
            JsonArray tArray = new JsonArray();
            for (Document tDoc : mCtx.database.listBulletinMarkets()) {
                tArray.add(toJson(tDoc));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/bulletin-markets", ctx -> {
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            String tName = RestApiContext.sanitizeName(RestApiContext.field(tBody, "name", "").trim());
            if (tName.isEmpty()) { OrderEndpoints.error(ctx, 400, "name is required"); return; }
            Fields tFields = validate(ctx, tBody);
            if (tFields == null) return;

            int tMarketId = mCtx.nextMarketId();
            String tMongoId = mCtx.database.createBulletinMarket(tMarketId, tName, tFields.description(),
                    tFields.tradingStatus(), tFields.open(), tFields.close(), tFields.instruments(), tFields.maxOrderTime());
            mCtx.bulletinBoard.putMarket(mCtx.database.getBulletinMarket(tMongoId));
            mCtx.refreshClientAuthorizations();   // firms authorized for "*" get the new market
            ctx.status(201).json("{\"mongoId\":\"" + tMongoId + "\"}");
        });

        app.get("/api/admin/bulletin-markets/{id}", ctx -> {
            Document tDoc = find(ctx);
            if (tDoc != null) ctx.json(toJson(tDoc).toString());
        });

        app.patch("/api/admin/bulletin-markets/{id}", ctx -> {
            if (find(ctx) == null) return;
            JsonObject tBody = parseBody(ctx);
            if (tBody == null) return;
            Fields tFields = validate(ctx, tBody);
            if (tFields == null) return;

            String tMongoId = ctx.pathParam("id");
            mCtx.database.updateBulletinMarket(tMongoId, tFields.description(), tFields.tradingStatus(),
                    tFields.open(), tFields.close(), tFields.instruments(), tFields.maxOrderTime());
            mCtx.bulletinBoard.putMarket(mCtx.database.getBulletinMarket(tMongoId));
            ctx.json("{\"success\":true}");
        });

        app.delete("/api/admin/bulletin-markets/{id}", ctx -> {
            Document tDoc = find(ctx);
            if (tDoc == null) return;
            int tMarketId = tDoc.getInteger("marketId");
            // A new market can reuse a deleted market's id, so a market must be empty before it is deleted
            int tBulletins = mCtx.bulletinBoard.bulletinCount(tMarketId);
            if (tBulletins > 0) {
                OrderEndpoints.error(ctx, 409, "Bulletin market has " + tBulletins
                        + " bulletin(s); delete them (Delete All Bulletins) before deleting the market");
                return;
            }
            mCtx.database.deleteBulletinMarket(ctx.pathParam("id"));
            mCtx.bulletinBoard.removeMarket(tMarketId);
            mCtx.refreshClientAuthorizations();
            ctx.json("{\"success\":true}");
        });

        // Deletes all bulletins in the market, of every firm and in any market state
        app.delete("/api/admin/bulletin-markets/{id}/bulletins", ctx -> {
            Document tDoc = find(ctx);
            if (tDoc == null) return;
            int tDeleted = mCtx.bulletinBoard.deleteAllBulletins(tDoc.getInteger("marketId"));
            ctx.json("{\"success\":true,\"bulletinsDeleted\":" + tDeleted + "}");
        });
    }

    /** The bulletin market of the {id} path parameter (its MongoDB id), or null after replying 400/404. */
    private Document find(Context ctx) {
        Document tDoc;
        try { tDoc = mCtx.database.getBulletinMarket(ctx.pathParam("id")); }
        catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "Invalid bulletin market ID"); return null; }
        if (tDoc == null) OrderEndpoints.error(ctx, 404, "Bulletin market not found");
        return tDoc;
    }

    /** The validated fields, or null after replying 400. */
    private static Fields validate(Context ctx, JsonObject pBody) {
        String tStatus = RestApiContext.field(pBody, "tradingStatus", BulletinMarket.CLOSED).trim();
        if (!STATUSES.contains(tStatus)) {
            OrderEndpoints.error(ctx, 400, "tradingStatus must be OPEN, CLOSED or SUSPENDED"); return null;
        }
        String tOpen  = RestApiContext.field(pBody, "open",  "").trim();
        String tClose = RestApiContext.field(pBody, "close", "").trim();
        if (!MarketStateScheduler.isValidTime(tOpen)) {
            OrderEndpoints.error(ctx, 400, "open is required (H:mm or H:mm:ss)"); return null;
        }
        if (!MarketStateScheduler.isValidTime(tClose)) {
            OrderEndpoints.error(ctx, 400, "close is required (H:mm or H:mm:ss)"); return null;
        }
        List<String> tInstruments = new ArrayList<>();
        JsonElement tArray = pBody.get("instruments");
        if (tArray == null || !tArray.isJsonArray()) {
            OrderEndpoints.error(ctx, 400, "instruments must be an array of instrument names and /regex/ entries"); return null;
        }
        for (JsonElement tEntry : tArray.getAsJsonArray()) {
            String tInstrument = tEntry.getAsString().trim();
            if (!tInstrument.isEmpty() && !tInstruments.contains(tInstrument)) tInstruments.add(tInstrument);
        }
        String tErr = BulletinMarket.validateInstruments(tInstruments);
        if (tErr != null) { OrderEndpoints.error(ctx, 400, tErr); return null; }

        String tMaxOrderTime = RestApiContext.field(pBody, "maxOrderTime", "").trim();
        if (BulletinMarket.parseMaxOrderTime(tMaxOrderTime) <= 0) {
            OrderEndpoints.error(ctx, 400, "maxOrderTime must be hh:mm (hours and minutes) and greater than 0:00"); return null;
        }
        return new Fields(RestApiContext.field(pBody, "description", "").trim(), tStatus, tOpen, tClose,
                tInstruments, tMaxOrderTime);
    }

    private static JsonObject toJson(Document pDoc) {
        JsonObject tJson = BulletinMarket.fromDocument(pDoc).toJson();
        tJson.addProperty("mongoId", pDoc.getObjectId("_id").toHexString());
        return tJson;
    }

    private static JsonObject parseBody(Context ctx) {
        try { return JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (Exception e) { OrderEndpoints.error(ctx, 400, "Request body must be a JSON object"); return null; }
    }
}
