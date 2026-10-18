package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.InstrumentCache.MarketEntry;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

import java.util.Set;

public class AdminMarketEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public AdminMarketEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        registerDeleteAllOrders(app);
        app.get("/api/admin/markets", ctx -> {
            JsonArray tArray = new JsonArray();
            for (var tMarket : mCtx.database.listMarkets()) {
                tArray.add(tMarket.withId(tMarket.value().toJson()));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/markets", ctx -> {
            JsonObject tBody              = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tName                  = RestApiContext.sanitizeName(RestApiContext.field(tBody, "name",     "").trim());
            String tDescription           = RestApiContext.field(tBody, "description",           "").trim();
            String tType                  = RestApiContext.field(tBody, "type",                  "").trim();
            String tOpen                  = RestApiContext.field(tBody, "open",                  "");
            String tClose                 = RestApiContext.field(tBody, "close",                 "");
            boolean tInverse              = tBody.has("inverse") && tBody.get("inverse").getAsBoolean();
            boolean tSelfTrade            = !tBody.has("selfTrade") || tBody.get("selfTrade").getAsBoolean();   // default allowed
            String tPreOpenTime           = RestApiContext.field(tBody, "preOpenTime",           "");
            String tOpenAuctionTime       = RestApiContext.field(tBody, "openAuctionTime",       "");
            String tAuctionInterval       = RestApiContext.field(tBody, "auctionInterval",       "");
            String tRandomAuctionInterval = RestApiContext.field(tBody, "randomAuctionInterval", "");
            String tTradingStatus         = RestApiContext.field(tBody, "tradingStatus",         "CLOSED");
            if (tName.isEmpty())     { ctx.status(400).json("{\"error\":\"name is required\"}"); return; }
            // Auto-assign marketId: max existing + 1, or 1 if none exist
            int tMarketId = mCtx.nextMarketId();
            if (!Set.of("CLOSED","OPEN","SUSPENDED","PREOPEN").contains(tTradingStatus)) {
                ctx.status(400).json("{\"error\":\"Invalid tradingStatus\"}"); return;
            }
            String tErr = validateMarket(tType, tOpen, tClose,
                    tPreOpenTime, tOpenAuctionTime, tAuctionInterval, tRandomAuctionInterval, true);
            if (tErr != null) { ctx.status(400).json("{\"error\":\"" + tErr + "\"}"); return; }
            String tDatabaseId = mCtx.database.createMarket(new MarketEntry(tMarketId, tName, tDescription, tType,
                    tOpen, tClose, tInverse, tPreOpenTime, tOpenAuctionTime,
                    tAuctionInterval, tRandomAuctionInterval, tTradingStatus, tSelfTrade));
            mCtx.instrumentCache.putMarket(mCtx.database.getMarket(tDatabaseId).value());
            mCtx.refreshClientAuthorizations();   // firms authorized for "*" get the new market
            ctx.status(201).json("{\"databaseId\":\"" + tDatabaseId + "\"}");
        });

        app.get("/api/admin/markets/{id}", ctx -> {
            try {
                var tMarket = mCtx.database.getMarket(ctx.pathParam("id"));
                if (tMarket == null) { ctx.status(404).json("{\"error\":\"Market not found\"}"); return; }
                ctx.json(tMarket.withId(tMarket.value().toJson()).toString());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid market ID\"}");
            }
        });

        app.patch("/api/admin/markets/{id}", ctx -> {
            JsonObject tBody              = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tName                  = RestApiContext.sanitizeName(RestApiContext.field(tBody, "name",     "").trim());
            String tDescription           = RestApiContext.field(tBody, "description",           "").trim();
            String tType                  = RestApiContext.field(tBody, "type",                  "").trim();
            String tOpen                  = RestApiContext.field(tBody, "open",                  "");
            String tClose                 = RestApiContext.field(tBody, "close",                 "");
            boolean tInverse              = tBody.has("inverse") && tBody.get("inverse").getAsBoolean();
            boolean tSelfTrade            = !tBody.has("selfTrade") || tBody.get("selfTrade").getAsBoolean();   // default allowed
            String tPreOpenTime           = RestApiContext.field(tBody, "preOpenTime",           "");
            String tOpenAuctionTime       = RestApiContext.field(tBody, "openAuctionTime",       "");
            String tAuctionInterval       = RestApiContext.field(tBody, "auctionInterval",       "");
            String tRandomAuctionInterval = RestApiContext.field(tBody, "randomAuctionInterval", "");
            String tTradingStatus         = RestApiContext.field(tBody, "tradingStatus",         "CLOSED");
            if (!Set.of("CLOSED","OPEN","SUSPENDED","PREOPEN").contains(tTradingStatus)) {
                ctx.status(400).json("{\"error\":\"Invalid tradingStatus\"}"); return;
            }
            String tErr = validateMarket(tType, tOpen, tClose,
                    tPreOpenTime, tOpenAuctionTime, tAuctionInterval, tRandomAuctionInterval, false);
            if (tErr != null) { ctx.status(400).json("{\"error\":\"" + tErr + "\"}"); return; }
            try {
                String tDatabaseId = ctx.pathParam("id");
                mCtx.database.updateMarket(tDatabaseId, new MarketEntry(0, tName, tDescription, tType,
                        tOpen, tClose, tInverse, tPreOpenTime, tOpenAuctionTime,
                        tAuctionInterval, tRandomAuctionInterval, tTradingStatus, tSelfTrade));
                var tMarket = mCtx.database.getMarket(tDatabaseId);
                if (tMarket != null) mCtx.instrumentCache.putMarket(tMarket.value());
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid market ID\"}");
            }
        });

        app.delete("/api/admin/markets/{id}", ctx -> {
            try {
                String tDatabaseId = ctx.pathParam("id");
                var tMarket = mCtx.database.getMarket(tDatabaseId);
                Integer tMarketId = (tMarket != null) ? tMarket.value().marketId() : null;
                // A new market can reuse a deleted market's id, so a market must be empty before it is deleted
                int tOrders = (tMarketId != null) ? mCtx.matchingEngine.orderCount(tMarketId) : 0;
                if (tOrders > 0) {
                    ctx.status(409).json("{\"error\":\"Market has " + tOrders
                            + " order(s); delete them (Delete All Orders) before deleting the market\"}"); return;
                }
                int tInstruments = (tMarketId != null) ? mCtx.instrumentCache.getInstrumentsByMarket(tMarketId).size() : 0;
                if (tInstruments > 0) {
                    ctx.status(409).json("{\"error\":\"Market has " + tInstruments
                            + " instrument(s); delete them before deleting the market\"}"); return;
                }
                mCtx.database.deleteMarket(tDatabaseId);
                if (tMarketId != null) mCtx.instrumentCache.removeMarket(tMarketId);
                mCtx.refreshClientAuthorizations();
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid market ID\"}");
            }
        });
    }

    private void registerDeleteAllOrders(Javalin app) {
        // Deletes all orders in the market's order books, of every firm and in any market state
        app.delete("/api/admin/markets/{id}/orders", ctx -> {
            try {
                var tMarket = mCtx.database.getMarket(ctx.pathParam("id"));
                if (tMarket == null) { ctx.status(404).json("{\"error\":\"Market not found\"}"); return; }
                int tDeleted = mCtx.matchingEngine.deleteAllOrders(tMarket.value().marketId());
                ctx.json("{\"success\":true,\"ordersDeleted\":" + tDeleted + "}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid market ID\"}");
            }
        });
    }

    private String validateMarket(String pType, String pOpen, String pClose,
                                   String pPreOpenTime, String pOpenAuctionTime,
                                   String pAuctionInterval, String pRandomAuctionInterval,
                                   boolean pRequireType) {
        if (pType == null || pType.isEmpty()) {
            return pRequireType ? "Type is required" : null;
        }
        switch (pType) {
            case "CONTINUOUS":
                if (pOpen.isEmpty())  return "open is required for CONTINUOUS";
                if (pClose.isEmpty()) return "close is required for CONTINUOUS";
                break;
            case "AUCTION":
                if (pOpen.isEmpty())                  return "open is required for AUCTION";
                if (pClose.isEmpty())                 return "close is required for AUCTION";
                if (pAuctionInterval.isEmpty())       return "auctionInterval is required for AUCTION";
                if (pRandomAuctionInterval.isEmpty()) return "randomAuctionInterval is required for AUCTION";
                break;
            default:
                // Bulletin markets are a separate kind of market, see AdminBulletinMarketEndpoints
                return "Invalid type; must be CONTINUOUS or AUCTION";
        }
        return null;
    }
}
