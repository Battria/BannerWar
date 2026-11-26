package io.github.townyadvanced.flagwar.worldedit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotWorkerTest {
    @Test void jobsNeverOverlapAndFailureDoesNotPoisonTheQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        try (SnapshotWorker worker = new SnapshotWorker()) {
            var first = worker.submit(() -> {
                active.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
                    throw new IOException("Simulated failed batch");
                } finally {
                    active.decrementAndGet();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = worker.submit(() -> active.incrementAndGet());
            assertFalse(second.isDone());
            release.countDown();
            CompletionException failure = assertThrows(CompletionException.class, first::join);
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(1, second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test void shutdownCancelsQueuedJobsAndRejectsNewOnes() throws Exception {
        SnapshotWorker worker = new SnapshotWorker();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            worker.submit(() -> {
                entered.countDown();
                release.await();
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = worker.submit(() -> "must not run");
            worker.close();
            assertThrows(CancellationException.class, queued::join);
            assertThrows(CompletionException.class, () -> worker.submit(() -> "rejected").join());
        } finally {
            worker.close();
            release.countDown();
        }
    }
}
