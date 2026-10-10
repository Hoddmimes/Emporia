package com.hoddmimes.emporia.server.me;

/** Result of an auction uncrossing; clearingPrice is 0 when the book did not cross. */
public record AuctionResult(boolean success, String message, long instrumentId,
                            long matchedVolume, long clearingPrice) {

    public static AuctionResult error(String pMessage) {
        return new AuctionResult(false, pMessage, 0L, 0L, 0L);
    }
}
