package com.hoddmimes.emporia.server;

import com.hoddmimes.emporia.server.db.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.bulletin.BulletinBoard;
import com.hoddmimes.emporia.server.me.MatchingEngine;
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

    public final Database           database;
    public final InstrumentCache    instrumentCache;
    public final AuthorizationCache authorizations;
    public final BdxDistributor     bdx;
    public final MatchingEngine     matchingEngine;
    public final BulletinBoard      bulletinBoard;
    public final JsonObject         configuration;
    public final int                sessionInactivitySeconds;

    public RestApiContext(Database pDatabase,
                          InstrumentCache pInstrumentCache,
                          AuthorizationCache pAuthorizations,
                          BdxDistributor pBdx,
                          MatchingEngine pMatchingEngine,
                          BulletinBoard pBulletinBoard,
                          JsonObject pConfiguration,
                          int pSessionInactivitySeconds) {
        database                 = pDatabase;
        instrumentCache          = pInstrumentCache;
        authorizations           = pAuthorizations;
        bdx                      = pBdx;
        matchingEngine           = pMatchingEngine;
        bulletinBoard            = pBulletinBoard;
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
     * Market ids the firm is authorized for, from the AuthorizationCache; a "*" authorization expands
     * to all cached markets and all bulletin markets.
     */
    public Set<Integer> authorizedMarkets(String pFirm) {
        return authorizations.authorizedMarkets(pFirm, this::allMarketIds);
    }

    /** True if the firm is authorized for the market (a "*" authorization covers every existing market). */
    public boolean isAuthorized(String pFirm, int pMarketId) {
        return authorizations.isAuthorized(pFirm, pMarketId, this::marketExists);
    }

    private Set<Integer> allMarketIds() {
        Set<Integer> tMarkets = new TreeSet<>();
        instrumentCache.getAllMarkets().forEach(m -> tMarkets.add(m.marketId()));
        bulletinBoard.getMarkets().forEach(m -> tMarkets.add(m.marketId()));
        return tMarkets;
    }

    /**
     * Reloads the AuthorizationCache from the database after an admin change of the authorizations
     * (or a firm delete) and updates the connected WebSocket clients.
     */
    public void reloadAuthorizations() {
        authorizations.load(database);
        refreshClientAuthorizations();
    }

    /**
     * Gives the connected WebSocket clients their firm's current authorized markets, after an
     * authorization change or a market added or deleted (which changes what "*" covers).
     */
    public void refreshClientAuthorizations() {
        bdx.updateAuthorizations(this::authorizedMarkets);
    }

    /** True if a market (continuous/auction or bulletin) has the id. */
    public boolean marketExists(int pMarketId) {
        return instrumentCache.getMarket(pMarketId) != null || bulletinBoard.getMarket(pMarketId) != null;
    }

    /**
     * A new market's marketId: the highest id of all markets, bulletin markets included, + 1.
     * Both kinds share one sequence, so an authorization or a bdx topic names one market.
     */
    public int nextMarketId() {
        return Math.max(instrumentCache.nextMarketId(), bulletinBoard.nextMarketId());
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
