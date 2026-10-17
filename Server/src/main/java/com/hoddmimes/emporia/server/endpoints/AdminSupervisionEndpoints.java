package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.db.BulletinTrade;
import com.hoddmimes.emporia.server.db.Database;
import com.hoddmimes.emporia.server.me.InternalTrade;
import com.hoddmimes.emporia.server.me.Order;
import com.hoddmimes.emporia.server.me.OrderResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Market supervision in the admin interface (pages market-orders.html and market-trades.html): the resting
 * orders and the trades of all firms, with firm and trader (user), in one market or all of them.
 *
 * GET    /api/admin/orders?marketId=&firm=         resting orders; marketId required, 0 = all markets; firm optional
 * DELETE /api/admin/orders/{orderId}               deletes any firm's order, in any market state
 * GET    /api/admin/trades?date=&marketId=&firm=   a day's stored trades (default today), latest first; marketId
 *                                                  optional (0 = all), firm optional (buyer or seller)
 * POST   /api/admin/trades/{tradeNo}/reverse       {"instrumentId","tradeTime"}: a reversal trade, the original's
 *                                                  buyer selling to its seller at the same price and quantity
 *
 * and the same for the bulletin markets (BulletinBoard, BulletinTrades):
 * GET    /api/admin/bulletins?marketId=&firm=      bulletins with owner and pending offer; marketId required, 0 = all
 *                                                  bulletin markets; firm optional (owner or offering firm)
 * DELETE /api/admin/bulletins/{bulletinId}         deletes any firm's bulletin, in any market state
 * GET    /api/admin/bulletin-trades?date=&marketId=&firm=  a day's bulletin trades, latest first
 * POST   /api/admin/bulletin-trades/{tradeNo}/reverse      {"tradeTime"}: a reversal bulletin trade
 *
 * Prices and quantities are scaled longs, like the rest of the REST API.
 */
public class AdminSupervisionEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(AdminSupervisionEndpoints.class);

    private final RestApiContext mCtx;
    private final Object         mReverseLock = new Object();   // one reversal at a time: check, then reverse

    public AdminSupervisionEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        registerBulletins(app);
        app.get("/api/admin/orders", ctx -> {
            Integer tMarketId = marketIdParam(ctx, true);
            if (tMarketId == null) return;
            ctx.json(mCtx.matchingEngine.getAllOrders(tMarketId, firmParam(ctx)).toString());
        });

        app.delete("/api/admin/orders/{orderId}", ctx -> {
            String tOrderId = ctx.pathParam("orderId");
            OrderResponse tRsp = mCtx.matchingEngine.supervisionDeleteOrder(tOrderId);
            if (!tRsp.success()) { error(ctx, 404, tRsp.message()); return; }
            cLog.info("Market supervision: admin {} deleted order {}", adminUser(ctx), tOrderId);
            JsonObject tJson = new JsonObject();
            tJson.addProperty("orderId", tOrderId);
            ctx.json(tJson.toString());
        });

        app.get("/api/admin/trades", ctx -> {
            String tDate = ctx.queryParamAsClass("date", String.class).getOrDefault(LocalDate.now().toString());
            Integer tMarketId = marketIdParam(ctx, false);
            if (tMarketId == null) return;
            String tFirm = firmParam(ctx);
            List<InternalTrade> tTrades;
            try { tTrades = mCtx.database.listTradesByDate(tDate); }
            catch (IllegalArgumentException e) { error(ctx, 400, e.getMessage()); return; }
            Map<String, InternalTrade> tReversals = reversalsByTrade();
            JsonArray tResult = new JsonArray();
            for (InternalTrade tTrade : tTrades) {
                if (tMarketId != 0 && marketIdOf(tTrade.getInstrumentId()) != tMarketId) continue;
                if (tFirm != null && !tFirm.equals(tTrade.getBuyOrder().getAccount())
                                  && !tFirm.equals(tTrade.getSellOrder().getAccount())) continue;
                tResult.add(tradeJson(tTrade, tReversals.get(key(tTrade.getInstrumentId(), Long.toHexString(tTrade.getTradeNo())))));
            }
            ctx.json(tResult.toString());
        });

        app.post("/api/admin/trades/{tradeNo}/reverse", ctx -> {
            String tTradeNo = ctx.pathParam("tradeNo");
            long tInstrumentId, tTradeTime;
            try {
                JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
                tInstrumentId = tBody.get("instrumentId").getAsLong();
                tTradeTime    = tBody.get("tradeTime").getAsLong();
            } catch (RuntimeException e) {
                error(ctx, 400, "instrumentId and tradeTime are required"); return;
            }
            synchronized (mReverseLock) {
                InternalTrade tOriginal = findTrade(tTradeNo, tInstrumentId, tTradeTime);
                if (tOriginal == null) { error(ctx, 404, "Trade not found: " + tTradeNo); return; }
                if (tOriginal.isReversal()) { error(ctx, 409, "Trade " + tTradeNo + " is a reversal trade and can not be reversed"); return; }
                InternalTrade tExisting = reversalsByTrade().get(key(tInstrumentId, tTradeNo));
                if (tExisting != null) {
                    error(ctx, 409, "Trade " + tTradeNo + " is already reversed by trade " + Long.toHexString(tExisting.getTradeNo()));
                    return;
                }
                InternalTrade tReversal = mCtx.matchingEngine.reverseTrade(tOriginal);
                cLog.info("Market supervision: admin {} reversed trade {}: {}", adminUser(ctx), tTradeNo, tReversal);
                ctx.status(201).json(tradeJson(tReversal, null).toString());
            }
        });
    }

    private void registerBulletins(Javalin app) {
        app.get("/api/admin/bulletins", ctx -> {
            Integer tMarketId = marketIdParam(ctx, true);
            if (tMarketId == null) return;
            ctx.json(mCtx.bulletinBoard.getAllBulletins(tMarketId, firmParam(ctx)).toString());
        });

        app.delete("/api/admin/bulletins/{bulletinId}", ctx -> {
            String tBulletinId = ctx.pathParam("bulletinId");
            if (!mCtx.bulletinBoard.supervisionDeleteBulletin(tBulletinId)) {
                error(ctx, 404, "Bulletin not found: " + tBulletinId); return;
            }
            cLog.info("Market supervision: admin {} deleted bulletin {}", adminUser(ctx), tBulletinId);
            JsonObject tJson = new JsonObject();
            tJson.addProperty("bulletinId", tBulletinId);
            ctx.json(tJson.toString());
        });

        app.get("/api/admin/bulletin-trades", ctx -> {
            String tDate = ctx.queryParamAsClass("date", String.class).getOrDefault(LocalDate.now().toString());
            Integer tMarketId = marketIdParam(ctx, false);
            if (tMarketId == null) return;
            String tFirm = firmParam(ctx);
            List<BulletinTrade> tTrades;
            try { tTrades = mCtx.database.listBulletinTradesByDate(tDate); }
            catch (IllegalArgumentException e) { error(ctx, 400, e.getMessage()); return; }
            Map<String, BulletinTrade> tReversals = bulletinReversalsByTrade();
            JsonArray tResult = new JsonArray();
            for (BulletinTrade t : tTrades) {
                if (tMarketId != 0 && t.marketId() != tMarketId) continue;
                if (tFirm != null && !tFirm.equals(t.buyAccount()) && !tFirm.equals(t.sellAccount())) continue;
                tResult.add(bulletinTradeJson(t, tReversals.get(t.tradeNo())));
            }
            ctx.json(tResult.toString());
        });

        app.post("/api/admin/bulletin-trades/{tradeNo}/reverse", ctx -> {
            String tTradeNo = ctx.pathParam("tradeNo");
            long tTradeTime;
            try { tTradeTime = JsonParser.parseString(ctx.body()).getAsJsonObject().get("tradeTime").getAsLong(); }
            catch (RuntimeException e) { error(ctx, 400, "tradeTime is required"); return; }
            synchronized (mReverseLock) {
                BulletinTrade tOriginal = null;
                for (BulletinTrade t : mCtx.database.listBulletinTradesByDate(Database.formatTradeTime(tTradeTime).substring(0, 10))) {
                    if (t.tradeNo().equalsIgnoreCase(tTradeNo)) { tOriginal = t; break; }
                }
                if (tOriginal == null) { error(ctx, 404, "Bulletin trade not found: " + tTradeNo); return; }
                if (tOriginal.isReversal()) { error(ctx, 409, "Bulletin trade " + tTradeNo + " is a reversal trade and can not be reversed"); return; }
                BulletinTrade tExisting = bulletinReversalsByTrade().get(tOriginal.tradeNo());
                if (tExisting != null) {
                    error(ctx, 409, "Bulletin trade " + tTradeNo + " is already reversed by trade " + tExisting.tradeNo());
                    return;
                }
                BulletinTrade tReversal = mCtx.bulletinBoard.reverseTrade(tOriginal);
                cLog.info("Market supervision: admin {} reversed bulletin trade {} by trade {}", adminUser(ctx), tTradeNo, tReversal.tradeNo());
                ctx.status(201).json(bulletinTradeJson(tReversal, null).toString());
            }
        });
    }

    /** The stored bulletin reversal trades by the tradeNo they reverse (bulletin trade numbers are unique). */
    private Map<String, BulletinTrade> bulletinReversalsByTrade() {
        Map<String, BulletinTrade> tMap = new HashMap<>();
        for (BulletinTrade t : mCtx.database.listBulletinReversalTrades()) tMap.put(t.reversalOf(), t);
        return tMap;
    }

    /** A bulletin trade in the same form as tradeJson (bulletinId and the free text instrument, no instrumentId). */
    private JsonObject bulletinTradeJson(BulletinTrade t, BulletinTrade pReversedBy) {
        BulletinMarket tMarket = mCtx.bulletinBoard.getMarket(t.marketId());
        JsonObject j = new JsonObject();
        j.addProperty("tradeNo",        t.tradeNo());
        j.addProperty("tradeTime",      t.tradeTime());
        j.addProperty("marketId",       t.marketId());
        j.addProperty("marketName",     (tMarket != null) ? tMarket.name() : String.valueOf(t.marketId()));
        j.addProperty("bulletinId",     t.bulletinId());
        j.addProperty("instrumentName", t.instrument());
        j.addProperty("price",          t.price());
        j.addProperty("quantity",       t.quantity());
        j.addProperty("buyFirm",          t.buyAccount());
        j.addProperty("buyUser",          t.buyUser());
        j.addProperty("buyClientAccount", t.buyClientAccount());
        j.addProperty("buyUserRef",       t.buyRef());
        j.addProperty("sellFirm",          t.sellAccount());
        j.addProperty("sellUser",          t.sellUser());
        j.addProperty("sellClientAccount", t.sellClientAccount());
        j.addProperty("sellUserRef",       t.sellRef());
        if (t.isReversal()) j.addProperty("reversalOf", t.reversalOf());
        if (pReversedBy != null) j.addProperty("reversedBy", pReversedBy.tradeNo());
        return j;
    }

    /** The stored trade with the tradeNo, instrument and time (looked up among the trades of its day), or null. */
    private InternalTrade findTrade(String pTradeNo, long pInstrumentId, long pTradeTime) {
        String tDate = Database.formatTradeTime(pTradeTime).substring(0, 10);
        for (InternalTrade tTrade : mCtx.database.listTradesByDate(tDate)) {
            if (tTrade.getInstrumentId() == pInstrumentId && tTrade.getTradeTime() == pTradeTime
                    && Long.toHexString(tTrade.getTradeNo()).equalsIgnoreCase(pTradeNo)) {
                return tTrade;
            }
        }
        return null;
    }

    /** The stored reversal trades by the trade they reverse (key). */
    private Map<String, InternalTrade> reversalsByTrade() {
        Map<String, InternalTrade> tMap = new HashMap<>();
        for (InternalTrade tReversal : mCtx.database.listReversalTrades()) {
            tMap.put(key(tReversal.getInstrumentId(), tReversal.getReversalOf()), tReversal);
        }
        return tMap;
    }

    // Trade numbers were only unique per server run before they included the start time, so a trade is
    // identified by its instrument and tradeNo
    private static String key(long pInstrumentId, String pTradeNo) {
        return pInstrumentId + ":" + pTradeNo.toLowerCase();
    }

    /** A trade with both sides' firm, trader, client account, userRef and order id; reversedBy if reversed. */
    private JsonObject tradeJson(InternalTrade pTrade, InternalTrade pReversedBy) {
        long tInstrumentId = pTrade.getInstrumentId();
        int  tMarketId     = marketIdOf(tInstrumentId);
        InstrumentCache.InstrumentEntry tInstrument = mCtx.instrumentCache.getInstrument(tInstrumentId);
        InstrumentCache.MarketEntry     tMarket     = mCtx.instrumentCache.getMarket(tMarketId);
        JsonObject j = new JsonObject();
        j.addProperty("tradeNo",        Long.toHexString(pTrade.getTradeNo()));
        j.addProperty("tradeTime",      pTrade.getTradeTime());
        j.addProperty("marketId",       tMarketId);
        j.addProperty("marketName",     (tMarket != null) ? tMarket.name() : "");
        j.addProperty("instrumentId",   tInstrumentId);
        j.addProperty("instrumentName", (tInstrument != null) ? tInstrument.name() : "");
        j.addProperty("price",          pTrade.getPrice());
        j.addProperty("quantity",       pTrade.getQuantity());
        side(j, "buy",  pTrade.getBuyOrder());
        side(j, "sell", pTrade.getSellOrder());
        if (pTrade.isReversal()) j.addProperty("reversalOf", pTrade.getReversalOf());
        if (pReversedBy != null) j.addProperty("reversedBy", Long.toHexString(pReversedBy.getTradeNo()));
        return j;
    }

    private static void side(JsonObject pJson, String pPrefix, Order pOrder) {
        pJson.addProperty(pPrefix + "Firm",          pOrder.getAccount());
        pJson.addProperty(pPrefix + "User",          pOrder.getUser());
        pJson.addProperty(pPrefix + "ClientAccount", pOrder.getClientAccount());
        pJson.addProperty(pPrefix + "UserRef",       pOrder.getUserRef());
        pJson.addProperty(pPrefix + "OrderId",       Long.toHexString(pOrder.getOrderId()));
    }

    private int marketIdOf(long pInstrumentId) {
        InstrumentCache.InstrumentEntry tInstrument = mCtx.instrumentCache.getInstrument(pInstrumentId);
        return (tInstrument != null) ? tInstrument.marketId() : (int) (pInstrumentId >>> 32);
    }

    /** The marketId query parameter (0 = all markets); null after answering 400 if it is required or invalid. */
    private static Integer marketIdParam(Context ctx, boolean pRequired) {
        String tValue = ctx.queryParam("marketId");
        if (tValue == null || tValue.isBlank()) {
            if (!pRequired) return 0;
            error(ctx, 400, "marketId is required (0 = all markets)");
            return null;
        }
        try { return Integer.parseInt(tValue.trim()); }
        catch (NumberFormatException e) { error(ctx, 400, "Invalid marketId: " + tValue); return null; }
    }

    /** The firm query parameter, null if not given (all firms). */
    private static String firmParam(Context ctx) {
        String tFirm = ctx.queryParam("firm");
        return (tFirm == null || tFirm.isBlank()) ? null : tFirm.trim();
    }

    private static String adminUser(Context ctx) {
        return ctx.sessionAttribute(RestApiContext.ADMIN_USER);
    }

    private static void error(Context ctx, int pStatus, String pMessage) {
        JsonObject tJson = new JsonObject();
        tJson.addProperty("error", pMessage);
        ctx.status(pStatus).json(tJson.toString());
    }
}
