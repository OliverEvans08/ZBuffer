package engine.render;

import engine.render.util.RenderWorkerContext;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * Allocation-light renderer scheduler.
 *
 * <p>The original implementation allocated a CountDownLatch and one lambda
 * wrapper per worker on every parallel phase. This version reuses permanent
 * WorkerTask instances and a single atomic completion counter.</p>
 *
 * <p>Rendering is expected to be serial for each Renderer instance. Therefore,
 * one scheduler phase always completes before the next phase begins.</p>
 */
public final class RenderScheduler implements AutoCloseable {
    private static final int SPIN_LIMIT = 256;
    private static final long PARK_NANOS = 50_000L;

    private final ExecutorService renderPool;
    private final boolean ownsRenderPool;

    private final AtomicBoolean closed =
            new AtomicBoolean();

    private final AtomicReference<Throwable> workerFailure =
            new AtomicReference<>();

    /*
     * Reused dynamic-work counter. Rendering is serial per Renderer, so this
     * object can safely serve every dynamic phase without allocation.
     */
    private final AtomicInteger nextDynamicWork =
            new AtomicInteger();

    /*
     * Reused phase-completion counter. This replaces a new CountDownLatch for
     * every parallel renderer phase.
     */
    private final AtomicInteger remainingWorkers =
            new AtomicInteger();

    /*
     * Current phase state. WorkerTask instances are permanent and read these
     * volatile values when the executor starts them.
     */
    private volatile IntConsumer currentWork;
    private volatile Thread waitingThread;

    private WorkerTask[] workerTasks =
            new WorkerTask[0];

    public final int maximumWorkers;

    public RenderWorkerContext[] workerContexts =
            new RenderWorkerContext[0];

    public int[] workerFaceLoads =
            new int[0];

    public int[] workerTriangleStarts =
            new int[0];

    public int[] workerTriangleCounts =
            new int[0];

    /*
     * Contiguous ranges into RenderFrame.workerOrderedItems.
     */
    public int[] workerItemStarts =
            new int[0];

    public int[] workerItemCounts =
            new int[0];

    /*
     * Temporary write positions used while compacting assigned items into
     * workerOrderedItems.
     */
    private int[] workerItemWritePositions =
            new int[0];

    public RenderScheduler(
            ExecutorService sharedPool,
            int sharedWorkers
    ) {
        final int processors =
                Runtime
                        .getRuntime()
                        .availableProcessors();

        maximumWorkers =
                sharedPool == null
                        ? Math.max(
                        1,
                        processors - 1
                )
                        : Math.max(
                        1,
                        sharedWorkers
                );

        if (sharedPool != null) {
            renderPool =
                    sharedPool;

            ownsRenderPool =
                    false;

            return;
        }

        final ThreadFactory delegate =
                Executors.defaultThreadFactory();

        final AtomicInteger nextId =
                new AtomicInteger(1);

        final ThreadFactory factory =
                task -> {
                    final Thread thread =
                            delegate.newThread(
                                    task
                            );

                    thread.setName(
                            "RenderWorker-" +
                                    nextId.getAndIncrement()
                    );

                    thread.setDaemon(
                            true
                    );

                    thread.setPriority(
                            Thread.NORM_PRIORITY
                    );

                    return thread;
                };

        renderPool =
                Executors.newFixedThreadPool(
                        maximumWorkers,
                        factory
                );

        ownsRenderPool =
                true;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (
                closed.compareAndSet(
                        false,
                        true
                ) &&
                        ownsRenderPool
        ) {
            renderPool.shutdownNow();
        }
    }

    public void ensureWorkerContexts(
            int workers
    ) {
        if (
                workerContexts.length >=
                        workers
        ) {
            return;
        }

        final int oldLength =
                workerContexts.length;

        workerContexts =
                Arrays.copyOf(
                        workerContexts,
                        workers
                );

        workerTasks =
                Arrays.copyOf(
                        workerTasks,
                        workers
                );

        for (
                int worker = oldLength;
                worker < workers;
                worker++
        ) {
            workerContexts[worker] =
                    new RenderWorkerContext();

            workerTasks[worker] =
                    new WorkerTask(
                            worker
                    );
        }
    }

    public void ensureWorkerPartitionCapacity(
            int workers
    ) {
        if (
                workerFaceLoads.length >=
                        workers
        ) {
            return;
        }

        final int capacity =
                growCapacity(
                        workerFaceLoads.length,
                        workers,
                        8
                );

        workerFaceLoads =
                Arrays.copyOf(
                        workerFaceLoads,
                        capacity
                );

        workerTriangleStarts =
                Arrays.copyOf(
                        workerTriangleStarts,
                        capacity
                );

        workerTriangleCounts =
                Arrays.copyOf(
                        workerTriangleCounts,
                        capacity
                );

        workerItemStarts =
                Arrays.copyOf(
                        workerItemStarts,
                        capacity
                );

        workerItemCounts =
                Arrays.copyOf(
                        workerItemCounts,
                        capacity
                );

        workerItemWritePositions =
                Arrays.copyOf(
                        workerItemWritePositions,
                        capacity
                );
    }

    public void partitionFrameItemsByFaceCount(
            RenderFrame frame,
            int workers
    ) {
        Arrays.fill(
                workerFaceLoads,
                0,
                workers,
                0
        );

        Arrays.fill(
                workerItemCounts,
                0,
                workers,
                0
        );

        final int itemCount =
                frame.itemCount;

        final int[] orderedItems =
                frame.workerOrderedItems;

        /*
         * Avoid sorting and assignment logic entirely for the very common
         * single-worker path.
         */
        if (workers == 1) {
            int faceLoad =
                    0;

            for (
                    int item = 0;
                    item < itemCount;
                    item++
            ) {
                frame.itemWorkers[item] =
                        0;

                orderedItems[item] =
                        item;

                faceLoad +=
                        frame.meshes[item]
                                .faceCount;
            }

            workerFaceLoads[0] =
                    faceLoad;

            workerItemStarts[0] =
                    0;

            workerItemCounts[0] =
                    itemCount;

            return;
        }

        final long[] weightedItems =
                frame.weightedItems;

        /*
         * Pack face count into the high bits and item index into the low bits.
         * Primitive-array sorting avoids object creation and comparator calls.
         */
        for (
                int item = 0;
                item < itemCount;
                item++
        ) {
            weightedItems[item] =
                    (
                            (long)
                                    frame.meshes[item]
                                            .faceCount <<
                                    32
                    ) |
                            (
                                    item &
                                            0xFFFF_FFFFL
                            );
        }

        Arrays.sort(
                weightedItems,
                0,
                itemCount
        );

        /*
         * Largest-processing-time partitioning.
         *
         * Worker counts are normally small. A short linear minimum search is
         * generally cheaper than maintaining and mutating a heap for every
         * visible object.
         */
        for (
                int order = itemCount - 1;
                order >= 0;
                order--
        ) {
            final int item =
                    (int) weightedItems[order];

            int lightestWorker =
                    0;

            int lightestLoad =
                    workerFaceLoads[0];

            for (
                    int worker = 1;
                    worker < workers;
                    worker++
            ) {
                final int load =
                        workerFaceLoads[worker];

                if (load < lightestLoad) {
                    lightestWorker =
                            worker;

                    lightestLoad =
                            load;
                }
            }

            frame.itemWorkers[item] =
                    lightestWorker;

            workerFaceLoads[
                    lightestWorker
                    ] +=
                    frame.meshes[item]
                            .faceCount;

            workerItemCounts[
                    lightestWorker
                    ]++;
        }

        /*
         * Convert worker assignments into compact contiguous item ranges.
         */
        int itemStart =
                0;

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            workerItemStarts[worker] =
                    itemStart;

            workerItemWritePositions[worker] =
                    itemStart;

            itemStart +=
                    workerItemCounts[worker];
        }

        for (
                int item = 0;
                item < itemCount;
                item++
        ) {
            final int worker =
                    frame.itemWorkers[item];

            orderedItems[
                    workerItemWritePositions[
                            worker
                            ]++
                    ] =
                    item;
        }
    }

    public void bindTriangleRanges(
            RenderFrame frame,
            int workers
    ) {
        int triangleStart =
                0;

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            workerTriangleStarts[worker] =
                    triangleStart;

            workerTriangleCounts[worker] =
                    0;

            triangleStart +=
                    workerFaceLoads[worker] <<
                            1;

            workerContexts[worker]
                    .batch
                    .bind(
                            frame.triangles,
                            workerTriangleStarts[worker],
                            triangleStart
                    );
        }
    }

    public void finishTriangleRanges(
            RenderFrame frame,
            int workers
    ) {
        int total =
                0;

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            final int count =
                    workerContexts[worker]
                            .batch
                            .size();

            workerTriangleCounts[worker] =
                    count;

            total +=
                    count;
        }

        frame.triangles
                .ensurePhysicalIndexCapacity(
                        total
                );

        final int[] physicalIndices =
                frame.triangles.physicalIndices;

        int position =
                0;

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            final int start =
                    workerTriangleStarts[worker];

            final int end =
                    start +
                            workerTriangleCounts[worker];

            for (
                    int triangle = start;
                    triangle < end;
                    triangle++
            ) {
                physicalIndices[position++] =
                        triangle;
            }
        }

        frame.triangles.count =
                total;
    }

    /**
     * Executes one logical task for each worker index.
     *
     * <p>The calling/render thread performs worker zero directly. Remaining
     * workers use reusable WorkerTask instances. This avoids allocating a
     * CountDownLatch and wrapper Runnable instances every frame.</p>
     */
    public void execute(
            int workers,
            IntConsumer work
    ) {
        if (workers <= 0) {
            return;
        }

        if (workers == 1) {
            work.accept(
                    0
            );

            return;
        }

        if (closed.get()) {
            throw new IllegalStateException(
                    "Renderer scheduler is closed"
            );
        }

        ensureWorkerContexts(
                workers
        );

        workerFailure.set(
                null
        );

        currentWork =
                work;

        waitingThread =
                Thread.currentThread();

        remainingWorkers.set(
                workers - 1
        );

        int submitted =
                0;

        try {
            for (
                    int worker = 1;
                    worker < workers;
                    worker++
            ) {
                renderPool.execute(
                        workerTasks[worker]
                );

                submitted++;
            }
        } catch (
                RejectedExecutionException exception
        ) {
            workerFailure.compareAndSet(
                    null,
                    exception
            );

            /*
             * Tasks that were never submitted cannot decrement the completion
             * counter, so remove them here.
             */
            final int notSubmitted =
                    workers -
                            1 -
                            submitted;

            if (notSubmitted > 0) {
                remainingWorkers.addAndGet(
                        -notSubmitted
                );
            }
        }

        try {
            work.accept(
                    0
            );
        } catch (
                Throwable throwable
        ) {
            workerFailure.compareAndSet(
                    null,
                    throwable
            );
        }

        awaitWorkers();

        currentWork =
                null;

        waitingThread =
                null;

        rethrowWorkerFailure(
                workerFailure.get()
        );
    }

    /**
     * Dynamically distributes a range of items between workers.
     *
     * <p>Workers claim contiguous chunks until all work is complete. Chunk
     * size rises for large batches to reduce atomic-counter cache contention.</p>
     */
    public void executeDynamic(
            int workers,
            int itemCount,
            int chunkSize,
            IntConsumer work
    ) {
        if (itemCount <= 0) {
            return;
        }

        final int effectiveWorkers =
                Math.max(
                        1,
                        Math.min(
                                workers,
                                itemCount
                        )
                );

        /*
         * Target approximately eight successful claims per worker. This keeps
         * load balancing dynamic while bounding AtomicInteger traffic.
         */
        final int balancingChunk =
                Math.max(
                        1,
                        itemCount /
                                (
                                        effectiveWorkers <<
                                                3
                                )
                );

        final int effectiveChunkSize =
                Math.max(
                        Math.max(
                                1,
                                chunkSize
                        ),
                        balancingChunk
                );

        nextDynamicWork.set(
                0
        );

        execute(
                effectiveWorkers,
                ignored -> {
                    for (;;) {
                        final int start =
                                nextDynamicWork.getAndAdd(
                                        effectiveChunkSize
                                );

                        if (start >= itemCount) {
                            return;
                        }

                        final int uncheckedEnd =
                                start +
                                        effectiveChunkSize;

                        final int end =
                                uncheckedEnd < itemCount
                                        ? uncheckedEnd
                                        : itemCount;

                        for (
                                int item = start;
                                item < end;
                                item++
                        ) {
                            work.accept(
                                    item
                            );
                        }
                    }
                }
        );
    }

    /*
     * Hybrid spin/park wait:
     *
     * - Very short renderer tasks complete while the calling thread spins,
     *   avoiding a kernel-level park/unpark cycle.
     * - Longer phases park briefly so the render thread does not burn an
     *   entire CPU core waiting for workers.
     */
    private void awaitWorkers() {
        boolean interrupted =
                false;

        int spins =
                0;

        while (
                remainingWorkers.get() !=
                        0
        ) {
            if (Thread.interrupted()) {
                interrupted =
                        true;
            }

            if (spins++ < SPIN_LIMIT) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(
                        this,
                        PARK_NANOS
                );
            }
        }

        if (interrupted) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    /*
     * Permanent executor task. It is queued repeatedly but never recreated.
     */
    private final class WorkerTask implements Runnable {
        private final int workerIndex;

        private WorkerTask(
                int workerIndex
        ) {
            this.workerIndex =
                    workerIndex;
        }

        @Override
        public void run() {
            try {
                final IntConsumer work =
                        currentWork;

                if (work != null) {
                    work.accept(
                            workerIndex
                    );
                }
            } catch (
                    Throwable throwable
            ) {
                workerFailure.compareAndSet(
                        null,
                        throwable
                );
            } finally {
                if (
                        remainingWorkers.decrementAndGet() ==
                                0
                ) {
                    final Thread waiter =
                            waitingThread;

                    if (waiter != null) {
                        LockSupport.unpark(
                                waiter
                        );
                    }
                }
            }
        }
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity =
                Math.max(
                        minimum,
                        current
                );

        while (capacity < required) {
            if (
                    capacity >
                            Integer.MAX_VALUE /
                                    2
            ) {
                return required;
            }

            capacity <<=
                    1;
        }

        return capacity;
    }

    private static void rethrowWorkerFailure(
            Throwable throwable
    ) {
        if (throwable == null) {
            return;
        }

        if (
                throwable instanceof
                        RuntimeException exception
        ) {
            throw exception;
        }

        if (
                throwable instanceof
                        Error error
        ) {
            throw error;
        }

        throw new IllegalStateException(
                "Renderer worker failed",
                throwable
        );
    }
}