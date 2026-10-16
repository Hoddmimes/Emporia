package com.hoddmimes.emporia.server.bulletin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.bdx.BdxMessage;
import com.hoddmimes.emporia.server.me.MatchingEngine;
import com.hoddmimes.emporia.server.db.BulletinTrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BulletinBoardTest {

    private static final long S = MatchingEngine.SCALE;

    private BulletinBoard    mBoard;
    private final List<BdxMessage> mPublished = new CopyOnWriteArrayList<>();   // the expiry publishes on the timer thread

    @BeforeEach
    void setUp() {
        mBoard = new BulletinBoard(null, mPublished::add);   // no database: nothing is stored
        mBoard.putMarket(market(1, "BB", "OPEN", "1:00"));
        mPublished.clear();
    }

    private static BulletinMarket market(int pMarketId, String pName, String pStatus, String pMaxOrderTime) {
        return BulletinMarket.of(pMarketId, pName, "", pStatus, "", "", List.of("Old tractor", "/Brent.*/", "x"), pMaxOrderTime);
    }

    private String add(String pFirm, String pSide, long pPrice, long pQuantity) {
        BulletinBoard.Result tResult = mBoard.addBulletin(pFirm, "u-" + pFirm, 1, "Old tractor", pSide,
                pPrice, pQuantity, "ref-" + pFirm);
        assertTrue(tResult.success(), tResult.error());
        assertEquals(201, tResult.status());
        return tResult.body().get("bulletinId").getAsString();
    }

    private List<String> types() {
        return mPublished.stream().map(BdxMessage::type).toList();
    }

    private List<BdxMessage> ofType(String pType) {
        return mPublished.stream().filter(m -> m.type().equals(pType)).toList();
    }

    private static String str(BdxMessage pMessage, String pField) {
        return pMessage.toJson().get(pField).getAsString();
    }

    @Test
    void addPublishesPublicAnonymousAndPrivateOwnBulletin() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        assertEquals(List.of(BdxMessage.BULLETIN, BdxMessage.OWN_BULLETIN), types());

        BdxMessage tPublic = mPublished.get(0);
        assertNull(tPublic.firm());
        assertEquals("/Bulletin/1", tPublic.topic());
        JsonObject tJson = tPublic.toJson();
        assertEquals("ADDED", tJson.get("event").getAsString());
        assertEquals(tId, tJson.get("bulletinId").getAsString());
        assertEquals(100.0, tJson.get("price").getAsDouble());
        assertFalse(tJson.has("userRef"));
        assertFalse(tJson.has("user"));

        BdxMessage tOwn = mPublished.get(1);
        assertEquals("FA", tOwn.firm());
        assertEquals("ref-FA", str(tOwn, "userRef"));
    }

    @Test
    void bulletinWithoutSideLetsTheOfferingFirmChooseTheSide() {
        String tId = add("FA", null, 100 * S, 10 * S);
        assertTrue(mPublished.get(0).toJson().get("side").isJsonNull());   // public Bulletin: no side
        assertTrue(mBoard.getBulletins("FB", Set.of(1)).get(0).getAsJsonObject().get("side").isJsonNull());
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "x", "HOLD", 0, 0, null).status());

        // The offer must name its side
        assertEquals(400, mBoard.makeOffer("FB", "u-FB", tId, 98 * S, 4 * S, "o1", 0).status());
        assertEquals(400, mBoard.makeOffer("FB", "u-FB", tId, "HOLD", 98 * S, 4 * S, "o1", 0).status());
        mPublished.clear();
        assertTrue(mBoard.makeOffer("FB", "u-FB", tId, "SELL", 98 * S, 4 * S, "o1", 0).success());
        assertEquals("SELL", str(ofType(BdxMessage.BULLETIN_OFFER).get(0), "side"));   // to the owner: the offer's side
        JsonObject tOffer = mBoard.getBulletins("FA", Set.of(1)).get(0).getAsJsonObject().getAsJsonObject("offer");
        assertEquals("SELL", tOffer.get("side").getAsString());

        // Accepted: the offering firm sells, so the owner buys
        mPublished.clear();
        assertTrue(mBoard.answerOffer("FA", tId, true).success());
        List<BdxMessage> tOwnTrades = ofType(BdxMessage.OWN_BULLETIN_TRADE);
        assertEquals("FA",   tOwnTrades.get(0).firm());
        assertEquals("BUY",  str(tOwnTrades.get(0), "side"));
        assertEquals("FB",   tOwnTrades.get(1).firm());
        assertEquals("SELL", str(tOwnTrades.get(1), "side"));

        // The next offer may take the other side
        assertTrue(mBoard.makeOffer("FC", "u-FC", tId, "BUY", 99 * S, 1 * S, null, 0).success());
    }

    @Test
    void offerOnBulletinWithSideMustBeOnTheOppositeSide() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        assertEquals(400, mBoard.makeOffer("FB", "u", tId, "SELL", 98 * S, 4 * S, null, 0).status());
        assertTrue(mBoard.makeOffer("FB", "u", tId, "BUY", 98 * S, 4 * S, null, 0).success());
    }

    @Test
    void addIsRejectedOutsideOpenBulletinMarkets() {
        assertEquals(400, mBoard.addBulletin("FA", "u", 9, "x", "BUY", 0, 0, null).status());   // no such bulletin market
        mBoard.putMarket(market(1, "BB", "CLOSED", "1:00"));
        mPublished.clear();
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "x", "BUY", 0, 0, null).status());
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, " ", "BUY", 0, 0, null).status());
        assertTrue(mPublished.isEmpty());
    }

    @Test
    void acceptedOfferTradesAndPartialFillKeepsTheBulletin() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        mPublished.clear();

        BulletinBoard.Result tOffer = mBoard.makeOffer("FB", "u-FB", tId, 98 * S, 4 * S, "offer-1", 0);
        assertTrue(tOffer.success(), tOffer.error());
        assertEquals("OFFER_PENDING", tOffer.body().get("state").getAsString());
        assertEquals(List.of(BdxMessage.BULLETIN, BdxMessage.OWN_BULLETIN,
                BdxMessage.BULLETIN_OFFER, BdxMessage.BULLETIN_OFFER), types());
        List<BdxMessage> tOffers = ofType(BdxMessage.BULLETIN_OFFER);
        assertEquals("FA", tOffers.get(0).firm());
        assertEquals("OWNER", str(tOffers.get(0), "role"));
        assertFalse(tOffers.get(0).toJson().has("offerRef"));
        assertEquals("FB", tOffers.get(1).firm());
        assertEquals("offer-1", str(tOffers.get(1), "offerRef"));
        assertEquals("BUY", str(tOffers.get(1), "side"));

        // Only one pending offer at a time
        assertEquals(400, mBoard.makeOffer("FC", "u", tId, 99 * S, 1 * S, null, 0).status());
        // Only the owner's firm can answer
        assertEquals(404, mBoard.answerOffer("FB", tId, true).status());

        mPublished.clear();
        BulletinBoard.Result tAccept = mBoard.answerOffer("FA", tId, true);
        assertTrue(tAccept.success(), tAccept.error());
        assertEquals("ACTIVE", tAccept.body().get("state").getAsString());
        assertEquals(6 * S, tAccept.body().get("remainingQuantity").getAsLong());
        assertEquals(List.of(BdxMessage.BULLETIN_OFFER, BdxMessage.BULLETIN_OFFER,
                BdxMessage.BULLETIN_TRADE, BdxMessage.OWN_BULLETIN_TRADE, BdxMessage.OWN_BULLETIN_TRADE,
                BdxMessage.BULLETIN, BdxMessage.OWN_BULLETIN), types());

        BdxMessage tTrade = ofType(BdxMessage.BULLETIN_TRADE).get(0);
        assertNull(tTrade.firm());
        assertEquals(98.0, tTrade.toJson().get("price").getAsDouble());
        assertEquals(4.0,  tTrade.toJson().get("quantity").getAsDouble());
        List<BdxMessage> tOwnTrades = ofType(BdxMessage.OWN_BULLETIN_TRADE);
        assertEquals("FA",   tOwnTrades.get(0).firm());
        assertEquals("SELL", str(tOwnTrades.get(0), "side"));
        assertEquals("FB",   tOwnTrades.get(1).firm());
        assertEquals("BUY",  str(tOwnTrades.get(1), "side"));
        assertEquals("UPDATED", str(ofType(BdxMessage.BULLETIN).get(0), "event"));
        assertEquals(6.0, ofType(BdxMessage.BULLETIN).get(0).toJson().get("remainingQuantity").getAsDouble());

        // More than the remaining quantity is rejected; the rest trades the bulletin out
        assertEquals(400, mBoard.makeOffer("FB", "u", tId, 98 * S, 7 * S, null, 0).status());
        assertTrue(mBoard.makeOffer("FB", "u", tId, 97 * S, 6 * S, null, 0).success());
        mPublished.clear();
        BulletinBoard.Result tLast = mBoard.answerOffer("FA", tId, true);
        assertEquals("TRADED", tLast.body().get("state").getAsString());
        assertEquals("REMOVED", str(ofType(BdxMessage.BULLETIN).get(0), "event"));
        assertEquals(0, mBoard.getBulletins("FA", Set.of(1)).size());
    }

    @Test
    void rejectedOfferReopensTheBulletin() {
        String tId = add("FA", "BUY", 0, 0);   // no price or quantity
        assertTrue(mBoard.makeOffer("FB", "u", tId, 50 * S, 2 * S, null, 0).success());
        mPublished.clear();
        BulletinBoard.Result tReject = mBoard.answerOffer("FA", tId, false);
        assertEquals("ACTIVE", tReject.body().get("state").getAsString());
        assertEquals("REJECTED", str(ofType(BdxMessage.BULLETIN_OFFER).get(0), "event"));
        assertTrue(ofType(BdxMessage.BULLETIN_TRADE).isEmpty());
        assertTrue(ofType(BdxMessage.BULLETIN).get(0).toJson().get("price").isJsonNull());

        // A bulletin without a quantity is done after its first trade
        assertTrue(mBoard.makeOffer("FB", "u", tId, 50 * S, 2 * S, null, 0).success());
        assertEquals("TRADED", mBoard.answerOffer("FA", tId, true).body().get("state").getAsString());
    }

    @Test
    void ownFirmCannotOfferAndOfferExpires() throws Exception {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        assertEquals(400, mBoard.makeOffer("FA", "other-user", tId, 99 * S, 1 * S, null, 0).status());
        assertEquals(404, mBoard.makeOffer("FB", "u", "nope", 99 * S, 1 * S, null, 0).status());

        assertTrue(mBoard.makeOffer("FB", "u", tId, 99 * S, 1 * S, null, 1).success());
        mPublished.clear();
        long tDeadline = System.currentTimeMillis() + 5000;
        while (ofType(BdxMessage.BULLETIN_OFFER).isEmpty() && System.currentTimeMillis() < tDeadline) {
            Thread.sleep(50);
        }
        synchronized (mBoard) {   // the expiry ran on the timer thread
            assertEquals("EXPIRED", str(ofType(BdxMessage.BULLETIN_OFFER).get(0), "event"));
            assertEquals("ACTIVE", mBoard.getBulletins("FA", Set.of(1)).get(0).getAsJsonObject().get("state").getAsString());
        }
    }

    @Test
    void offeringFirmWithdrawsItsPendingOffer() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        assertEquals(404, mBoard.withdrawOffer("FB", tId).status());   // no offer yet
        assertTrue(mBoard.makeOffer("FB", "u-FB", tId, 99 * S, 2 * S, "o1", 0).success());
        assertEquals(404, mBoard.withdrawOffer("FA", tId).status());   // the owner answers, it can't withdraw
        assertEquals(404, mBoard.withdrawOffer("FC", tId).status());   // not its offer
        assertEquals(404, mBoard.withdrawOffer("FB", "nope").status());

        mBoard.putMarket(market(1, "BB", "CLOSED", "1:00"));   // allowed in any market state
        mPublished.clear();
        BulletinBoard.Result tResult = mBoard.withdrawOffer("FB", tId);
        assertTrue(tResult.success(), tResult.error());
        assertEquals("ACTIVE", tResult.body().get("state").getAsString());
        List<BdxMessage> tOffers = ofType(BdxMessage.BULLETIN_OFFER);
        assertEquals(2, tOffers.size());
        assertEquals("CANCELLED", str(tOffers.get(0), "event"));
        assertEquals("OWNER",     str(tOffers.get(0), "role"));
        assertEquals("FA",        tOffers.get(0).firm());
        assertEquals("OFFERER",   str(tOffers.get(1), "role"));
        assertEquals("FB",        tOffers.get(1).firm());
        assertEquals("o1",        str(tOffers.get(1), "offerRef"));
        assertEquals("ACTIVE", str(ofType(BdxMessage.BULLETIN).get(0), "state"));
        assertEquals(404, mBoard.withdrawOffer("FB", tId).status());   // already gone

        // The bulletin takes offers again
        mBoard.putMarket(market(1, "BB", "OPEN", "1:00"));
        assertTrue(mBoard.makeOffer("FC", "u-FC", tId, 98 * S, 1 * S, null, 0).success());
    }

    @Test
    void tradeRecordHasTheBuyerAndSellerOfTheTrade() {
        BulletinEntry tEntry = new BulletinEntry("b1", 1, "Old tractor", "SELL", 100 * S, 10 * S, 10 * S,
                "FA", "u-FA", "ref-FA", "fa-acct", 0L, 0L);
        tEntry.offerFirm     = "FB";
        tEntry.offerUser     = "u-FB";
        tEntry.offerSide     = "BUY";
        tEntry.offerPrice    = 99 * S;
        tEntry.offerQuantity = 4 * S;
        tEntry.offerRef      = "o1";
        tEntry.offerClientAccount = "fb-acct";
        BulletinTrade tTrade = BulletinBoard.tradeRecord(tEntry, "t1", 0L);
        assertEquals("FB", tTrade.buyAccount());
        assertEquals("u-FB", tTrade.buyUser());
        assertEquals("o1", tTrade.buyRef());
        assertEquals("FA", tTrade.sellAccount());
        assertEquals("ref-FA", tTrade.sellRef());
        assertEquals("fb-acct", tTrade.buyClientAccount());
        assertEquals("fa-acct", tTrade.sellClientAccount());
        assertEquals(99 * S, tTrade.price());
        assertEquals(4 * S, tTrade.quantity());
        assertEquals("Old tractor", tTrade.instrument());
        assertEquals(1, tTrade.marketId());

        tEntry.offerSide = "SELL";   // a bulletin without a side: the offering firm sold, the owner bought
        tTrade = BulletinBoard.tradeRecord(tEntry, "t2", 0L);
        assertEquals("FA", tTrade.buyAccount());
        assertEquals("FB", tTrade.sellAccount());
    }

    @Test
    void clientAccountsOfTheOwnerAndTheOffererArePrivate() {
        String tId = mBoard.addBulletin("FA", "u-FA", 1, "Old tractor", "SELL", 100 * S, 10 * S, null, "fa-acct")
                .body().get("bulletinId").getAsString();
        assertEquals("fa-acct", str(ofType(BdxMessage.OWN_BULLETIN).get(0), "clientAccount"));
        assertFalse(ofType(BdxMessage.BULLETIN).get(0).toJson().has("clientAccount"));
        assertTrue(mBoard.makeOffer("FB", "u-FB", tId, null, 99 * S, 4 * S, null, null, 0).success());   // no account: omni

        JsonObject tOwnerView = mBoard.getBulletins("FA", Set.of(1)).get(0).getAsJsonObject();
        assertEquals("fa-acct", tOwnerView.get("clientAccount").getAsString());
        assertFalse(tOwnerView.getAsJsonObject("offer").has("clientAccount"));   // the offerer's account is not shown to the owner
        JsonObject tOffererView = mBoard.getBulletins("FB", Set.of(1)).get(0).getAsJsonObject();
        assertFalse(tOffererView.has("clientAccount"));
        assertEquals("omni", tOffererView.getAsJsonObject("offer").get("clientAccount").getAsString());

        List<BdxMessage> tOffers = ofType(BdxMessage.BULLETIN_OFFER);
        assertEquals("fa-acct", str(tOffers.get(0), "clientAccount"));   // OWNER
        assertEquals("omni",    str(tOffers.get(1), "clientAccount"));   // OFFERER

        mPublished.clear();
        assertTrue(mBoard.answerOffer("FA", tId, true).success());
        List<BdxMessage> tOwnTrades = ofType(BdxMessage.OWN_BULLETIN_TRADE);
        assertEquals("fa-acct", str(tOwnTrades.get(0), "clientAccount"));
        assertEquals("omni",    str(tOwnTrades.get(1), "clientAccount"));
        assertFalse(ofType(BdxMessage.BULLETIN_TRADE).get(0).toJson().has("clientAccount"));
    }

    @Test
    void withdrawOnlyByOwnerAndNotWithPendingOffer() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        assertEquals(404, mBoard.withdrawBulletin("FB", tId).status());
        assertTrue(mBoard.makeOffer("FB", "u", tId, 99 * S, 1 * S, null, 0).success());
        assertEquals(400, mBoard.withdrawBulletin("FA", tId).status());
        mBoard.answerOffer("FA", tId, false);
        mPublished.clear();
        BulletinBoard.Result tWithdraw = mBoard.withdrawBulletin("FA", tId);
        assertEquals("WITHDRAWN", tWithdraw.body().get("state").getAsString());
        assertEquals("REMOVED", str(ofType(BdxMessage.BULLETIN).get(0), "event"));
        assertEquals(0, mBoard.getMarketId(tId));
    }

    @Test
    void listShowsOfferDetailsOnlyToTheInvolvedFirms() {
        String tId = add("FA", "SELL", 100 * S, 10 * S);
        mBoard.makeOffer("FB", "u", tId, 99 * S, 1 * S, "o1", 0);

        JsonObject tOwner = mBoard.getBulletins("FA", Set.of(1)).get(0).getAsJsonObject();
        assertTrue(tOwner.get("own").getAsBoolean());
        assertEquals("ref-FA", tOwner.get("userRef").getAsString());
        assertFalse(tOwner.getAsJsonObject("offer").has("offerRef"));

        JsonObject tOfferer = mBoard.getBulletins("FB", Set.of(1)).get(0).getAsJsonObject();
        assertFalse(tOfferer.has("userRef"));
        assertEquals("o1", tOfferer.getAsJsonObject("offer").get("offerRef").getAsString());

        JsonObject tOther = mBoard.getBulletins("FC", Set.of(1)).get(0).getAsJsonObject();
        assertFalse(tOther.has("offer"));
        assertEquals("OFFER_PENDING", tOther.get("state").getAsString());

        JsonArray tOtherMarket = mBoard.getBulletins("FA", Set.of(2));
        assertEquals(0, tOtherMarket.size());
    }

    @Test
    void instrumentMustBeALiteralOrMatchARegex() {
        assertTrue(mBoard.addBulletin("FA", "u", 1, "Old tractor", "BUY", 0, 0, null).success());
        assertTrue(mBoard.addBulletin("FA", "u", 1, "Brent Crude 2027", "BUY", 0, 0, null).success());
        assertTrue(mBoard.addBulletin("FA", "u", 1, "Brent", "BUY", 0, 0, null).success());
        // Literals are exact (case-sensitive); a regex must match the whole text
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "old tractor", "BUY", 0, 0, null).status());
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "Old tractor 2", "BUY", 0, 0, null).status());
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "North Brent", "BUY", 0, 0, null).status());
        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "xx", "BUY", 0, 0, null).status());
    }

    @Test
    void maxOrderTimeIsHoursAndMinutes() {
        assertEquals(90,        BulletinMarket.parseMaxOrderTime("1:30"));
        assertEquals(168 * 60,  BulletinMarket.parseMaxOrderTime("168:00"));
        assertEquals(0,         BulletinMarket.parseMaxOrderTime("00:00"));
        assertEquals(-1,        BulletinMarket.parseMaxOrderTime("1:60"));
        assertEquals(-1,        BulletinMarket.parseMaxOrderTime("90"));
        assertEquals(-1,        BulletinMarket.parseMaxOrderTime("1:5"));
        assertEquals(-1,        BulletinMarket.parseMaxOrderTime(""));
    }

    @Test
    void validateInstruments() {
        assertNull(BulletinMarket.validateInstruments(List.of("Old tractor", "/Brent.*/", "/")));
        assertNotNull(BulletinMarket.validateInstruments(List.of()));
        assertNotNull(BulletinMarket.validateInstruments(List.of("/[a-/")));
        assertFalse(BulletinMarket.isRegex("/"));    // too short to be a regex: a literal
        assertFalse(BulletinMarket.isRegex("//"));
        assertTrue(BulletinMarket.isRegex("/a/"));
    }

    @Test
    void marketStateIsPublishedOnAddAndStatusChange() {
        mBoard.putMarket(market(2, "BB2", "CLOSED", "1:00"));
        mBoard.putMarket(market(2, "BB2", "CLOSED", "2:00"));   // no status change
        mBoard.putMarket(market(2, "BB2", "OPEN", "2:00"));
        assertEquals(List.of(BdxMessage.MARKET_STATE, BdxMessage.MARKET_STATE), types());
        assertEquals("/MarketState/2", mPublished.get(1).topic());
        assertEquals("OPEN", str(mPublished.get(1), "tradingStatus"));
        assertEquals(3, mBoard.nextMarketId());
    }

    @Test
    void bulletinIsRemovedAfterMaxOrderTime() throws Exception {
        String tKept = add("FA", "SELL", 100 * S, 10 * S);
        mBoard.putMarket(market(1, "BB", "OPEN", "0:00"));   // only for the test: expire at once
        String tId = add("FB", "BUY", 100 * S, 10 * S);
        long tDeadline = System.currentTimeMillis() + 5000;
        while (mBoard.getMarketId(tId) != 0 && System.currentTimeMillis() < tDeadline) {
            Thread.sleep(20);
        }
        synchronized (mBoard) {
            assertEquals(0, mBoard.getMarketId(tId));
            BdxMessage tRemoved = ofType(BdxMessage.BULLETIN).stream()
                    .filter(m -> str(m, "event").equals("REMOVED")).findFirst().orElseThrow();
            assertEquals(tId, str(tRemoved, "bulletinId"));
            assertEquals("EXPIRED", str(tRemoved, "state"));
            // A changed maxOrderTime applies to new bulletins only
            assertEquals(1, mBoard.getMarketId(tKept));
            assertEquals(1, mBoard.bulletinCount(1));
        }
    }

    @Test
    void adminDeletesAllBulletinsOfAMarketInAnyState() {
        String tA = add("FA", "SELL", 100 * S, 10 * S);
        String tB = add("FA", "BUY", 90 * S, 5 * S);
        assertTrue(mBoard.makeOffer("FB", "u", tA, 99 * S, 1 * S, "o1", 0).success());
        mBoard.putMarket(market(2, "BB2", "OPEN", "1:00"));
        assertTrue(mBoard.addBulletin("FA", "u", 2, "x", "BUY", 0, 0, null).success());
        mBoard.putMarket(market(1, "BB", "CLOSED", "1:00"));
        mPublished.clear();

        assertEquals(2, mBoard.deleteAllBulletins(1));
        assertNotNull(mBoard.getMarket(1));         // the market itself stays
        assertEquals(0, mBoard.getMarketId(tA));
        assertEquals(0, mBoard.getMarketId(tB));
        assertEquals(1, mBoard.bulletinCount(2));   // other markets are untouched
        List<BdxMessage> tOffers = ofType(BdxMessage.BULLETIN_OFFER);
        assertEquals(2, tOffers.size());            // to the owner and to the offering firm
        assertEquals("CANCELLED", str(tOffers.get(0), "event"));
        assertTrue(ofType(BdxMessage.BULLETIN).stream()
                .allMatch(m -> str(m, "event").equals("REMOVED") && str(m, "state").equals("DELETED")));
        assertEquals(2, ofType(BdxMessage.BULLETIN).size());
        assertEquals(0, mBoard.bulletinCount(1));
    }

    @Test
    void suspendedMarketOnlyAllowsWithdrawAndReject() {
        String tA = add("FA", "SELL", 100 * S, 10 * S);
        String tB = add("FA", "SELL", 100 * S, 10 * S);
        assertTrue(mBoard.makeOffer("FB", "u", tA, 99 * S, 1 * S, null, 0).success());
        mBoard.putMarket(market(1, "BB", "SUSPENDED", "1:00"));
        assertEquals("SUSPENDED", str(ofType(BdxMessage.MARKET_STATE).get(0), "tradingStatus"));

        assertEquals(400, mBoard.addBulletin("FA", "u", 1, "x", "BUY", 0, 0, null).status());
        assertEquals(400, mBoard.makeOffer("FB", "u", tB, 99 * S, 1 * S, null, 0).status());
        assertEquals(400, mBoard.answerOffer("FA", tA, true).status());
        assertTrue(mBoard.answerOffer("FA", tA, false).success());
        assertTrue(mBoard.withdrawBulletin("FA", tB).success());
    }
}
