package com.hoddmimes.emporia.server.me;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Orderbook for a single instrument.
 * Maintains buy and sell sides in price-time priority order. A CONTINUOUS book
 * matches incoming orders immediately, unless told to only collect them (market in
 * PREOPEN); an AUCTION book always collects orders. Collected orders are matched by
 * {@link #uncross()} at a single clearing price.
 */
public class Orderbook {

    private static final Logger cLog = LogManager.getLogger(Orderbook.class);

    public static final String TYPE_AUCTION = "AUCTION";

    private final long                     mInstrumentId;
    private final String                   mType;
    private final long                     mTickSize;
    private final long                     mLotSize;
    private final OrderMapInterface        mBuySide;
    private final OrderMapInterface        mSellSide;
    private final MatchingEngineCallbackIf mCallback;

    private long mLastTradePrice;
    private long mOpenPrice;
    private long mHighPrice;
    private long mTradedVolume;
    private long mSeqNo;
    private int  mOrderCount;
    private int  mExecutionCount;

    public Orderbook(long pInstrumentId, String pType, long pTickSize, long pLotSize, MatchingEngineCallbackIf pCallback) {
        mInstrumentId   = pInstrumentId;
        mType           = pType;
        mTickSize       = pTickSize;
        mLotSize        = pLotSize;
        mCallback       = pCallback;
        mBuySide        = new OrderMap(Order.Side.BUY);
        mSellSide       = new OrderMap(Order.Side.SELL);
        mLastTradePrice = 0L;
        mSeqNo          = 0L;
        mOrderCount     = 0;
        mExecutionCount = 0;
    }

    public String getType() { return mType; }

    // ── Add order ─────────────────────────────────────────────────────────

    /** Adds an order; with pMatch false (or in an AUCTION book) the order is only put in the book. */
    public OrderResponse addOrder(AddOrderRequest pReq, boolean pMatch) {
        String tErr = validateOrder(pReq.price(), pReq.quantity());
        if (tErr != null) return OrderResponse.error(tErr);

        if (!cLog.isTraceEnabled()) {
            cLog.info( pReq.toString());
        }

        Order tOrder = new Order(pReq);
        mOrderCount++;

        if (!pMatch || TYPE_AUCTION.equals(mType)) {
            addOrderToBook(tOrder);
            mCallback.orderAdded(tOrder, ++mSeqNo);
            mCallback.orderbookChanged(mInstrumentId);
            return new OrderResponse(true, null, Long.toHexString(tOrder.getOrderId()),
                    true, 0L, tOrder.getQuantity(), tOrder.getUserRef());
        }

        // Continuous: match immediately
        if (tOrder.getSide() == Order.Side.BUY) {
            return matchBook(mSellSide, tOrder);
        } else {
            return matchBook(mBuySide, tOrder);
        }
    }

    // ── Trigger auction ───────────────────────────────────────────────────

    public AuctionResult triggerAuction() {
        if (!TYPE_AUCTION.equals(mType)) {
            return AuctionResult.error("Not an auction orderbook");
        }
        return uncross();
    }

    /**
     * Matches all crossing orders at a single clearing price (any book type).
     * Used for scheduled auctions and to uncross a continuous book when its market opens.
     */
    public AuctionResult uncross() {
        List<Order> tBuys  = new ArrayList<>(mBuySide.getOrders());
        List<Order> tSells = new ArrayList<>(mSellSide.getOrders());

        long tClearingPrice = findClearingPrice(tBuys, tSells);
        long tTotalMatched  = 0L;

        if (tClearingPrice > 0L) {
            tTotalMatched = executeAtClearingPrice(tClearingPrice, tBuys, tSells);
        }

        mCallback.orderbookChanged(mInstrumentId);

        return new AuctionResult(true, null, mInstrumentId, tTotalMatched, tClearingPrice);
    }

    /**
     * Scans all candidate prices to find the one that maximises matched volume.
     * Tie-breaking: 1. Minimum imbalance, 2. Closest to last trade price.
     * Returns 0 if there is no price overlap.
     */
    private long findClearingPrice(List<Order> pBuys, List<Order> pSells) {
        if (pBuys.isEmpty() || pSells.isEmpty()) return 0L;

        long tBestBid = pBuys.get(0).getPrice();
        long tBestAsk = pSells.get(0).getPrice();
        if (tBestBid < tBestAsk) return 0L;

        TreeSet<Long> tCandidates = new TreeSet<>();
        for (Order o : pBuys)  { if (o.getPrice() >= tBestAsk) tCandidates.add(o.getPrice()); }
        for (Order o : pSells) { if (o.getPrice() <= tBestBid) tCandidates.add(o.getPrice()); }

        long tMaxVolume     = 0L;
        long tMinImbalance  = Long.MAX_VALUE;
        long tClearingPrice = 0L;

        for (long tPrice : tCandidates) {
            long tBuyVol = 0L;
            for (Order o : pBuys)  { if (o.getPrice() >= tPrice) tBuyVol  += o.getQuantity(); }
            long tSellVol = 0L;
            for (Order o : pSells) { if (o.getPrice() <= tPrice) tSellVol += o.getQuantity(); }

            long tMatchable = Math.min(tBuyVol, tSellVol);
            long tImbalance = Math.abs(tBuyVol - tSellVol);

            boolean tBetter =
                    tMatchable > tMaxVolume
                    || (tMatchable == tMaxVolume && tImbalance < tMinImbalance)
                    || (tMatchable == tMaxVolume && tImbalance == tMinImbalance
                            && mLastTradePrice > 0
                            && Math.abs(tPrice - mLastTradePrice)
                               < Math.abs(tClearingPrice - mLastTradePrice));

            if (tBetter) {
                tMaxVolume     = tMatchable;
                tMinImbalance  = tImbalance;
                tClearingPrice = tPrice;
            }
        }
        return tClearingPrice;
    }

    private long executeAtClearingPrice(long pClearingPrice, List<Order> pAllBuys, List<Order> pAllSells) {
        List<Order> tBuys  = new ArrayList<>();
        List<Order> tSells = new ArrayList<>();
        for (Order o : pAllBuys)  { if (o.getPrice() >= pClearingPrice) tBuys.add(o); }
        for (Order o : pAllSells) { if (o.getPrice() <= pClearingPrice) tSells.add(o); }

        Map<Long, Long> tOrigQty = new HashMap<>();
        for (Order o : tBuys)  tOrigQty.put(o.getOrderId(), o.getQuantity());
        for (Order o : tSells) tOrigQty.put(o.getOrderId(), o.getQuantity());

        for (Order o : tBuys)  mBuySide.remove(o.getOrderId());
        for (Order o : tSells) mSellSide.remove(o.getOrderId());

        long tTotalMatched = 0L;
        int  tBuyIdx  = 0;
        int  tSellIdx = 0;

        while (tBuyIdx < tBuys.size() && tSellIdx < tSells.size()) {
            Order tBuy  = tBuys.get(tBuyIdx);
            Order tSell = tSells.get(tSellIdx);

            long tQty = Math.min(tBuy.getQuantity(), tSell.getQuantity());
            recordTrade(pClearingPrice, tQty);
            mCallback.trade(new InternalTrade(mInstrumentId, pClearingPrice, tQty, tBuy, tSell));

            tBuy.setQuantity(tBuy.getQuantity()   - tQty);
            tSell.setQuantity(tSell.getQuantity() - tQty);
            tTotalMatched += tQty;

            if (tBuy.getQuantity()  == 0) { mCallback.orderRemoved(tBuy,  ++mSeqNo); tBuyIdx++;  }
            if (tSell.getQuantity() == 0) { mCallback.orderRemoved(tSell, ++mSeqNo); tSellIdx++; }
        }

        for (; tBuyIdx  < tBuys.size();  tBuyIdx++)  reinsert(tBuys.get(tBuyIdx),   tOrigQty);
        for (; tSellIdx < tSells.size(); tSellIdx++) reinsert(tSells.get(tSellIdx), tOrigQty);

        return tTotalMatched;
    }

    private void reinsert(Order pOrder, Map<Long, Long> pOrigQty) {
        addOrderToBook(pOrder);
        if (pOrder.getQuantity() < pOrigQty.get(pOrder.getOrderId())) {
            mCallback.orderModified(pOrder, ++mSeqNo);
        }
    }

    // ── Modify order ──────────────────────────────────────────────────────

    /**
     * Modifies a resting order. A clean modify (quantity not increased and price not
     * worsened) keeps the order in place; otherwise the order is replaced by a new one,
     * losing its time priority and possibly matching (pMatch as for addOrder). pPrice 0 keeps the
     * current price and a null pUserRef keeps the current userRef, in both cases.
     */
    public OrderResponse modifyOrder(long pOrderId, long pPrice, long pQuantity, String pUserRef, boolean pMatch) {
        Order tExisting = getOrder(pOrderId);
        if (tExisting == null) {
            return OrderResponse.error("Order not found: " + Long.toHexString(pOrderId));
        }

        long   tNewPrice   = pPrice > 0 ? pPrice : tExisting.getPrice();
        String tNewUserRef = pUserRef != null ? pUserRef : tExisting.getUserRef();   // kept unless a new one is given
        String tErr = validateOrder(tNewPrice, pQuantity);
        if (tErr != null) return OrderResponse.error(tErr);

        boolean tClean = (pQuantity <= tExisting.getQuantity()) && !priceWorsened(tExisting, tNewPrice);

        if (tClean) {
            tExisting.setQuantity(pQuantity);
            tExisting.setUserRef(tNewUserRef);
            if (tExisting.getPrice() != tNewPrice) {
                tExisting.setPrice(tNewPrice);
                reshuffleOrder(tExisting);
            }
            mCallback.orderModified(tExisting, ++mSeqNo);
            mCallback.orderbookChanged(mInstrumentId);
            return new OrderResponse(true, null, Long.toHexString(tExisting.getOrderId()),
                    true, 0L, tExisting.getQuantity(), tExisting.getUserRef());
        }

        // Full modify: delete existing, add new
        removeOrderFromBook(pOrderId);
        mCallback.orderRemoved(tExisting, ++mSeqNo);
        mCallback.orderbookChanged(mInstrumentId);

        return addOrder(new AddOrderRequest(mInstrumentId, tExisting.getSide(), tNewPrice, pQuantity,
                tExisting.getAccount(), tNewUserRef), pMatch);
    }

    // ── Delete order ──────────────────────────────────────────────────────

    public OrderResponse deleteOrder(long pOrderId) {
        if (!cLog.isTraceEnabled()) {
            cLog.info("DeleteOrder orderid: " + Long.toHexString(pOrderId));
        }
        Order tRemoved = removeOrderFromBook(pOrderId);
        if (tRemoved == null) {
            if (!cLog.isTraceEnabled()) {
                cLog.info("DeleteOrder [failed] orderid: " + Long.toHexString(pOrderId) + " was not found");
            }
            return OrderResponse.error("Order not found: " + Long.toHexString(pOrderId));
        }
        mCallback.orderRemoved(tRemoved, ++mSeqNo);
        mCallback.orderbookChanged(mInstrumentId);
        if (!cLog.isTraceEnabled()) {
            cLog.info("DeleteOrder [success] orderid: " + Long.toHexString(pOrderId) + " deleted");
        }
        return new OrderResponse(true, null, Long.toHexString(tRemoved.getOrderId()),
                false, 0L, tRemoved.getQuantity(), tRemoved.getUserRef());
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public List<Order> getOrdersForAccount(String pAccount) {
        List<Order> tResult = new ArrayList<>();
        for (Iterator<Order> it = mBuySide.iterator(); it.hasNext(); ) {
            Order o = it.next();
            if (o.getAccount().equalsIgnoreCase(pAccount)) tResult.add(o);
        }
        for (Iterator<Order> it = mSellSide.iterator(); it.hasNext(); ) {
            Order o = it.next();
            if (o.getAccount().equalsIgnoreCase(pAccount)) tResult.add(o);
        }
        return tResult;
    }

    public Order getOrder(long pOrderId) {
        Iterator<Order> tItr = OrderId.isBuyOrder(pOrderId) ? mBuySide.iterator() : mSellSide.iterator();
        while (tItr.hasNext()) {
            Order o = tItr.next();
            if (o.getOrderId() == pOrderId) return o;
        }
        return null;
    }

    /** Total quantity at the best price on the given side (0 if the side is empty). */
    public long getBestPriceVolume(Order.Side pSide) {
        OrderMapInterface tSide = (pSide == Order.Side.BUY) ? mBuySide : mSellSide;
        Order tBest = tSide.peek();
        if (tBest == null) return 0L;
        long tVolume = 0L;
        for (Order o : tSide.getOrders()) {
            if (o.getPrice() != tBest.getPrice()) break;
            tVolume += o.getQuantity();
        }
        return tVolume;
    }

    public long              getInstrumentId()    { return mInstrumentId; }
    public int               getBuySideDepth()    { return mBuySide.size(); }
    public int               getSellSideDepth()   { return mSellSide.size(); }
    public long              getLastTradePrice()  { return mLastTradePrice; }
    public long              getOpenPrice()       { return mOpenPrice; }
    public long              getHighPrice()       { return mHighPrice; }
    public long              getTradedVolume()    { return mTradedVolume; }
    public OrderMapInterface getBuySide()         { return mBuySide; }
    public OrderMapInterface getSellSide()        { return mSellSide; }

    // ── Internals ─────────────────────────────────────────────────────────

    private OrderResponse matchBook(OrderMapInterface pBook, Order pNewOrder) {
        long    tTotMatched = 0;
        boolean tEndOfMatch = false;

        Iterator<Order> tItr = pBook.iterator();
        while (tItr.hasNext() && pNewOrder.getQuantity() > 0 && !tEndOfMatch) {
            Order tBookOrder = tItr.next();
            if (pNewOrder.match(tBookOrder)) {
                long tMatchedQty = Math.min(tBookOrder.getQuantity(), pNewOrder.getQuantity());

                recordTrade(tBookOrder.getPrice(), tMatchedQty);
                mCallback.trade(new InternalTrade(mInstrumentId, tBookOrder.getPrice(),
                        tMatchedQty, pNewOrder, tBookOrder));

                tBookOrder.setQuantity(tBookOrder.getQuantity() - tMatchedQty);
                pNewOrder.setQuantity(pNewOrder.getQuantity() - tMatchedQty);
                tTotMatched += tMatchedQty;

                if (tBookOrder.getQuantity() == 0) {
                    mCallback.orderRemoved(tBookOrder, ++mSeqNo);
                    tItr.remove();
                } else {
                    mCallback.orderModified(tBookOrder, ++mSeqNo);   // partially filled, rests with less quantity
                }
            } else {
                tEndOfMatch = true;
            }
        }

        if (pNewOrder.getQuantity() > 0) {
            addOrderToBook(pNewOrder);
            mCallback.orderAdded(pNewOrder, ++mSeqNo);
        } else {
            mCallback.newOrderMatched(pNewOrder, mSeqNo);
        }

        mCallback.orderbookChanged(mInstrumentId);

        return new OrderResponse(true, null, Long.toHexString(pNewOrder.getOrderId()),
                pNewOrder.getQuantity() > 0, tTotMatched, pNewOrder.getQuantity(), pNewOrder.getUserRef());
    }

    private void recordTrade(long pPrice, long pQuantity) {
        if (mOpenPrice == 0L) mOpenPrice = pPrice;
        mHighPrice      = Math.max(mHighPrice, pPrice);
        mLastTradePrice = pPrice;
        mTradedVolume  += pQuantity;
        mExecutionCount++;
    }

    private void addOrderToBook(Order pOrder) {
        if (pOrder.getSide() == Order.Side.BUY) {
            mBuySide.add(pOrder);
        } else {
            mSellSide.add(pOrder);
        }
    }

    private Order removeOrderFromBook(long pOrderId) {
        return OrderId.isBuyOrder(pOrderId) ? mBuySide.remove(pOrderId) : mSellSide.remove(pOrderId);
    }

    private void reshuffleOrder(Order pOrder) {
        Order tOrder = removeOrderFromBook(pOrder.getOrderId());
        addOrderToBook(tOrder);
    }

    private boolean priceWorsened(Order pExisting, long pNewPrice) {
        if (pExisting.getSide() == Order.Side.BUY  && pNewPrice > pExisting.getPrice()) return true;
        if (pExisting.getSide() == Order.Side.SELL && pNewPrice < pExisting.getPrice()) return true;
        return false;
    }

    private String validateOrder(long pPrice, long pQuantity) {
        if (pPrice <= 0)    return "Price must be greater than zero";
        if (pQuantity <= 0) return "Quantity must be greater than zero";
        if (mTickSize > 0 && pPrice % mTickSize != 0)
            return "Price " + pPrice + " is not a multiple of tick size " + mTickSize;
        if (mLotSize > 0 && pQuantity % mLotSize != 0)
            return "Quantity " + pQuantity + " is not a multiple of lot size " + mLotSize;
        return null;
    }
}
