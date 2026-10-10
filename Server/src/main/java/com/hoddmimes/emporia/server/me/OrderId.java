package com.hoddmimes.emporia.server.me;

/**
 * Generates unique order IDs.
 *
 * ID layout (64 bits):
 *   - Upper 32 bits : epoch seconds at generation time
 *   - Lower 32 bits : sequence number (incremented per ID)
 *   - LSB (bit 0)   : 1 = BUY side, 0 = SELL side
 */
public class OrderId {

    private static long mTime  = ((System.currentTimeMillis() / 1000L) << 32);
    private static long mSeqNo = 1;

    public static synchronized long get(Order.Side pSide) {
        if (mSeqNo >= 0x7fffff00L) {
            mTime  = ((System.currentTimeMillis() / 1000L) << 32);
            mSeqNo = 1;
        }
        if (pSide == Order.Side.BUY) {
            if ((mSeqNo & 1) == 0) { mSeqNo++; }
        } else {
            if ((mSeqNo & 1) == 1) { mSeqNo++; }
        }
        return mTime + mSeqNo++;
    }

    public static boolean isBuyOrder(long pOrderId) {
        return (pOrderId & 1) == 1;
    }
}
