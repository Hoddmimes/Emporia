package com.hoddmimes.emporia.server.me;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AddOrderRequestTest {

    @Test
    void toStringShowsMarketAndSequenceAndDisplayUnits() {
        long tInstrumentId = (1L << 32) | 1;
        assertEquals("AddOrderRequest[instrument=1/1, side=BUY, price=105.5, quantity=10, account=FirmA, userRef=a1]",
                new AddOrderRequest(tInstrumentId, Order.Side.BUY, 10550000L, 1000000L, "FirmA", "a1").toString());
        assertEquals("AddOrderRequest[instrument=3/12, side=SELL, price=0.00001, quantity=1500, account=FirmB, userRef=null]",
                new AddOrderRequest((3L << 32) | 12, Order.Side.SELL, 1L, 150000000L, "FirmB", null).toString());
    }
}
