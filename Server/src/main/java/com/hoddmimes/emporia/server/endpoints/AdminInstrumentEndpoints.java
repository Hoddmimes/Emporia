package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

import java.util.Set;

public class AdminInstrumentEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public AdminInstrumentEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/instruments", ctx -> {
            String tMarketIdStr = ctx.queryParam("marketId");
            if (tMarketIdStr == null || tMarketIdStr.isBlank()) {
                ctx.status(400).json("{\"error\":\"marketId query parameter is required\"}"); return;
            }
            int tMarketId;
            try { tMarketId = Integer.parseInt(tMarketIdStr); }
            catch (NumberFormatException e) { ctx.status(400).json("{\"error\":\"marketId must be an integer\"}"); return; }
            JsonArray tArray = new JsonArray();
            for (var tDoc : mCtx.database.listInstrumentsByMarket(tMarketId)) {
                tArray.add(mCtx.database.instrumentToJson(tDoc));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/instruments/load", ctx -> {
            JsonObject tBody    = JsonParser.parseString(ctx.body()).getAsJsonObject();
            JsonArray  tResults = new JsonArray();

            if (tBody.has("ADD")) {
                for (var tElem : tBody.getAsJsonArray("ADD")) {
                    JsonObject tInst   = tElem.getAsJsonObject();
                    JsonObject tResult = new JsonObject();
                    // ID field is used as the instrument name (ticker); fall back to "name" field
                    String tNameRaw = tInst.has("ID")   ? tInst.get("ID").getAsString().trim()
                                    : tInst.has("name") ? tInst.get("name").getAsString().trim() : "";
                    String tName = RestApiContext.sanitizeName(tNameRaw);
                    tResult.addProperty("operation", "ADD");
                    tResult.addProperty("id", tNameRaw);
                    if (tName.isEmpty()) {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "ID / name is required");
                        tResults.add(tResult); continue;
                    }
                    // "market" may be a market name (e.g. "XSTO") or an integer market ID
                    String tMarketIdStr = tInst.has("market") ? tInst.get("market").getAsString().trim() : "";
                    if (tMarketIdStr.isEmpty()) {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "market is required");
                        tResults.add(tResult); continue;
                    }
                    int tMarketId;
                    try {
                        tMarketId = Integer.parseInt(tMarketIdStr);
                        if (mCtx.instrumentCache.getMarket(tMarketId) == null) {
                            tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "Market '" + tMarketId + "' does not exist");
                            tResults.add(tResult); continue;
                        }
                    } catch (NumberFormatException e) {
                        // treat as market name
                        var tMarket = mCtx.instrumentCache.getMarketByName(tMarketIdStr);
                        if (tMarket == null) {
                            tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "Market '" + tMarketIdStr + "' does not exist");
                            tResults.add(tResult); continue;
                        }
                        tMarketId = tMarket.marketId();
                    }
                    String tInstType       = tInst.has("instType")       ? tInst.get("instType").getAsString().trim()       : "";
                    long   tTickSize       = Math.round((tInst.has("tickSize")    ? tInst.get("tickSize").getAsDouble()    : 0.0) * 100000);
                    long   tLotSize        = Math.round((tInst.has("lotSize")     ? tInst.get("lotSize").getAsDouble()     : 0.0) * 100000);
                    String tIsin           = tInst.has("ISIN") ? tInst.get("ISIN").getAsString()
                                           : tInst.has("isin") ? tInst.get("isin").getAsString() : "";
                    String tTradingStatus  = tInst.has("tradingStatus")  ? tInst.get("tradingStatus").getAsString()         : "ACTIVE";
                    String tExpirationDate = tInst.has("expirationDate") ? tInst.get("expirationDate").getAsString()        : "";
                    long   tStrikePrice    = Math.round((tInst.has("strikePrice") ? tInst.get("strikePrice").getAsDouble() : 0.0) * 100000);
                    String tUnderlying     = tInst.has("underlying")     ? tInst.get("underlying").getAsString()            : "";
                    String tOptionType     = tInst.has("optionType")     ? tInst.get("optionType").getAsString()            : "";
                    String tErr = validateInstrument(tInstType, tExpirationDate, tStrikePrice, tUnderlying, tOptionType, true);
                    if (tErr != null) {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", tErr);
                        tResults.add(tResult); continue;
                    }
                    long tId = mCtx.instrumentCache.nextInstrumentId(tMarketId);
                    String tMongoId = mCtx.database.createInstrument(tId, tMarketId, tName, tInstType, tTickSize, tLotSize,
                            tIsin, tTradingStatus, tExpirationDate, tStrikePrice, tUnderlying, tOptionType);
                    mCtx.instrumentCache.putInstrument(mCtx.database.getInstrument(tMongoId));
                    tResult.addProperty("status", "OK");
                    tResults.add(tResult);
                }
            }

            if (tBody.has("DELETE")) {
                for (var tElem : tBody.getAsJsonArray("DELETE")) {
                    JsonObject tInst   = tElem.getAsJsonObject();
                    JsonObject tResult = new JsonObject();
                    // DELETE accepts either a numeric instrumentId or a name
                    String tIdStr = tInst.has("ID") ? tInst.get("ID").getAsString().trim() : "";
                    tResult.addProperty("operation", "DELETE");
                    tResult.addProperty("id", tIdStr);
                    if (tIdStr.isEmpty()) {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "ID is required");
                        tResults.add(tResult); continue;
                    }
                    boolean tDeleted = false;
                    try {
                        long tId = Long.parseLong(tIdStr);
                        tDeleted = mCtx.database.deleteInstrumentByBusinessId(tId);
                        if (tDeleted) mCtx.instrumentCache.removeInstrument(tId);
                    } catch (NumberFormatException e) {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "ID must be a numeric instrumentId for DELETE");
                        tResults.add(tResult); continue;
                    }
                    if (tDeleted) {
                        tResult.addProperty("status", "OK");
                    } else {
                        tResult.addProperty("status", "ERROR"); tResult.addProperty("message", "Instrument '" + tIdStr + "' not found");
                    }
                    tResults.add(tResult);
                }
            }
            JsonObject tResponse = new JsonObject();
            tResponse.add("results", tResults);
            ctx.json(tResponse.toString());
        });

        app.post("/api/admin/instruments", ctx -> {
            JsonObject tBody       = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tMarketIdStr    = RestApiContext.field(tBody, "marketId",       "").trim();
            String tName           = RestApiContext.sanitizeName(RestApiContext.field(tBody, "name", "").trim());
            String tInstType       = RestApiContext.field(tBody, "instType",       "").trim();
            long   tTickSize       = tBody.has("tickSize")    ? tBody.get("tickSize").getAsLong()    : 0L;
            long   tLotSize        = tBody.has("lotSize")     ? tBody.get("lotSize").getAsLong()     : 0L;
            String tIsin           = RestApiContext.field(tBody, "isin",           "");
            String tExpirationDate = RestApiContext.field(tBody, "expirationDate", "");
            long   tStrikePrice    = tBody.has("strikePrice") ? tBody.get("strikePrice").getAsLong() : 0L;
            String tUnderlying     = RestApiContext.field(tBody, "underlying",     "");
            String tOptionType     = RestApiContext.field(tBody, "optionType",     "");
            if (tMarketIdStr.isEmpty())  { ctx.status(400).json("{\"error\":\"marketId is required\"}"); return; }
            int tMarketId;
            try { tMarketId = Integer.parseInt(tMarketIdStr); }
            catch (NumberFormatException e) { ctx.status(400).json("{\"error\":\"marketId must be an integer\"}"); return; }
            if (mCtx.instrumentCache.getMarket(tMarketId) == null) {
                ctx.status(400).json("{\"error\":\"Market '" + tMarketId + "' does not exist\"}"); return;
            }
            if (tName.isEmpty())         { ctx.status(400).json("{\"error\":\"name is required\"}"); return; }
            String tErr = validateInstrument(tInstType, tExpirationDate, tStrikePrice, tUnderlying, tOptionType, true);
            if (tErr != null) { ctx.status(400).json("{\"error\":\"" + tErr + "\"}"); return; }
            long tInstrumentId = mCtx.instrumentCache.nextInstrumentId(tMarketId);
            String tMongoId = mCtx.database.createInstrument(tInstrumentId, tMarketId, tName, tInstType,
                    tTickSize, tLotSize, tIsin, "ACTIVE", tExpirationDate, tStrikePrice, tUnderlying, tOptionType);
            mCtx.instrumentCache.putInstrument(mCtx.database.getInstrument(tMongoId));
            ctx.status(201).json("{\"mongoId\":\"" + tMongoId + "\"}");
        });

        app.get("/api/admin/instruments/{id}", ctx -> {
            try {
                var tDoc = mCtx.database.getInstrument(ctx.pathParam("id"));
                if (tDoc == null) { ctx.status(404).json("{\"error\":\"Instrument not found\"}"); return; }
                ctx.json(mCtx.database.instrumentToJson(tDoc).toString());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid instrument ID\"}");
            }
        });

        app.patch("/api/admin/instruments/{id}", ctx -> {
            JsonObject tBody       = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tMarketIdStr    = RestApiContext.field(tBody, "marketId",       "").trim();
            String tName           = RestApiContext.sanitizeName(RestApiContext.field(tBody, "name", "").trim());
            String tInstType       = RestApiContext.field(tBody, "instType",       "").trim();
            long   tTickSize       = tBody.has("tickSize")    ? tBody.get("tickSize").getAsLong()    : 0L;
            long   tLotSize        = tBody.has("lotSize")     ? tBody.get("lotSize").getAsLong()     : 0L;
            String tIsin           = RestApiContext.field(tBody, "isin",           "");
            String tExpirationDate = RestApiContext.field(tBody, "expirationDate", "");
            long   tStrikePrice    = tBody.has("strikePrice") ? tBody.get("strikePrice").getAsLong() : 0L;
            String tUnderlying     = RestApiContext.field(tBody, "underlying",     "");
            String tOptionType     = RestApiContext.field(tBody, "optionType",     "");
            int tMarketId;
            try { tMarketId = tMarketIdStr.isEmpty() ? 0 : Integer.parseInt(tMarketIdStr); }
            catch (NumberFormatException e) { ctx.status(400).json("{\"error\":\"marketId must be an integer\"}"); return; }
            String tErr = validateInstrument(tInstType, tExpirationDate, tStrikePrice, tUnderlying, tOptionType, false);
            if (tErr != null) { ctx.status(400).json("{\"error\":\"" + tErr + "\"}"); return; }
            try {
                String tMongoId = ctx.pathParam("id");
                mCtx.database.updateInstrument(tMongoId, tMarketId, tName, tInstType,
                        tTickSize, tLotSize, tIsin, tExpirationDate, tStrikePrice, tUnderlying, tOptionType);
                mCtx.instrumentCache.putInstrument(mCtx.database.getInstrument(tMongoId));
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid instrument ID\"}");
            }
        });

        app.patch("/api/admin/instruments/{id}/status", ctx -> {
            JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String tStatus   = RestApiContext.field(tBody, "tradingStatus", "");
            if (!Set.of("ACTIVE", "SUSPENDED").contains(tStatus)) {
                ctx.status(400).json("{\"error\":\"tradingStatus must be ACTIVE or SUSPENDED\"}"); return;
            }
            try {
                String tMongoId = ctx.pathParam("id");
                mCtx.database.updateInstrumentStatus(tMongoId, tStatus);
                mCtx.instrumentCache.putInstrument(mCtx.database.getInstrument(tMongoId));
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid instrument ID\"}");
            }
        });

        app.delete("/api/admin/instruments/{id}", ctx -> {
            try {
                String tMongoId = ctx.pathParam("id");
                var tDoc = mCtx.database.getInstrument(tMongoId);
                mCtx.database.deleteInstrument(tMongoId);
                var tEntry = (tDoc != null) ? InstrumentCache.InstrumentEntry.fromDocument(tDoc) : null;
                if (tEntry != null) mCtx.instrumentCache.removeInstrument(tEntry.instrumentId());
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid instrument ID\"}");
            }
        });
    }

    private String validateInstrument(String pInstType, String pExpirationDate,
                                       long pStrikePrice, String pUnderlying,
                                       String pOptionType, boolean pRequireType) {
        if (pInstType == null || pInstType.isEmpty()) {
            return pRequireType ? "instType is required" : null;
        }
        switch (pInstType) {
            case "OPTION":
                if (pExpirationDate.isEmpty()) return "expirationDate is required for OPTION";
                if (pStrikePrice == 0)         return "strikePrice is required for OPTION";
                if (pUnderlying.isEmpty())     return "underlying is required for OPTION";
                if (pOptionType.isEmpty() || (!pOptionType.equals("PUT") && !pOptionType.equals("CALL")))
                    return "optionType must be PUT or CALL for OPTION";
                break;
            case "FUTURE":
                if (pExpirationDate.isEmpty()) return "expirationDate is required for FUTURE";
                if (pStrikePrice == 0)         return "strikePrice is required for FUTURE";
                if (pUnderlying.isEmpty())     return "underlying is required for FUTURE";
                break;
            case "COMMODITY":
            case "EQUITY":
            case "OTC":
                break;
            default:
                return "Invalid instType; must be COMMODITY, EQUITY, OPTION, FUTURE or OTC";
        }
        return null;
    }
}
