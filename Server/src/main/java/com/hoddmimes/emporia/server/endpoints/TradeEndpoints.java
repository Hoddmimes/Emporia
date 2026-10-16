package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.db.BulletinTrade;
import com.hoddmimes.emporia.server.me.InternalTrade;
import io.javalin.Javalin;

import java.time.LocalDate;
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
            List<BulletinTrade> tBulletinTrades;
            try { tBulletinTrades = mCtx.database.listBulletinTradesByFirm(tFirm, tDay); }
            catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date must be yyyy-MM-dd"); return; }
            if (tDate == null || tDate.isBlank()) {
                tOut.add("trades", mCtx.matchingEngine.getTradesByFirm(tFirm, tMarketId, tInstrumentId));
            } else {
                List<InternalTrade> tTrades;
                try { tTrades = mCtx.database.listTradesByFirm(tFirm, tDate.trim()); }
                catch (IllegalArgumentException e) { OrderEndpoints.error(ctx, 400, "date must be yyyy-MM-dd"); return; }
                Collections.reverse(tTrades);   // stored trades are listed latest first
                tOut.addProperty("date", tDate.trim());
                tOut.add("trades", mCtx.matchingEngine.tradesForFirm(tTrades, tFirm, tMarketId, tInstrumentId));
            }
            tOut.add("bulletinTrades", (tInstrumentId != 0) ? new JsonArray() : bulletinTradesForFirm(tBulletinTrades, tFirm, tMarketId));
            ctx.json(tOut.toString());
        });
    }

    /**
     * The firm's side of the bulletin trades (given latest first), oldest first, optionally only for one
     * market (0 = all). A firm can't trade with its own bulletin, so it is on one side only.
     */
    private JsonArray bulletinTradesForFirm(List<BulletinTrade> pTrades, String pFirm, int pMarketId) {
        JsonArray tOut = new JsonArray();
        for (int i = pTrades.size() - 1; i >= 0; i--) {
            BulletinTrade t = pTrades.get(i);
            if (pMarketId != 0 && t.marketId() != pMarketId) continue;
            boolean tBuyer = pFirm.equals(t.buyAccount());
            BulletinMarket tMarket = mCtx.bulletinBoard.getMarket(t.marketId());
            JsonObject tJson = new JsonObject();
            tJson.addProperty("tradeNo",    t.tradeNo());
            tJson.addProperty("bulletinId", t.bulletinId());
            tJson.addProperty("marketId",   t.marketId());
            tJson.addProperty("marketName", (tMarket != null) ? tMarket.name() : String.valueOf(t.marketId()));
            tJson.addProperty("instrument", t.instrument());
            tJson.addProperty("side",       tBuyer ? "BUY" : "SELL");
            tJson.addProperty("price",      t.price());
            tJson.addProperty("quantity",   t.quantity());
            tJson.addProperty("tradeTime",  t.tradeTime());
            tJson.addProperty("user",       tBuyer ? t.buyUser() : t.sellUser());
            String tRef = tBuyer ? t.buyRef() : t.sellRef();
            if (tRef != null) tJson.addProperty("ref", tRef);
            tJson.addProperty("clientAccount", tBuyer ? t.buyClientAccount() : t.sellClientAccount());
            tOut.add(tJson);
        }
        return tOut;
    }
}
