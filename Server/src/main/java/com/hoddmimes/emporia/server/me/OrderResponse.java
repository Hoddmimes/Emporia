package com.hoddmimes.emporia.server.me;

/**
 * Result of an add, modify or delete order request.
 *
 * @param orderId   hex order id
 * @param inserted  true if (a remainder of) the order rests in the book
 * @param matched   quantity matched by the request
 * @param remaining quantity left in the book (delete: the quantity that was removed)
 * @param selfTradeCancelled quantity of the incoming order cancelled because it would have traded with an order
 *                  of its own firm, in a market that doesn't allow self-trade (0 otherwise)
 */
public record OrderResponse(boolean success, String message, String orderId,
                            boolean inserted, long matched, long remaining, String userRef, long selfTradeCancelled) {

    /** Without a self-trade cancellation. */
    public OrderResponse(boolean success, String message, String orderId,
                         boolean inserted, long matched, long remaining, String userRef) {
        this(success, message, orderId, inserted, matched, remaining, userRef, 0L);
    }

    public static OrderResponse error(String pMessage) {
        return new OrderResponse(false, pMessage, null, false, 0L, 0L, null);
    }
}
