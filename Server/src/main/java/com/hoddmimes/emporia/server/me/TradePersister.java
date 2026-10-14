package com.hoddmimes.emporia.server.me;

import com.hoddmimes.emporia.server.bdx.BdxMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The matching engine's output: its broadcast (bdx) messages and trades, in the order the engine
 * produced them. No trade is lost on a host failure and nobody hears of a trade before it is on disk:
 *
 * - The engine queues every message and trade here (under its lock) instead of publishing or
 *   storing them itself, so matching never waits for the disk.
 * - The persister thread takes everything queued so far as one batch, stores the batch's trades
 *   with one durable write (the store function, Database.saveTrades: journaled, i.e. fsynced, so one
 *   fsync covers all trades of the batch: group commit), and only then publishes the batch's
 *   messages, in order. Messages never overtake each other, so a book's events keep their seqNo order;
 *   a message queued behind a trade waits for that trade's write.
 * - A caller whose request traded (add or modify order, auction) waits with awaitReleased until its
 *   trades are stored and its messages published before it answers (MatchingEngine.durable).
 *
 * A failed write is logged and retried every second until it succeeds; until then nothing more is
 * published and the callers wait. Without a store (unit tests) there is no thread: messages are
 * published at once and trades are not stored.
 */
class TradePersister extends Thread {

    private static final Logger cLog = LogManager.getLogger(TradePersister.class);

    private static final int    MAX_BATCH     = 10000;
    private static final long   RETRY_MS      = 1000;
    private static final Object STOP          = new Object();   // queued by stopPersisting

    private final Consumer<List<InternalTrade>> mStore;       // null: no thread, publish at once
    private final Consumer<BdxMessage>          mPublisher;
    private final BlockingQueue<Object>         mQueue = new LinkedBlockingQueue<>();   // BdxMessage, InternalTrade or STOP

    private final Object mQueueLock = new Object();
    private long mQueued;       // items queued, under mQueueLock
    private long mTradesQueued; // trades queued, under mQueueLock
    private long mReleased;     // items stored/published, under this

    TradePersister(Consumer<List<InternalTrade>> pStore, Consumer<BdxMessage> pPublisher) {
        super("TradePersister");
        setDaemon(true);
        mStore     = pStore;
        mPublisher = pPublisher;
    }

    /** Starts the thread; does nothing without a store. */
    void startPersisting() {
        if (mStore != null) start();
    }

    /**
     * Stores and publishes what is queued, then ends the thread; waits at most pTimeoutMs (a write
     * retried because the database is down could wait forever).
     */
    void stopPersisting(long pTimeoutMs) {
        if (mStore == null || !isAlive()) return;
        mQueue.add(STOP);
        try {
            join(pTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (isAlive()) cLog.error("Trade persister did not finish within {} ms, queued trades may not be stored", pTimeoutMs);
    }

    /** Queues a message, published after the trades queued before it are stored. */
    void publish(BdxMessage pMessage) {
        if (mStore == null) { mPublisher.accept(pMessage); return; }
        queue(pMessage, false);
    }

    /** Queues a trade for storing. */
    void trade(InternalTrade pTrade) {
        if (mStore == null) return;
        queue(pTrade, true);
    }

    private void queue(Object pItem, boolean pTrade) {
        synchronized (mQueueLock) {
            mQueue.add(pItem);
            mQueued++;
            if (pTrade) mTradesQueued++;
        }
    }

    /** The number of items queued so far; awaitReleased(lastQueued()) waits for all of them. */
    long lastQueued() {
        synchronized (mQueueLock) { return mQueued; }
    }

    /** The number of trades queued so far. */
    long tradesQueued() {
        synchronized (mQueueLock) { return mTradesQueued; }
    }

    /** Waits until the first pItems queued items are stored and published (returns at once without a store). */
    synchronized void awaitReleased(long pItems) {
        if (mStore == null) return;
        boolean tInterrupted = false;
        while (mReleased < pItems) {
            try { wait(); }
            catch (InterruptedException e) { tInterrupted = true; }
        }
        if (tInterrupted) Thread.currentThread().interrupt();
    }

    private synchronized void released(int pItems) {
        mReleased += pItems;
        notifyAll();
    }

    @Override
    public void run() {
        List<Object> tBatch = new ArrayList<>();
        boolean tStop = false;
        while (!tStop) {
            try {
                tBatch.add(mQueue.take());
            } catch (InterruptedException e) {
                cLog.warn("Trade persister interrupted, {} queued item(s) not stored or published", mQueue.size());
                return;
            }
            mQueue.drainTo(tBatch, MAX_BATCH - 1);
            tStop = tBatch.remove(STOP);

            List<InternalTrade> tTrades = new ArrayList<>();
            for (Object tItem : tBatch) {
                if (tItem instanceof InternalTrade tTrade) tTrades.add(tTrade);
            }
            if (!tTrades.isEmpty()) store(tTrades);
            for (Object tItem : tBatch) {
                if (tItem instanceof BdxMessage tMessage) mPublisher.accept(tMessage);
            }
            released(tBatch.size());
            tBatch.clear();
        }
        cLog.info("Trade persister stopped");
    }

    /** Stores the trades, retrying until the write succeeds. */
    private void store(List<InternalTrade> pTrades) {
        long tStart = System.nanoTime();
        for (int tAttempt = 1; ; tAttempt++) {
            try {
                mStore.accept(pTrades);
                if (cLog.isDebugEnabled()) {
                    cLog.debug("Stored {} trade(s) in {} usec", pTrades.size(), (System.nanoTime() - tStart) / 1000);
                }
                if (tAttempt > 1) cLog.info("Stored {} trade(s) after {} attempts", pTrades.size(), tAttempt);
                return;
            } catch (Exception e) {
                cLog.error("Failed to store {} trade(s) (attempt {}), retrying in {} ms: {}",
                        pTrades.size(), tAttempt, RETRY_MS, e.toString());
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    cLog.error("Trade persister interrupted, {} trade(s) not stored", pTrades.size());
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while storing trades", ie);
                }
            }
        }
    }
}
