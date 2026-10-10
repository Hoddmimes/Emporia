package com.hoddmimes.emporia.server.me;

/** Callback interface for Orderbook events, implemented by MatchingEngine. */
public interface MatchingEngineCallbackIf {

    /** A new order was inserted into the book (unmatched remainder). */
    void orderAdded(Order pOrder, long pSeqNo);

    /** An order was fully removed from the book (deleted or fully matched). */
    void orderRemoved(Order pOrder, long pSeqNo);

    /** An existing order was modified in place (clean modify). */
    void orderModified(Order pOrder, long pSeqNo);

    /** A trade was executed between two orders. */
    void trade(InternalTrade pTrade);

    /** A new order was fully matched and will not rest in the book. */
    void newOrderMatched(Order pOrder, long pSeqNo);

    /** The orderbook for the given instrument has changed. */
    void orderbookChanged(long pInstrumentId);
}
