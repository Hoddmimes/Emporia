package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.InstrumentCache;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.bulletin.BulletinMarket;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import io.javalin.Javalin;
import org.bson.Document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The logged-in user's firm's holdings: GET /api/portfolio. A position per instrument is the sum of
 * all the firm's stored trades in it, of every day (bought quantity minus sold quantity), so selling
 * 10, buying 5 and buying 10 is a position of +5. Positions are kept per instrument and client account
 * (the clientAccount of the firm's order in each trade; trades stored before client accounts count as
 * the default account "omni"), so the client can show them per account or add them up per instrument.
 * Positions of 0 are left out.
 * The value is the position times the instrument's last traded price: today's from the matching
 * engine, otherwise the latest stored trade's. Prices, quantities and values are longs scaled by
 * MatchingEngine.SCALE. Bulletin trades (BulletinTrades) give positions of their own, per bulletin market
 * and free text instrument ({@code bulletin} true, instrumentId null), valued at the instrument's latest
 * bulletin trade price in that market; they are kept per client account too.
 */
public class PortfolioEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public PortfolioEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    /** A position in an instrument and client account: the quantities bought and sold (scaled), position = bought - sold. */
    record Position(long instrumentId, int marketId, String clientAccount, long bought, long sold) {
        long position() { return bought - sold; }
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/portfolio", ctx -> {
            String tFirm = OrderEndpoints.firm(ctx);
            List<JsonObject> tRows = new ArrayList<>();
            long tTotalValue = 0;
            for (Position tPos : positions(tFirm, mCtx.database.listTradesByFirm(tFirm, null))) {
                if (tPos.position() == 0) continue;
                long tLast = mCtx.matchingEngine.getLastTradePrice(tPos.instrumentId());
                if (tLast == 0) tLast = mCtx.database.lastTradePrice(tPos.instrumentId());
                InstrumentCache.InstrumentEntry tInstrument = mCtx.instrumentCache.getInstrument(tPos.instrumentId());
                InstrumentCache.MarketEntry     tMarket     = mCtx.instrumentCache.getMarket(tPos.marketId());

                JsonObject tJson = new JsonObject();
                tJson.addProperty("instrumentId", tPos.instrumentId());
                tJson.addProperty("instrument",   tInstrument != null ? tInstrument.name() : String.valueOf(tPos.instrumentId()));
                tJson.addProperty("marketId",     tPos.marketId());
                tJson.addProperty("market",       tMarket != null ? tMarket.name() : String.valueOf(tPos.marketId()));
                tJson.addProperty("clientAccount", tPos.clientAccount());
                tJson.addProperty("position",     tPos.position());
                tJson.addProperty("bought",       tPos.bought());
                tJson.addProperty("sold",         tPos.sold());
                tJson.addProperty("lastPrice",    tLast > 0 ? tLast : null);
                if (tLast > 0) {
                    long tValue = value(tPos.position(), tLast);
                    tJson.addProperty("value", tValue);
                    tTotalValue += tValue;
                } else {
                    tJson.add("value", null);
                }
                tRows.add(tJson);
            }
            for (BulletinPosition tPos : bulletinPositions(tFirm, mCtx.database.listBulletinTradesByFirm(tFirm, null))) {
                if (tPos.position() == 0) continue;
                long tLast = mCtx.database.lastBulletinTradePrice(tPos.marketId(), tPos.instrument());
                BulletinMarket tMarket = mCtx.bulletinBoard.getMarket(tPos.marketId());
                long tValue = value(tPos.position(), tLast);
                tTotalValue += tValue;

                JsonObject tJson = new JsonObject();
                tJson.add("instrumentId", null);
                tJson.addProperty("instrument",   tPos.instrument());
                tJson.addProperty("marketId",     tPos.marketId());
                tJson.addProperty("market",       tMarket != null ? tMarket.name() : String.valueOf(tPos.marketId()));
                tJson.addProperty("bulletin",     true);
                tJson.addProperty("clientAccount", tPos.clientAccount());
                tJson.addProperty("position",     tPos.position());
                tJson.addProperty("bought",       tPos.bought());
                tJson.addProperty("sold",         tPos.sold());
                tJson.addProperty("lastPrice",    tLast);
                tJson.addProperty("value",        tValue);
                tRows.add(tJson);
            }
            tRows.sort(Comparator.comparing((JsonObject j) -> j.get("market").getAsString())
                                 .thenComparing(j -> j.get("instrument").getAsString())
                                 .thenComparing(j -> j.get("clientAccount").getAsString()));
            JsonArray tArray = new JsonArray();
            tRows.forEach(tArray::add);
            JsonObject tOut = new JsonObject();
            tOut.add("positions", tArray);
            tOut.addProperty("totalValue", tTotalValue);
            ctx.json(tOut.toString());
        });
    }

    /**
     * The firm's position per instrument and client account from its Trades documents, in the order
     * they first appear. A trade between two orders of the firm itself counts on both sides: it nets 0
     * if both orders have the same client account, otherwise it moves the position from one to the other.
     */
    static List<Position> positions(String pFirm, List<Document> pTrades) {
        Map<List<Object>, Position> tPositions = new LinkedHashMap<>();   // key: instrument id, client account
        for (Document tTrade : pTrades) {
            long tInstrumentId = Long.parseLong(tTrade.getString("instrumentId"));
            long tQuantity     = tTrade.getLong("quantity");
            if (pFirm.equals(tTrade.getString("buyAccount"))) {
                addTo(tPositions, tInstrumentId, Database.clientAccountOf(tTrade, "buyClientAccount"), tQuantity, 0L);
            }
            if (pFirm.equals(tTrade.getString("sellAccount"))) {
                addTo(tPositions, tInstrumentId, Database.clientAccountOf(tTrade, "sellClientAccount"), 0L, tQuantity);
            }
        }
        return new ArrayList<>(tPositions.values());
    }

    private static void addTo(Map<List<Object>, Position> pPositions, long pInstrumentId, String pClientAccount,
                              long pBought, long pSold) {
        pPositions.merge(List.of(pInstrumentId, pClientAccount),
                new Position(pInstrumentId, (int) (pInstrumentId >>> 32), pClientAccount, pBought, pSold),
                (a, b) -> new Position(a.instrumentId(), a.marketId(), a.clientAccount(), a.bought() + b.bought(), a.sold() + b.sold()));
    }

    /** A position in a bulletin market's free text instrument and a client account, from its bulletin trades. */
    record BulletinPosition(int marketId, String instrument, String clientAccount, long bought, long sold) {
        long position() { return bought - sold; }
    }

    /**
     * The firm's position per bulletin market, instrument and client account from its BulletinTrades
     * documents. A firm can't trade with its own bulletin, so it is on one side of each trade.
     */
    static List<BulletinPosition> bulletinPositions(String pFirm, List<Document> pTrades) {
        Map<List<Object>, BulletinPosition> tPositions = new LinkedHashMap<>();   // key: market, instrument, client account
        for (Document tTrade : pTrades) {
            int    tMarketId   = tTrade.getInteger("marketId");
            String tInstrument = tTrade.getString("instrument");
            long   tQuantity   = tTrade.getLong("quantity");
            boolean tBuyer     = pFirm.equals(tTrade.getString("buyAccount"));
            if (!tBuyer && !pFirm.equals(tTrade.getString("sellAccount"))) continue;
            String tAccount    = Database.clientAccountOf(tTrade, tBuyer ? "buyClientAccount" : "sellClientAccount");
            tPositions.merge(List.of(tMarketId, tInstrument, tAccount),
                    new BulletinPosition(tMarketId, tInstrument, tAccount, tBuyer ? tQuantity : 0L, tBuyer ? 0L : tQuantity),
                    (a, b) -> new BulletinPosition(a.marketId(), a.instrument(), a.clientAccount(), a.bought() + b.bought(), a.sold() + b.sold()));
        }
        return new ArrayList<>(tPositions.values());
    }

    /** position * price, both scaled, as a scaled value (without overflowing on the way). */
    static long value(long pPosition, long pPrice) {
        return BigInteger.valueOf(pPosition).multiply(BigInteger.valueOf(pPrice))
                .divide(BigInteger.valueOf(MatchingEngine.SCALE)).longValue();
    }
}
