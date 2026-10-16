package com.hoddmimes.emporia.server.db;

/**
 * A trade on a bulletin (collection/table BulletinTrades, kept apart from Trades, whose trades are
 * replayed into the order books at startup): the free text instrument, price and quantity (scaled), the
 * trade time in epoch millis, and per side the firm (account), user, reference (the bulletin's userRef or
 * the offer's offerRef) and client account.
 */
public record BulletinTrade(String tradeNo, String bulletinId, int marketId, String instrument,
                            long price, long quantity, long tradeTime,
                            String buyAccount,  String buyUser,  String buyRef,  String buyClientAccount,
                            String sellAccount, String sellUser, String sellRef, String sellClientAccount) {
}
