package com.hoddmimes.emporia.server.bdx;

import com.google.gson.JsonObject;

/**
 * A broadcast (bdx) message sent to trading clients on the /ws WebSocket.
 *
 * Every message is a JSON object starting with {@code type} and {@code topic}, followed by
 * {@code marketId}, {@code instrumentId} (instrument messages only) and the message's own fields.
 * The topic is {@code /<type>/<marketId>/<instrumentId>}, or {@code /<type>/<marketId>} for market
 * messages. A public message goes to every client whose firm is authorized for the market; a
 * private message ({@link #forFirm}) only to the clients of that firm.
 */
public final class BdxMessage {

    // Message types
    public static final String PRICE_UPDATE = "PriceUpdate";   // public, latest kept as snapshot
    public static final String MARKET_STATE = "MarketState";   // public, latest kept as snapshot
    public static final String TRADE        = "Trade";         // public, anonymous
    public static final String ADD_ORDER    = "AddOrder";      // public, anonymous order book event
    public static final String MODIFY_ORDER = "ModifyOrder";   // public, anonymous order book event
    public static final String REMOVE_ORDER = "RemoveOrder";   // public, anonymous order book event
    public static final String OWN_ORDER    = "OwnOrder";      // private to the order's firm
    public static final String OWN_TRADE    = "OwnTrade";      // private to the firm of one trade side

    // Bulletin board message types (market messages, no instrumentId)
    public static final String BULLETIN           = "Bulletin";          // public, anonymous bulletin event
    public static final String OWN_BULLETIN       = "OwnBulletin";       // private to the bulletin owner's firm
    public static final String BULLETIN_OFFER     = "BulletinOffer";     // private to the owner's and the offering firm
    public static final String BULLETIN_TRADE     = "BulletinTrade";     // public, anonymous
    public static final String OWN_BULLETIN_TRADE = "OwnBulletinTrade";  // private to the firm of one trade side

    private final String     mType;
    private final String     mTopic;
    private final int        mMarketId;
    private final String     mFirm;
    private final JsonObject mJson = new JsonObject();

    private BdxMessage(String pType, int pMarketId, Long pInstrumentId, String pFirm) {
        mType     = pType;
        mMarketId = pMarketId;
        mFirm     = pFirm;
        mTopic    = "/" + pType + "/" + pMarketId + (pInstrumentId != null ? "/" + pInstrumentId : "");
        mJson.addProperty("type",     pType);
        mJson.addProperty("topic",    mTopic);
        mJson.addProperty("marketId", pMarketId);
        if (pInstrumentId != null) mJson.addProperty("instrumentId", pInstrumentId);
    }

    /** A public message about an instrument. */
    public static BdxMessage instrument(String pType, int pMarketId, long pInstrumentId) {
        return new BdxMessage(pType, pMarketId, pInstrumentId, null);
    }

    /** A public message about a market. */
    public static BdxMessage market(String pType, int pMarketId) {
        return new BdxMessage(pType, pMarketId, null, null);
    }

    /** A private message about an instrument, only sent to the clients of the firm. */
    public static BdxMessage forFirm(String pType, int pMarketId, long pInstrumentId, String pFirm) {
        return new BdxMessage(pType, pMarketId, pInstrumentId, pFirm);
    }

    /** A private message about a market, only sent to the clients of the firm. */
    public static BdxMessage forFirm(String pType, int pMarketId, String pFirm) {
        return new BdxMessage(pType, pMarketId, null, pFirm);
    }

    /** Adds a field; a null value is sent as JSON null (the client clears its previous value). */
    public BdxMessage put(String pName, Number pValue)  { mJson.addProperty(pName, pValue); return this; }
    public BdxMessage put(String pName, String pValue)  { mJson.addProperty(pName, pValue); return this; }
    public BdxMessage put(String pName, Boolean pValue) { mJson.addProperty(pName, pValue); return this; }

    public String type()     { return mType; }
    public String topic()    { return mTopic; }
    public int    marketId() { return mMarketId; }
    /** The firm a private message is for; null for a public message. */
    public String firm()     { return mFirm; }

    /** A copy of the message as JSON. */
    public JsonObject toJson() { return mJson.deepCopy(); }

    @Override
    public String toString() { return mJson.toString(); }
}
