package com.hoddmimes.emporia.server.db;

/**
 * A trade on a bulletin (collection/table BulletinTrades, kept apart from Trades, whose trades are
 * replayed into the order books at startup): the free text instrument, price and quantity (scaled), the
 * trade time in epoch millis, and per side the firm (account), user, reference (the bulletin's userRef or
 * the offer's offerRef) and client account. reversalOf is the tradeNo of the bulletin trade a reversal made by
 * the market supervision reverses, null for a normal trade.
 */
public record BulletinTrade(String tradeNo, String bulletinId, int marketId, String instrument,
                            long price, long quantity, long tradeTime,
                            String buyAccount,  String buyUser,  String buyRef,  String buyClientAccount,
                            String sellAccount, String sellUser, String sellRef, String sellClientAccount,
                            String reversalOf) {

    /** A normal bulletin trade (no reversal). */
    public BulletinTrade(String tradeNo, String bulletinId, int marketId, String instrument,
                         long price, long quantity, long tradeTime,
                         String buyAccount,  String buyUser,  String buyRef,  String buyClientAccount,
                         String sellAccount, String sellUser, String sellRef, String sellClientAccount) {
        this(tradeNo, bulletinId, marketId, instrument, price, quantity, tradeTime,
             buyAccount, buyUser, buyRef, buyClientAccount, sellAccount, sellUser, sellRef, sellClientAccount, null);
    }

    public boolean isReversal() { return reversalOf != null; }

    /**
     * The trade reversing this one (new tradeNo and time): same bulletin, instrument, price and quantity, the
     * seller buying back from the buyer, each side with its user, reference and client account.
     */
    public BulletinTrade reversal(String pTradeNo, long pTradeTime) {
        return new BulletinTrade(pTradeNo, bulletinId, marketId, instrument, price, quantity, pTradeTime,
                sellAccount, sellUser, sellRef, sellClientAccount,
                buyAccount,  buyUser,  buyRef,  buyClientAccount, tradeNo);
    }
}
