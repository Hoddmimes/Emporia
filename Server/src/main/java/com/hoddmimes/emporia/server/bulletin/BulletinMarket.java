package com.hoddmimes.emporia.server.bulletin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A bulletin market (collection/table BulletinMarkets), kept apart from the continuous and auction
 * markets. Its tradingStatus is OPEN or CLOSED, driven by its open and close times ("H:mm[:ss]", server
 * local time) by the MarketStateScheduler like the other markets, or changed in the admin interface;
 * SUSPENDED is only set and lifted in the admin interface, never by the scheduler. Bulletins
 * can only be added for the market's instruments: each entry is either a literal name, matched
 * exactly, or a regular expression written between slashes ({@code /Brent.*}{@code /}), which must
 * match the whole instrument text. A bulletin is removed when it has been in the book for
 * maxOrderTime ("hh:mm", hours and minutes; the hours may exceed 24, e.g. "168:00" for a week).
 *
 * The marketId is allocated from the same sequence as the other markets, so authorizations and
 * bdx topics refer to one market.
 */
public record BulletinMarket(int marketId, String name, String description, String tradingStatus,
                             String open, String close, List<String> instruments, String maxOrderTime, List<Pattern> patterns) {

    public static final String OPEN      = "OPEN";
    public static final String CLOSED    = "CLOSED";
    public static final String SUSPENDED = "SUSPENDED";

    /** A bulletin market with the patterns of its /regex/ instrument entries compiled. */
    public static BulletinMarket of(int pMarketId, String pName, String pDescription, String pTradingStatus,
                                    String pOpen, String pClose, List<String> pInstruments, String pMaxOrderTime) {
        List<Pattern> tPatterns = new ArrayList<>();
        for (String tEntry : pInstruments) {
            tPatterns.add(isRegex(tEntry) ? Pattern.compile(regexOf(tEntry)) : null);
        }
        return new BulletinMarket(pMarketId, pName, pDescription, pTradingStatus, pOpen, pClose,
                List.copyOf(pInstruments), pMaxOrderTime, tPatterns);
    }

    /** True if bulletins can be added for the instrument: it equals a literal entry or matches a regex entry. */
    public boolean allows(String pInstrument) {
        for (int i = 0; i < instruments.size(); i++) {
            Pattern tPattern = patterns.get(i);
            if ((tPattern != null) ? tPattern.matcher(pInstrument).matches() : instruments.get(i).equals(pInstrument)) {
                return true;
            }
        }
        return false;
    }

    public boolean isOpen() {
        return OPEN.equals(tradingStatus);
    }

    /** The maxOrderTime in milliseconds (0 if it is not a valid "hh:mm"). */
    public long maxOrderTimeMillis() {
        return Math.max(0, parseMaxOrderTime(maxOrderTime)) * 60_000L;
    }

    /** Minutes of an "hh:mm" time (hours 0 or more, minutes 0-59), or -1 if it is not one. */
    public static long parseMaxOrderTime(String pTime) {
        if (pTime == null || !pTime.trim().matches("\\d{1,5}:[0-5]\\d")) return -1;
        String[] tParts = pTime.trim().split(":");
        return Long.parseLong(tParts[0]) * 60 + Long.parseLong(tParts[1]);
    }

    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("id",            marketId);
        j.addProperty("name",          name);
        j.addProperty("description",   description);
        j.addProperty("tradingStatus", tradingStatus);
        j.addProperty("open",          open);
        j.addProperty("close",         close);
        JsonArray tInstruments = new JsonArray();
        instruments.forEach(tInstruments::add);
        j.add("instruments", tInstruments);
        j.addProperty("maxOrderTime",  maxOrderTime);
        return j;
    }

    // ── Instrument entries ────────────────────────────────────────────────

    /** An entry written as /regex/ is a regular expression; anything else is a literal name. */
    public static boolean isRegex(String pEntry) {
        return pEntry.length() > 2 && pEntry.startsWith("/") && pEntry.endsWith("/");
    }

    private static String regexOf(String pEntry) {
        return pEntry.substring(1, pEntry.length() - 1);
    }

    /** Null if the entries are valid (at least one, none blank, every regex compiles), otherwise why not. */
    public static String validateInstruments(List<String> pEntries) {
        if (pEntries.isEmpty()) return "At least one instrument is required";
        for (String tEntry : pEntries) {
            if (tEntry.isBlank()) return "An instrument must not be blank";
            if (isRegex(tEntry)) {
                try { Pattern.compile(regexOf(tEntry)); }
                catch (PatternSyntaxException e) {
                    return "Invalid regular expression " + tEntry + ": " + e.getDescription();
                }
            }
        }
        return null;
    }
}
