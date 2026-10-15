package com.hoddmimes.emporia.server.me;

import java.text.SimpleDateFormat;

/**
 * Represents a single order in the book.
 *
 * Price and quantity are stored as longs scaled by 100000
 * (e.g. a price of 10.50 is stored as 1050000).
 *
 * Ordering: BUY orders sorted highest price first; SELL orders sorted
 * lowest price first; ties broken by creation time (earliest first).
 */
public class Order implements Comparable<Order> {

    public enum Side         { BUY, SELL }
    public enum ChangeAction { ADD, REMOVE, MODIFY }

    private long   mInstrumentId;
    private long   mPrice;
    private Side   mSide;
    private long   mQuantity;
    private long   mCreationTime;
    private String mUserRef;
    private long   mOrderId;
    private String mAccount;
    private String mClientAccount;   // the firm's own account for the order (AddOrderRequest.DEFAULT_CLIENT_ACCOUNT if not given)

    /** Construct from an incoming order request. */
    public Order(AddOrderRequest pReq) {
        mInstrumentId = pReq.instrumentId();
        mPrice        = pReq.price();
        mQuantity     = pReq.quantity();
        mSide         = pReq.side();
        mUserRef      = pReq.userRef();
        mAccount      = pReq.account();
        mClientAccount = pReq.clientAccount();
        mCreationTime = System.currentTimeMillis();
        mOrderId      = OrderId.get(mSide);
    }

    /** Used internally (e.g. re-inserting during modify). */
    Order(long pInstrumentId, long pPrice, long pQuantity, Side pSide,
          String pAccount, String pUserRef, long pCreationTime) {
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mSide         = pSide;
        mAccount      = pAccount;
        mUserRef      = pUserRef;
        mCreationTime = pCreationTime;
        mOrderId      = OrderId.get(mSide);
    }

    /** An order with a known order id, e.g. one side of a trade read back from the database. */
    Order(long pOrderId, long pInstrumentId, long pPrice, long pQuantity, Side pSide,
          String pAccount, String pUserRef, String pClientAccount, long pCreationTime) {
        mOrderId      = pOrderId;
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mSide         = pSide;
        mAccount      = pAccount;
        mUserRef      = pUserRef;
        mClientAccount = pClientAccount;
        mCreationTime = pCreationTime;
    }

    // ── Comparable ────────────────────────────────────────────────────────

    @Override
    public int compareTo(Order pOther) {
        if (mSide == Side.BUY) {
            if (mPrice < pOther.mPrice) return  1;
            if (mPrice > pOther.mPrice) return -1;
        } else {
            if (mPrice > pOther.mPrice) return  1;
            if (mPrice < pOther.mPrice) return -1;
        }
        if (mCreationTime > pOther.mCreationTime) return  1;
        if (mCreationTime < pOther.mCreationTime) return -1;
        return 0;
    }

    /**
     * Returns true if this order matches against the given book order.
     * Called on the incoming order; pBookOrder is from the opposite side.
     */
    public boolean match(Order pBookOrder) {
        if (mQuantity == 0 || pBookOrder.mQuantity == 0) return false;
        if (mSide == Side.BUY  && mPrice >= pBookOrder.mPrice) return true;
        if (mSide == Side.SELL && mPrice <= pBookOrder.mPrice) return true;
        return false;
    }

    // ── Getters / setters ─────────────────────────────────────────────────

    public long   getInstrumentId() { return mInstrumentId; }
    public long   getPrice()        { return mPrice; }
    public Side   getSide()         { return mSide; }
    public long   getQuantity()     { return mQuantity; }
    public long   getCreationTime() { return mCreationTime; }
    public String getUserRef()      { return mUserRef; }
    public long   getOrderId()      { return mOrderId; }
    public String getAccount()      { return mAccount; }
    public String getClientAccount() { return mClientAccount; }

    public void setQuantity(long pQuantity) { mQuantity = pQuantity; }
    public void setPrice(long pPrice)       { mPrice = pPrice; }
    public void setUserRef(String pUserRef) { mUserRef = pUserRef; }
    void        setOrderId(long pOrderId)   { mOrderId = pOrderId; }

    @Override
    public String toString() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        return "inst:" + mInstrumentId + " " + mQuantity + "@" + mPrice
                + " " + mSide + " acc:" + mAccount
                + " client:" + mClientAccount
                + " ref:" + mUserRef
                + " t:" + sdf.format(mCreationTime)
                + " id:" + Long.toHexString(mOrderId);
    }
}
