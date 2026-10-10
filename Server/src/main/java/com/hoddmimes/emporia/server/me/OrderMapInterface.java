package com.hoddmimes.emporia.server.me;

import java.util.Iterator;
import java.util.List;

/** Ordered collection of orders on one side of the book. */
public interface OrderMapInterface {

    int           size();
    void          add(Order pOrder);
    Order         remove(long pOrderId);
    Iterator<Order> iterator();
    List<Order>   getOrders();
    Order         peek();
}
