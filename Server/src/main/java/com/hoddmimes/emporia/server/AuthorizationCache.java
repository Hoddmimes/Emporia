package com.hoddmimes.emporia.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * The firms' market authorizations (MongoDB collection Authorization) in memory: firm -> the market
 * ids, or "*" (all markets). Loaded at startup and reloaded whole after each admin
 * change (load), so MongoDB stays the master store; a change made directly in MongoDB applies only
 * after a restart. "*" is kept as it is and expanded at lookup time, so a market created later is
 * included. The map is replaced on each load, so readers need no lock.
 */
public class AuthorizationCache {

    private static final Logger cLog = LogManager.getLogger(AuthorizationCache.class);

    private static final String ALL_MARKETS = "*";

    /** One firm's authorizations: all markets ("*") and/or the listed market ids. */
    private record FirmMarkets(boolean all, Set<Integer> marketIds) {
        static final FirmMarkets NONE = new FirmMarkets(false, Set.of());
    }

    private volatile Map<String, FirmMarkets> mMarketsByFirm = Map.of();

    /** Reads all authorizations from the database, replacing the cached ones. */
    public void load(Database pDatabase) {
        set(pDatabase.listAuthorizations());
        cLog.info("Loaded authorizations for {} firm(s)", mMarketsByFirm.size());
    }

    /** Replaces the cached authorizations with the documents (firm, market). */
    synchronized void set(List<Document> pDocuments) {
        Map<String, Set<Integer>> tIds = new HashMap<>();
        Set<String> tAll = new HashSet<>();
        for (Document tDoc : pDocuments) {
            String tFirm   = tDoc.getString("firm");
            String tMarket = tDoc.getString("market");
            if (tFirm == null || tMarket == null) continue;
            Set<Integer> tFirmIds = tIds.computeIfAbsent(tFirm, f -> new HashSet<>());
            if (ALL_MARKETS.equals(tMarket.trim())) {
                tAll.add(tFirm);
            } else {
                try { tFirmIds.add(Integer.parseInt(tMarket.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        Map<String, FirmMarkets> tMap = new HashMap<>();
        tIds.forEach((tFirm, tFirmIds) -> tMap.put(tFirm, new FirmMarkets(tAll.contains(tFirm), Set.copyOf(tFirmIds))));
        mMarketsByFirm = Map.copyOf(tMap);
    }

    /**
     * The market ids the firm is authorized for; "*" is expanded to pAllMarkets (all markets and
     * bulletin markets).
     */
    public Set<Integer> authorizedMarkets(String pFirm, Supplier<Collection<Integer>> pAllMarkets) {
        FirmMarkets tFirm = firm(pFirm);
        Set<Integer> tMarkets = new TreeSet<>(tFirm.marketIds());
        if (tFirm.all()) tMarkets.addAll(pAllMarkets.get());
        return tMarkets;
    }

    /** True if the firm is authorized for the market; with "*" for every market pMarketExists accepts. */
    public boolean isAuthorized(String pFirm, int pMarketId, IntPredicate pMarketExists) {
        FirmMarkets tFirm = firm(pFirm);
        return tFirm.marketIds().contains(pMarketId) || (tFirm.all() && pMarketExists.test(pMarketId));
    }

    private FirmMarkets firm(String pFirm) {
        return (pFirm == null) ? FirmMarkets.NONE : mMarketsByFirm.getOrDefault(pFirm, FirmMarkets.NONE);
    }
}
