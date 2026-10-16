package com.hoddmimes.emporia.server.db;

/**
 * A bulletin as stored (collection/table Bulletins), without its pending offer (see BulletinBoard).
 * Prices and quantities are scaled longs, 0 when not given; side is BUY, SELL or null; times are epoch
 * millis.
 */
public record StoredBulletin(String bulletinId, int marketId, String instrument, String side,
                             long price, long quantity, long remainingQuantity,
                             String ownerFirm, String ownerUser, String userRef, String clientAccount,
                             long createTime, long expiresAt) {
}
