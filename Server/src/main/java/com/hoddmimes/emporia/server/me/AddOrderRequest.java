package com.hoddmimes.emporia.server.me;

import java.math.BigDecimal;

/**
 * A new order for the matching engine. Price and quantity are longs scaled by
 * {@link MatchingEngine#SCALE}; the account is the firm that owns the order. The optional
 * clientAccount (free text) is the firm's own account for the order, carried on to its trades, so
 * the firm's positions can be kept per client account; an order without one gets
 * {@link #DEFAULT_CLIENT_ACCOUNT}.
 */
public record AddOrderRequest(long instrumentId, Order.Side side, long price, long quantity,
                              String account, String userRef, String clientAccount) {

    /** The client account of an order entered without one (and of trades stored before client accounts). */
    public static final String DEFAULT_CLIENT_ACCOUNT = "omni";

    public AddOrderRequest {
        if (clientAccount == null) clientAccount = DEFAULT_CLIENT_ACCOUNT;
    }

    /** An order without a client account: it gets the default one. */
    public AddOrderRequest(long instrumentId, Order.Side side, long price, long quantity,
                           String account, String userRef) {
        this(instrumentId, side, price, quantity, account, userRef, null);
    }

    /**
     * A readable form for logging: the instrument as marketId/sequence (its id's high and low
     * 32 bits) and price and quantity in display units, e.g.
     * AddOrderRequest[instrument=1/1, side=BUY, price=105.5, quantity=10, account=FirmA, userRef=a1]
     */
    @Override
    public String toString() {
        return "AddOrderRequest[instrument=" + (instrumentId >>> 32) + "/" + (instrumentId & 0xffffffffL)
                + ", side="     + side
                + ", price="    + unscaled(price)
                + ", quantity=" + unscaled(quantity)
                + ", account="  + account
                + ", userRef="  + userRef
                + (DEFAULT_CLIENT_ACCOUNT.equals(clientAccount) ? "" : ", clientAccount=" + clientAccount) + "]";
    }

    // Exact decimal form of a scaled value, without trailing zeros (10550000 -> 105.5, 1000000 -> 10)
    private static String unscaled(long pValue) {
        return BigDecimal.valueOf(pValue).divide(BigDecimal.valueOf(MatchingEngine.SCALE))
                .stripTrailingZeros().toPlainString();
    }
}
