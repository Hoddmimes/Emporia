package com.hoddmimes.emporia.server.me;

import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single matched trade between a buy and a sell order, or a reversal trade made by the
 * market supervision (reversalOf, see reversal).
 */
public class InternalTrade {

    // Unique across server restarts like the order ids: the start time in seconds in the high 32 bits
    private static final AtomicLong sTradeSeq = new AtomicLong(((System.currentTimeMillis() / 1000L) << 32) + 1);
    private static final SimpleDateFormat SDF  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private final long   mInstrumentId;
    private final long   mPrice;
    private final long   mQuantity;
    private final long   mTradeTime;
    private final long   mTradeNo;
    private final Order  mBuyOrder;
    private final Order  mSellOrder;
    private final String mReversalOf;   // the tradeNo (hex) of the trade this one reverses, null for a normal trade

    public InternalTrade(long pInstrumentId, long pPrice, long pQuantity, Order pOrder1, Order pOrder2) {
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mTradeTime    = System.currentTimeMillis();
        mTradeNo      = sTradeSeq.getAndIncrement();
        mBuyOrder     = (pOrder1.getSide() == Order.Side.BUY)  ? pOrder1 : pOrder2;
        mSellOrder    = (pOrder1.getSide() == Order.Side.SELL) ? pOrder1 : pOrder2;
        mReversalOf   = null;
    }

    /**
     * A trade with known values, e.g. read back from the database; the trade number counter is not
     * used. Only the order ids, accounts, user refs, client accounts and users of the two orders are known,
     * so the buy and sell orders are stand-ins carrying the trade's price and quantity and the trade time as
     * their creation time, not the original orders' values.
     */
    public InternalTrade(long pTradeNo, long pTradeTime, long pInstrumentId, long pPrice, long pQuantity,
                         long pBuyOrderId,  String pBuyAccount,  String pBuyUserRef,  String pBuyClientAccount,  String pBuyUser,
                         long pSellOrderId, String pSellAccount, String pSellUserRef, String pSellClientAccount, String pSellUser,
                         String pReversalOf) {
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mTradeTime    = pTradeTime;
        mTradeNo      = pTradeNo;
        mBuyOrder     = new Order(pBuyOrderId,  pInstrumentId, pPrice, pQuantity, Order.Side.BUY,
                                  pBuyAccount,  pBuyUserRef,  pBuyClientAccount,  pBuyUser,  pTradeTime);
        mSellOrder    = new Order(pSellOrderId, pInstrumentId, pPrice, pQuantity, Order.Side.SELL,
                                  pSellAccount, pSellUserRef, pSellClientAccount, pSellUser, pTradeTime);
        mReversalOf   = pReversalOf;
    }

    /** As above, without users, not a reversal. */
    public InternalTrade(long pTradeNo, long pTradeTime, long pInstrumentId, long pPrice, long pQuantity,
                         long pBuyOrderId,  String pBuyAccount,  String pBuyUserRef,  String pBuyClientAccount,
                         long pSellOrderId, String pSellAccount, String pSellUserRef, String pSellClientAccount) {
        this(pTradeNo, pTradeTime, pInstrumentId, pPrice, pQuantity,
             pBuyOrderId, pBuyAccount, pBuyUserRef, pBuyClientAccount, null,
             pSellOrderId, pSellAccount, pSellUserRef, pSellClientAccount, null, null);
    }

    /**
     * A new trade (new trade number, made now) reversing pOriginal: the same instrument, price and quantity
     * with the roles changed, the original seller buying and the original buyer selling, each side with its
     * original order id, account, user ref, client account and user, so the firms' positions per client
     * account are restored.
     */
    public static InternalTrade reversal(InternalTrade pOriginal) {
        Order tBuy  = pOriginal.getSellOrder();   // the seller buys back
        Order tSell = pOriginal.getBuyOrder();
        return new InternalTrade(sTradeSeq.getAndIncrement(), System.currentTimeMillis(), pOriginal.getInstrumentId(),
                pOriginal.getPrice(), pOriginal.getQuantity(),
                tBuy.getOrderId(),  tBuy.getAccount(),  tBuy.getUserRef(),  tBuy.getClientAccount(),  tBuy.getUser(),
                tSell.getOrderId(), tSell.getAccount(), tSell.getUserRef(), tSell.getClientAccount(), tSell.getUser(),
                Long.toHexString(pOriginal.getTradeNo()));
    }

    public long   getInstrumentId() { return mInstrumentId; }
    public long   getPrice()        { return mPrice; }
    public long   getQuantity()     { return mQuantity; }
    public long   getTradeTime()    { return mTradeTime; }
    public long   getTradeNo()      { return mTradeNo; }
    public Order  getBuyOrder()     { return mBuyOrder; }
    public Order  getSellOrder()    { return mSellOrder; }
    /** The tradeNo (hex) of the trade this one reverses, null if it is no reversal. */
    public String getReversalOf()   { return mReversalOf; }
    public boolean isReversal()     { return mReversalOf != null; }

    @Override
    public String toString() {
        return "Trade inst:" + mInstrumentId
                + " " + mQuantity + "@" + mPrice
                + " t:" + SDF.format(mTradeTime)
                + " no:" + Long.toHexString(mTradeNo)
                + " buy:" + Long.toHexString(mBuyOrder.getOrderId()) + "/" + mBuyOrder.getAccount()
                + " sell:" + Long.toHexString(mSellOrder.getOrderId()) + "/" + mSellOrder.getAccount()
                + ((mReversalOf != null) ? " reversalOf:" + mReversalOf : "");
    }
}
