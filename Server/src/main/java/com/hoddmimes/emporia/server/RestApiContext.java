package com.hoddmimes.emporia.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.http.Cookie;
import jakarta.servlet.http.HttpSession;

import java.util.Set;
import java.util.TreeSet;

/**
 * Shared dependencies and utility methods passed to each REST endpoint handler.
 */
public class RestApiContext {

    // HTTP session attributes set at login. A browser can be logged in both as
    // a trading user and as an admin, since they share one HTTP session.
    public static final String USER       = "user";
    public static final String FIRM       = "firm";
    public static final String MARKETS    = "markets";     // JsonArray of authorized market ids
    public static final String ADMIN_USER = "admin-user";

    public final Database        database;
    public final InstrumentCache instrumentCache;
    public final PriceFeed       priceFeed;
    public final JsonObject      configuration;
    public final int             sessionInactivitySeconds;

    public RestApiContext(Database pDatabase,
                          InstrumentCache pInstrumentCache,
                          JsonObject pConfiguration,
                          int pSessionInactivitySeconds) {
        database                 = pDatabase;
        instrumentCache          = pInstrumentCache;
        priceFeed                = new PriceFeed(pInstrumentCache);
        configuration            = pConfiguration;
        sessionInactivitySeconds = pSessionInactivitySeconds;
    }

    // ── Response helpers ──────────────────────────────────────────────────

    public static String statusMessage(String pMessage) {
        JsonObject tRsp = new JsonObject();
        tRsp.addProperty("success", false);
        tRsp.addProperty("message", pMessage);
        return tRsp.toString();
    }

    public static Cookie getLoginCookie(String pFirm, String pUser, String pSessionId) {
        String tData = pFirm + "#" + pUser + "#" + pSessionId;
        Cookie cookie = new Cookie("emporia", tData);
        cookie.setPath("/");
        cookie.setComment("SameSite=Strict");
        cookie.setMaxAge(60 * 60 * 24);
        return cookie;
    }

    // ── Request helpers ───────────────────────────────────────────────────

    public static String field(JsonObject pBody, String pKey, String pDefault) {
        return pBody.has(pKey) ? pBody.get(pKey).getAsString() : pDefault;
    }

    /** Replaces all spaces in a name with "-". */
    public static String sanitizeName(String pName) {
        return pName == null ? "" : pName.replace(" ", "-");
    }

    // ── Authorization helpers ─────────────────────────────────────────────

    /**
     * Market ids the firm is authorized for. Authorizations are read from the
     * database on each call; a "*" authorization expands to all cached markets.
     */
    public Set<Integer> authorizedMarkets(String pFirm) {
        Set<Integer> tMarkets = new TreeSet<>();
        for (var tDoc : database.listAuthorizationsByFirm(pFirm)) {
            String tMarket = tDoc.getString("market");
            if ("*".equals(tMarket)) {
                instrumentCache.getAllMarkets().forEach(m -> tMarkets.add(m.marketId()));
            } else {
                try { tMarkets.add(Integer.parseInt(tMarket)); } catch (NumberFormatException ignored) {}
            }
        }
        return tMarkets;
    }

    public JsonArray authorizedMarketsAsJson(String pFirm) {
        JsonArray tArray = new JsonArray();
        authorizedMarkets(pFirm).forEach(m -> tArray.add(String.valueOf(m)));
        return tArray;
    }

    // ── Session helpers ───────────────────────────────────────────────────

    /**
     * Removes the given login attributes and invalidates the session once
     * neither a trading user nor an admin is logged in on it.
     */
    public static void logout(HttpSession pSession, String... pAttributes) {
        for (String tAttribute : pAttributes) {
            pSession.removeAttribute(tAttribute);
        }
        if (pSession.getAttribute(USER) == null && pSession.getAttribute(ADMIN_USER) == null) {
            pSession.invalidate();
        }
    }
}
