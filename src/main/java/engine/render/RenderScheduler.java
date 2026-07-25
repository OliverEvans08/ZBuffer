package engine.render;

import engine.render.util.RenderWorkerContext;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

public final class RenderScheduler implements AutoCloseable {
    private final ExecutorService renderPool;
    public final int maximumWorkers;
    private final boolean ownsRenderPool;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> workerFailure =
            new AtomicReference<>();

    public RenderWorkerContext[] workerContexts =
            new RenderWorkerContext[0];
    public int[] workerFaceLoads = new int[0];
    public int[] workerTriangleStarts = new int[0];
    public int[] workerTriangleCounts = new int[0];

    public RenderScheduler(
            ExecutorService sharedPool,
            int sharedWorkers
    ) {
        final int processors =
                Runtime.getRuntime().availableProcessors();
        maximumWorkers = sharedPool == null
                ? Math.max(1, processors - 1)
                : Math.max(1, sharedWorkers);

        if (sharedPool != null) {
            renderPool = sharedPool;
            ownsRenderPool = false;
            return;
        }

        final ThreadFactory factory = new ThreadFactory() {
            private final ThreadFactory delegate =
                    Executors.defaultThreadFactory();
            private final AtomicInteger nextId =
                    new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = delegate.newThread(task);
                thread.setName(
                        "RenderWorker-" +
                                nextId.getAndIncrement()
                );
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };

        renderPool = Executors.newFixedThreadPool(
                maximumWorkers,
                factory
        );
        ownsRenderPool = true;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (
                closed.compareAndSet(false, true) &&
                        ownsRenderPool
        ) {
            renderPool.shutdownNow();
        }
    }

    public void ensureWorkerContexts(int workers) {
        if (workerContexts.length >= workers) {
            return;
        }

        final RenderWorkerContext[] expanded =
                new RenderWorkerContext[workers];
        System.arraycopy(
                workerContexts,
                0,
                expanded,
                0,
                workerContexts.length
        );

        for (int i = workerContexts.length; i < workers; i++) {
            expanded[i] = new RenderWorkerContext();
        }

        workerContexts = expanded;
    }

    public void ensureWorkerPartitionCapacity(int workers) {
        if (workerFaceLoads.length >= workers) {
            return;
        }

        final int capacity =
                growCapacity(workerFaceLoads.length, workers, 8);

        workerFaceLoads =
                Arrays.copyOf(workerFaceLoads, capacity);
        workerTriangleStarts =
                Arrays.copyOf(workerTriangleStarts, capacity);
        workerTriangleCounts =
                Arrays.copyOf(workerTriangleCounts, capacity);
    }

    public void partitionFrameItemsByFaceCount(
            RenderFrame frame,
            int workers
    ) {
        Arrays.fill(workerFaceLoads, 0, workers, 0);
        final long[] weightedItems = frame.weightedItems;

        for (int item = 0; item < frame.itemCount; item++) {
            weightedItems[item] =
                    ((long) frame.meshes[item].faceCount << 32) |
                            (item & 0xFFFFFFFFL);
        }

        Arrays.sort(weightedItems, 0, frame.itemCount);

        for (
                int order = frame.itemCount - 1;
                order >= 0;
                order--
        ) {
            final int item = (int) weightedItems[order];
            int lightestWorker = 0;
            int lightestLoad = workerFaceLoads[0];

            for (int worker = 1; worker < workers; worker++) {
                final int load = workerFaceLoads[worker];

                if (load < lightestLoad) {
                    lightestWorker = worker;
                    lightestLoad = load;
                }
            }

            frame.itemWorkers[item] = lightestWorker;
            workerFaceLoads[lightestWorker] +=
                    frame.meshes[item].faceCount;
        }
    }

    public void bindTriangleRanges(
            RenderFrame frame,
            int workers
    ) {
        int triangleStart = 0;

        for (int worker = 0; worker < workers; worker++) {
            workerTriangleStarts[worker] = triangleStart;
            workerTriangleCounts[worker] = 0;
            triangleStart += workerFaceLoads[worker] << 1;
            workerContexts[worker].batch.bind(
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
        int total = 0;

        for (int worker = 0; worker < workers; worker++) {
            final int count =
                    workerContexts[worker].batch.size();
            workerTriangleCounts[worker] = count;
            total += count;
        }

        frame.triangles.ensurePhysicalIndexCapacity(total);

        int position = 0;

        for (int worker = 0; worker < workers; worker++) {
            final int start = workerTriangleStarts[worker];
            final int count = workerTriangleCounts[worker];
            final int end = start + count;

            for (
                    int triangle = start;
                    triangle < end;
                    triangle++
            ) {
                frame.triangles.physicalIndices[position++] =
                        triangle;
            }
        }

        frame.triangles.count = total;
    }

    public void execute(int workers, IntConsumer work) {
        final CountDownLatch latch =
                new CountDownLatch(workers);
        final AtomicReference<Throwable> failure =
                workerFailure;
        failure.set(null);

        for (int worker = 0; worker < workers; worker++) {
            final int workerIndex = worker;

            renderPool.execute(() -> {
                try {
                    work.accept(workerIndex);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    latch.countDown();
                }
            });
        }

        awaitUninterruptibly(latch);
        rethrowWorkerFailure(failure.get());
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity = Math.max(minimum, current);

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity <<= 1;
        }

        return capacity;
    }

    private static void awaitUninterruptibly(
            CountDownLatch latch
    ) {
        boolean interrupted = false;

        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rethrowWorkerFailure(
            Throwable throwable
    ) {
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
                "Renderer worker failed",
                throwable
        );
    }
}