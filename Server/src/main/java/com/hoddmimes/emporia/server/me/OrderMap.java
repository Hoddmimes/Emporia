package com.hoddmimes.emporia.server.me;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * Price-time priority ordered list for one side of the book.
 * BUY side: highest price first; SELL side: lowest price first.
 * Ties broken by creation time (earliest first).
 */
public class OrderMap implements OrderMapInterface {

    private final Order.Side        mSide;
    private final LinkedList<Order> mOrders;

    public OrderMap(Order.Side pSide) {
        mSide   = pSide;
        mOrders = new LinkedList<>();
    }

    @Override
    public int size() {
        return mOrders.size();
    }

    @Override
    public void add(Order pOrder) {
        if (mOrders.isEmpty()) {
            mOrders.add(pOrder);
            return;
        }
        ListIterator<Order> tItr = mOrders.listIterator();
        while (tItr.hasNext()) {
            Order o = tItr.next();
            if (o.compareTo(pOrder) > 0) {
                tItr.previous();
                tItr.add(pOrder);
                return;
            }
        }
        mOrders.addLast(pOrder);
    }

    @Override
    public Order remove(long pOrderId) {
        ListIterator<Order> tItr = mOrders.listIterator();
        while (tItr.hasNext()) {
            Order o = tItr.next();
            if (o.getOrderId() == pOrderId) {
                tItr.remove();
                return o;
            }
        }
        return null;
    }

    @Override
    public Iterator<Order> iterator() {
        return mOrders.iterator();
    }

    @Override
    public List<Order> getOrders() {
        return mOrders;
    }

    @Override
    public Order peek() {
        return mOrders.isEmpty() ? null : mOrders.getFirst();
    }
}
