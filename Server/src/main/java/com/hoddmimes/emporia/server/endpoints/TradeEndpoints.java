package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.InternalTrade;
import io.javalin.Javalin;
import org.bson.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The logged-in user's firm's trades, oldest first, optionally only for one market (?marketId=) and/or
 * one instrument (?instrumentId=): today's from the matching engine, or with ?date=yyyy-MM-dd the
 * trades stored in the database for that day. The market's public trades are in MarketEndpoints.
 * The firm's bulletin trades of the day (BulletinTrades, today's or the given date's) are listed apart,
 * in {@code bulletinTrades}; they have a free text instrument and no instrumentId, so ?instrumentId=
 * leaves them out.
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
            String tDay = (tDate == null || tDate.isBlank()) ? LocalDate.now().toString() : tDate.trim();
            List<Document> tBulletinDocs;
            try { tBulletinDocs = mCtx.database.listBulletinTradesByFirm(tFirm, tDay); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date must be yyyy-MM-dd"); return; }
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
            tOut.add("bulletinTrades", (tInstrumentId != 0) ? new JsonArray() : bulletinTradesForFirm(tBulletinDocs, tFirm, tMarketId));
            ctx.json(tOut.toString());
        });
    }

    /**
     * The firm's side of the BulletinTrades documents (given latest first), oldest first, optionally only
     * for one market (0 = all). A firm can't trade with its own bulletin, so it is on one side only.
     */
    private JsonArray bulletinTradesForFirm(List<Document> pDocs, String pFirm, int pMarketId) {
        JsonArray tOut = new JsonArray();
        for (int i = pDocs.size() - 1; i >= 0; i--) {
            Document tDoc = pDocs.get(i);
            int tMarketId = tDoc.getInteger("marketId");
            if (pMarketId != 0 && tMarketId != pMarketId) continue;
            String tSide = pFirm.equals(tDoc.getString("buyAccount")) ? "buy" : "sell";
            BulletinMarket tMarket = mCtx.bulletinBoard.getMarket(tMarketId);
            JsonObject tJson = new JsonObject();
            tJson.addProperty("tradeNo",    tDoc.getString("tradeNo"));
            tJson.addProperty("bulletinId", tDoc.getString("bulletinId"));
            tJson.addProperty("marketId",   tMarketId);
            tJson.addProperty("marketName", (tMarket != null) ? tMarket.name() : String.valueOf(tMarketId));
            tJson.addProperty("instrument", tDoc.getString("instrument"));
            tJson.addProperty("side",       tSide.toUpperCase());
            tJson.addProperty("price",      tDoc.getLong("price"));
            tJson.addProperty("quantity",   tDoc.getLong("quantity"));
            tJson.addProperty("tradeTime",  Database.parseTradeTime(tDoc.getString("tradeTime")));
            tJson.addProperty("user",       tDoc.getString(tSide + "User"));
            String tRef = tDoc.getString(tSide + "Ref");
            if (tRef != null) tJson.addProperty("ref", tRef);
            tOut.add(tJson);
        }
        return tOut;
    }
}
