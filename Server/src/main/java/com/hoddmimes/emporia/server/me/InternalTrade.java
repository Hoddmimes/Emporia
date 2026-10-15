package com.hoddmimes.emporia.server.me;

import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicLong;

/** Represents a single matched trade between a buy and a sell order. */
public class InternalTrade {

    private static final AtomicLong sTradeSeq = new AtomicLong(1);
    private static final SimpleDateFormat SDF  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private final long  mInstrumentId;
    private final long  mPrice;
    private final long  mQuantity;
    private final long  mTradeTime;
    private final long  mTradeNo;
    private final Order mBuyOrder;
    private final Order mSellOrder;

    public InternalTrade(long pInstrumentId, long pPrice, long pQuantity, Order pOrder1, Order pOrder2) {
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mTradeTime    = System.currentTimeMillis();
        mTradeNo      = sTradeSeq.getAndIncrement();
        mBuyOrder     = (pOrder1.getSide() == Order.Side.BUY)  ? pOrder1 : pOrder2;
        mSellOrder    = (pOrder1.getSide() == Order.Side.SELL) ? pOrder1 : pOrder2;
    }

    /**
     * A trade with known values, e.g. read back from the database; the trade number counter is not
     * used. Only the order ids, accounts, user refs and client accounts of the two orders are known, so the buy and
     * sell orders are stand-ins carrying the trade's price and quantity and the trade time as their
     * creation time, not the original orders' values.
     */
    public InternalTrade(long pTradeNo, long pTradeTime, long pInstrumentId, long pPrice, long pQuantity,
                         long pBuyOrderId,  String pBuyAccount,  String pBuyUserRef,  String pBuyClientAccount,
                         long pSellOrderId, String pSellAccount, String pSellUserRef, String pSellClientAccount) {
        mInstrumentId = pInstrumentId;
        mPrice        = pPrice;
        mQuantity     = pQuantity;
        mTradeTime    = pTradeTime;
        mTradeNo      = pTradeNo;
        mBuyOrder     = new Order(pBuyOrderId,  pInstrumentId, pPrice, pQuantity, Order.Side.BUY,
                                  pBuyAccount,  pBuyUserRef,  pBuyClientAccount,  pTradeTime);
        mSellOrder    = new Order(pSellOrderId, pInstrumentId, pPrice, pQuantity, Order.Side.SELL,
                                  pSellAccount, pSellUserRef, pSellClientAccount, pTradeTime);
    }

    public long  getInstrumentId() { return mInstrumentId; }
    public long  getPrice()        { return mPrice; }
    public long  getQuantity()     { return mQuantity; }
    public long  getTradeTime()    { return mTradeTime; }
    public long  getTradeNo()      { return mTradeNo; }
    public Order getBuyOrder()     { return mBuyOrder; }
    public Order getSellOrder()    { return mSellOrder; }

    @Override
    public String toString() {
        return "Trade inst:" + mInstrumentId
                + " " + mQuantity + "@" + mPrice
                + " t:" + SDF.format(mTradeTime)
                + " no:" + Long.toHexString(mTradeNo)
                + " buy:" + Long.toHexString(mBuyOrder.getOrderId())
                + " sell:" + Long.toHexString(mSellOrder.getOrderId());
    }
}
