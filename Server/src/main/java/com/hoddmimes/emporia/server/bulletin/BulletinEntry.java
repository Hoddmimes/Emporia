package com.hoddmimes.emporia.server.bulletin;

import com.hoddmimes.emporia.server.Database;
import org.bson.Document;

import java.util.concurrent.ScheduledFuture;

/**
 * One bulletin on the bulletin board, with at most one pending offer. Only used by BulletinBoard,
 * under its lock. Prices and quantities are longs scaled by MatchingEngine.SCALE; 0 means the
 * bulletin did not specify a price or quantity; a null side means it did not specify a side (the
 * offering firm chooses one). The bulletin's clientAccount is the owner's client account, the
 * offer's offerClientAccount the offering firm's (AddOrderRequest.DEFAULT_CLIENT_ACCOUNT when not
 * given). A bulletin is stored in the Bulletins collection (toDocument) without its pending offer,
 * so it is reloaded as ACTIVE.
 */
class BulletinEntry {

    static final String STATE_ACTIVE        = "ACTIVE";          // open for offers
    static final String STATE_OFFER_PENDING = "OFFER_PENDING";   // an offer waits for the owner's answer
    static final String STATE_TRADED        = "TRADED";          // fully traded, removed from the board
    static final String STATE_WITHDRAWN     = "WITHDRAWN";       // withdrawn by the owner, removed from the board
    static final String STATE_EXPIRED       = "EXPIRED";         // the market's maxOrderTime passed, removed from the board
    static final String STATE_DELETED       = "DELETED";         // deleted by the admin, removed from the board

    final String bulletinId;
    final int    marketId;
    final String instrument;
    final String side;              // BUY or SELL, the owner's side; null if not given
    final long   price;
    final long   quantity;
    final String ownerFirm;
    final String ownerUser;
    final String userRef;
    final String clientAccount;     // the owner's client account
    final long   createTime;        // epoch millis
    final long   expiresAt;         // epoch millis, createTime + the market's maxOrderTime

    long   remainingQuantity;       // decreases with each accepted offer (stays 0 when no quantity was given)
    String state = STATE_ACTIVE;
    ScheduledFuture<?> expiry;      // removes the bulletin at expiresAt

    // The pending offer, set while the state is OFFER_PENDING
    long   offerSeq;                // identifies the offer for its expiry timer
    String offerFirm;
    String offerUser;
    String offerSide;               // BUY or SELL, the offer's side
    long   offerPrice;
    long   offerQuantity;
    String offerRef;
    String offerClientAccount;      // the offering firm's client account
    long   offerExpiresAt;          // epoch millis
    ScheduledFuture<?> offerExpiry;

    BulletinEntry(String pBulletinId, int pMarketId, String pInstrument, String pSide, long pPrice, long pQuantity,
                  long pRemainingQuantity, String pOwnerFirm, String pOwnerUser, String pUserRef,
                  String pClientAccount, long pCreateTime, long pExpiresAt) {
        bulletinId        = pBulletinId;
        marketId          = pMarketId;
        instrument        = pInstrument;
        side              = pSide;
        price             = pPrice;
        quantity          = pQuantity;
        remainingQuantity = pRemainingQuantity;
        ownerFirm         = pOwnerFirm;
        ownerUser         = pOwnerUser;
        userRef           = pUserRef;
        clientAccount     = pClientAccount;
        createTime        = pCreateTime;
        expiresAt         = pExpiresAt;
    }

    Document toDocument() {
        return new Document("bulletinId",        bulletinId)
                .append("marketId",          marketId)
                .append("instrument",        instrument)
                .append("side",              side)
                .append("price",             price)
                .append("quantity",          quantity)
                .append("remainingQuantity", remainingQuantity)
                .append("ownerFirm",         ownerFirm)
                .append("ownerUser",         ownerUser)
                .append("userRef",           userRef)
                .append("clientAccount",     clientAccount)
                .append("createTime",        createTime)
                .append("expiresAt",         expiresAt);
    }

    static BulletinEntry fromDocument(Document pDoc) {
        return new BulletinEntry(pDoc.getString("bulletinId"), pDoc.getInteger("marketId"),
                pDoc.getString("instrument"), pDoc.getString("side"),
                pDoc.getLong("price"), pDoc.getLong("quantity"), pDoc.getLong("remainingQuantity"),
                pDoc.getString("ownerFirm"), pDoc.getString("ownerUser"), pDoc.getString("userRef"),
                Database.clientAccountOf(pDoc, "clientAccount"),   // the default one for bulletins stored before client accounts
                pDoc.getLong("createTime"), pDoc.getLong("expiresAt"));
    }

    /** The other side: SELL for BUY, BUY for SELL. */
    static String opposite(String pSide) {
        return "BUY".equals(pSide) ? "SELL" : "BUY";
    }

    /** The owner's side in a trade on the pending offer: the opposite of the offer's side. */
    String ownerTradeSide() {
        return opposite(offerSide);
    }

    void clearOffer() {
        if (offerExpiry != null) offerExpiry.cancel(false);
        offerExpiry    = null;
        offerFirm      = null;
        offerUser      = null;
        offerSide      = null;
        offerPrice     = 0;
        offerQuantity  = 0;
        offerRef       = null;
        offerClientAccount = null;
        offerExpiresAt = 0;
    }
}
