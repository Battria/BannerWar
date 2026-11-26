package io.github.townyadvanced.flagwar.worldedit;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** One job at a time globally; queued futures also terminate when the plugin stops. */
final class SnapshotWorker implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BannerWar-WorldEdit");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopping;

    <T> CompletableFuture<T> submit(Callable<T> operation) {
        Job<T> job = new Job<>(operation);
        try {
            executor.execute(job);
        } catch (RejectedExecutionException e) {
            job.future.completeExceptionally(e);
        }
        return job.future;
    }

    void ensureRunning() {
        if (stopping || Thread.currentThread().isInterrupted())
            throw new CancellationException("Snapshot worker is stopping");
    }

    @Override
    public void close() {
        stopping = true;
        for (Runnable queued : executor.shutdownNow()) {
            ((Job<?>) queued).future.completeExceptionally(new CancellationException("Snapshot worker is stopping"));
        }
    }

    private final class Job<T> implements Runnable {
        private final Callable<T> operation;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Job(Callable<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            try {
                ensureRunning();
                future.complete(operation.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }
    }
}
