package com.hoddmimes.emporia.server.me;

import com.hoddmimes.emporia.server.bdx.BdxMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TradePersisterTest {

    private static final long INST = (1L << 32) | 1;
    private static final long S    = MatchingEngine.SCALE;

    private final List<String>        mEvents    = Collections.synchronizedList(new ArrayList<>());   // "store n" / message types
    private final List<Integer>       mBatches   = Collections.synchronizedList(new ArrayList<>());
    private TradePersister mPersister;

    @AfterEach
    void tearDown() {
        if (mPersister != null) mPersister.stopPersisting(5000);
    }

    private TradePersister persister(Consumer<List<InternalTrade>> pStore) {
        mPersister = new TradePersister(pStore, m -> mEvents.add(m.type()));
        mPersister.startPersisting();
        return mPersister;
    }

    private static InternalTrade trade() {
        Order tBuy  = new Order(new AddOrderRequest(INST, Order.Side.BUY,  10 * S, S, "FA", null));
        Order tSell = new Order(new AddOrderRequest(INST, Order.Side.SELL, 10 * S, S, "FB", null));
        return new InternalTrade(INST, 10 * S, S, tBuy, tSell);
    }

    private static BdxMessage message(String pType) {
        return BdxMessage.instrument(pType, 1, INST);
    }

    @Test
    void messagesArePublishedOnlyAfterTheTradesBeforeThemAreStored() throws Exception {
        CountDownLatch tStoreStarted = new CountDownLatch(1);
        CountDownLatch tDiskDone     = new CountDownLatch(1);
        TradePersister tPersister = persister(t -> {
            tStoreStarted.countDown();
            try { tDiskDone.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            mEvents.add("store " + t.size());
        });

        tPersister.publish(message(BdxMessage.ADD_ORDER));
        tPersister.trade(trade());
        tPersister.publish(message(BdxMessage.TRADE));
        tPersister.publish(message(BdxMessage.PRICE_UPDATE));
        long tWaitFor = tPersister.lastQueued();
        assertEquals(1, tPersister.tradesQueued());

        assertTrue(tStoreStarted.await(5, TimeUnit.SECONDS));
        Thread tCaller = new Thread(() -> tPersister.awaitReleased(tWaitFor));
        tCaller.start();
        Thread.sleep(100);
        assertTrue(tCaller.isAlive(), "the caller waits for the write");
        assertFalse(mEvents.contains(BdxMessage.TRADE), "nothing about the trade is published before it is stored");

        tDiskDone.countDown();
        tCaller.join(5000);
        assertFalse(tCaller.isAlive());
        // The batch's trades are stored first, then its messages are published in order
        assertEquals(List.of("store 1", BdxMessage.ADD_ORDER, BdxMessage.TRADE, BdxMessage.PRICE_UPDATE), mEvents);
    }

    @Test
    void tradesQueuedDuringAWriteAreStoredTogether() throws Exception {
        CountDownLatch tFirstWrite = new CountDownLatch(1);
        CountDownLatch tRelease    = new CountDownLatch(1);
        TradePersister tPersister = persister(t -> {
            mBatches.add(t.size());
            tFirstWrite.countDown();
            try { tRelease.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });

        tPersister.trade(trade());
        assertTrue(tFirstWrite.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) tPersister.trade(trade());   // while the first write is on its way
        tRelease.countDown();
        tPersister.awaitReleased(tPersister.lastQueued());
        assertEquals(List.of(1, 5), mBatches);                  // group commit
    }

    @Test
    void aFailedWriteIsRetriedAndHoldsBackTheMessages() throws Exception {
        AtomicInteger tAttempts = new AtomicInteger();
        TradePersister tPersister = persister(t -> {
            if (tAttempts.incrementAndGet() == 1) throw new IllegalStateException("database down");
            mEvents.add("store " + t.size());
        });

        tPersister.trade(trade());
        tPersister.publish(message(BdxMessage.TRADE));
        tPersister.awaitReleased(tPersister.lastQueued());
        assertEquals(2, tAttempts.get());
        assertEquals(List.of("store 1", BdxMessage.TRADE), mEvents);
    }

    @Test
    void stopStoresAndPublishesWhatIsQueued() {
        TradePersister tPersister = persister(t -> mEvents.add("store " + t.size()));
        tPersister.trade(trade());
        tPersister.publish(message(BdxMessage.TRADE));
        tPersister.stopPersisting(5000);
        assertFalse(tPersister.isAlive());
        assertEquals(List.of("store 1", BdxMessage.TRADE), mEvents);
    }

    @Test
    void withoutAStoreMessagesArePublishedAtOnce() {
        TradePersister tPersister = persister(null);
        tPersister.trade(trade());
        tPersister.publish(message(BdxMessage.TRADE));
        tPersister.awaitReleased(Long.MAX_VALUE);   // returns at once
        assertEquals(List.of(BdxMessage.TRADE), mEvents);
        assertFalse(tPersister.isAlive());
    }
}
