package engine.concurrent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * Serial phases containing parallel work.
 *
 * Call from a coordinator thread, never from a worker of the supplied pool.
 * Each task must write only its own output range.
 *
 * Concurrent or nested phases on the same instance are rejected.
 *
 * The supplied executor must execute tasks or throw on rejection.
 * Silent discard policies are unsupported.
 *
 * Ownership of the supplied executor remains with the caller.
 */
public final class ParallelExecutor implements AutoCloseable {

    private final ExecutorService pool;
    private final Task[] tasks;

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicInteger remaining = new AtomicInteger();
    private final AtomicInteger next = new AtomicInteger();

    private final AtomicReference<Throwable> failure =
            new AtomicReference<>();

    private volatile Thread waiter;

    private IntConsumer work;
    private IntConsumer itemWork;

    private int itemCount;
    private int chunkSize;

    private final IntConsumer dynamicWork = ignored -> drainItems();

    public ParallelExecutor(
            ExecutorService pool,
            int maximumWorkers
    ) {
        this.pool = Objects.requireNonNull(pool, "pool");

        if (maximumWorkers < 1) {
            throw new IllegalArgumentException("maximumWorkers");
        }

        tasks = new Task[maximumWorkers];

        for (int i = 1; i < tasks.length; i++) {
            tasks[i] = new Task(i);
        }
    }

    public void execute(
            int workers,
            IntConsumer action
    ) {
        Objects.requireNonNull(action, "action");

        enter();

        try {
            runPhase(workers, action);
        } finally {
            busy.set(false);
        }
    }

    public void executeDynamic(
            int workers,
            int count,
            int minimumChunk,
            IntConsumer action
    ) {
        Objects.requireNonNull(action, "action");

        enter();

        try {
            if (count <= 0) {
                return;
            }

            int effectiveWorkers = Math.max(
                    1,
                    Math.min(
                            Math.min(workers, tasks.length),
                            count
                    )
            );

            itemCount = count;

            chunkSize = Math.max(
                    Math.max(1, minimumChunk),
                    (int) Math.max(
                            1L,
                            count / (8L * effectiveWorkers)
                    )
            );

            itemWork = action;
            next.set(0);

            runPhase(effectiveWorkers, dynamicWork);
        } finally {
            itemWork = null;
            busy.set(false);
        }
    }

    private void enter() {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Concurrent or nested parallel phase"
            );
        }

        if (closed.get() || pool.isShutdown()) {
            busy.set(false);

            throw new IllegalStateException(
                    "Parallel executor is closed"
            );
        }
    }

    private void runPhase(
            int workers,
            IntConsumer action
    ) {
        if (workers < 0 || workers > tasks.length) {
            throw new IllegalArgumentException(
                    "workers outside configured capacity"
            );
        }

        failure.set(null);

        if (workers == 0) {
            return;
        }

        if (workers == 1) {
            action.accept(0);
            return;
        }

        work = action;
        waiter = Thread.currentThread();

        remaining.set(workers - 1);

        for (int i = 1; i < workers; i++) {
            tasks[i].state.set(1);
        }

        try {
            for (int i = 1; i < workers; i++) {
                try {
                    pool.execute(tasks[i]);
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);

                    for (int j = i; j < workers; j++) {
                        tasks[j].cancelQueued();
                    }

                    break;
                }
            }

            try {
                action.accept(0);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }

            await(workers);
            rethrow(failure.get());
        } finally {
            work = null;
            waiter = null;
        }
    }

    private void drainItems() {
        while (failure.get() == null) {
            int start;
            int end;

            do {
                start = next.get();

                if (start >= itemCount) {
                    return;
                }

                end = (int) Math.min(
                        (long) itemCount,
                        (long) start + chunkSize
                );
            } while (!next.compareAndSet(start, end));

            for (int i = start; i < end; i++) {
                itemWork.accept(i);
            }
        }
    }

    private void await(int workers) {
        boolean interrupted = false;
        int spins = 0;

        while (remaining.get() != 0) {
            if (Thread.interrupted()) {
                interrupted = true;
            }

            if (pool.isShutdown()) {
                closed.set(true);

                failure.compareAndSet(
                        null,
                        new RejectedExecutionException(
                                "Executor shut down during phase"
                        )
                );

                /*
                 * shutdownNow can remove accepted tasks without executing them.
                 *
                 * Cancel only tasks that have not started. Running tasks must
                 * finish before their output buffers can be released or reused.
                 *
                 * This executor is permanently closed after pool shutdown,
                 * so cancelled task instances cannot be reused by another phase.
                 */
                for (int i = 1; i < workers; i++) {
                    tasks[i].cancelQueued();
                }
            }

            if (spins++ < 64) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, 100_000L);
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void completed() {
        Thread target = waiter;

        if (remaining.decrementAndGet() == 0 && target != null) {
            LockSupport.unpark(target);
        }
    }

    private final class Task implements Runnable {

        private final int index;

        /*
         * 1 = queued
         * 2 = running
         * 3 = cancelled or finished
         */
        private final AtomicInteger state = new AtomicInteger(3);

        private Task(int index) {
            this.index = index;
        }

        private void cancelQueued() {
            if (state.compareAndSet(1, 3)) {
                completed();
            }
        }

        @Override
        public void run() {
            if (!state.compareAndSet(1, 2)) {
                return;
            }

            try {
                work.accept(index);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                state.set(3);
                completed();
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Prevents new phases.
     * An already running phase is allowed to finish.
     */
    @Override
    public void close() {
        closed.set(true);
    }

    public static void rethrow(Throwable throwable) {
        if (throwable == null) {
            return;
        }

        if (throwable instanceof RuntimeException exception) {
            throw exception;
        }

        if (throwable instanceof Error error) {
            throw error;
        }

        throw new IllegalStateException(
                "Parallel worker failed",
                throwable
        );
    }
}