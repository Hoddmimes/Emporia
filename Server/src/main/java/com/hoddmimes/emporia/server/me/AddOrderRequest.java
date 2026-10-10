package com.hoddmimes.emporia.server.me;

/**
 * A new order for the matching engine. Price and quantity are longs scaled by
 * {@link MatchingEngine#SCALE}; the account is the firm that owns the order.
 */
public record AddOrderRequest(long instrumentId, Order.Side side, long price, long quantity,
                              String account, String userRef) {}
