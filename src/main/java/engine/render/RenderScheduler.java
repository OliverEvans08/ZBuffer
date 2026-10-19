package engine.render;

import engine.concurrent.ParallelExecutor;
import engine.render.util.RenderWorkerContext;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Render-specific partitioning over the engine's common phase executor.
 *
 * Rendering and partition preparation remain serial per Renderer instance.
 */
public final class RenderScheduler implements AutoCloseable {

    private final ExecutorService renderPool;
    private final boolean ownsRenderPool;
    private final ParallelExecutor phases;

    public final int maximumWorkers;

    public RenderWorkerContext[] workerContexts =
            new RenderWorkerContext[0];

    public int[] workerFaceLoads = new int[0];
    public int[] workerTriangleStarts = new int[0];
    public int[] workerTriangleCounts = new int[0];
    public int[] workerItemStarts = new int[0];
    public int[] workerItemCounts = new int[0];

    private int[] workerItemWritePositions = new int[0];

    public RenderScheduler(
            ExecutorService sharedPool,
            int sharedWorkers
    ) {
        maximumWorkers = sharedPool == null
                ? Math.max(
                1,
                Runtime.getRuntime().availableProcessors() - 1
        )
                : Math.max(1, sharedWorkers);

        ownsRenderPool = sharedPool == null;

        if (ownsRenderPool) {
            AtomicInteger ids = new AtomicInteger();

            renderPool = Executors.newFixedThreadPool(
                    maximumWorkers,
                    task -> {
                        Thread thread = new Thread(
                                task,
                                "RenderWorker-" + ids.incrementAndGet()
                        );

                        thread.setDaemon(true);
                        return thread;
                    }
            );
        } else {
            renderPool = sharedPool;
        }

        phases = new ParallelExecutor(renderPool, maximumWorkers);
    }

    public boolean isClosed() {
        return phases.isClosed();
    }

    @Override
    public void close() {
        phases.close();

        /*
         * Do not silently discard queued tasks while a phase is waiting.
         * Shared pools remain owned by EngineContext.
         */
        if (ownsRenderPool) {
            renderPool.shutdown();
        }
    }

    public void ensureWorkerContexts(int workers) {
        if (workerContexts.length >= workers) {
            return;
        }

        int oldLength = workerContexts.length;

        workerContexts = Arrays.copyOf(
                workerContexts,
                workers
        );

        for (int i = oldLength; i < workers; i++) {
            workerContexts[i] = new RenderWorkerContext();
        }
    }

    public void ensureWorkerPartitionCapacity(int workers) {
        if (workerFaceLoads.length >= workers) {
            return;
        }

        int capacity = Math.max(workers, 8);

        workerFaceLoads = Arrays.copyOf(
                workerFaceLoads,
                capacity
        );

        workerTriangleStarts = Arrays.copyOf(
                workerTriangleStarts,
                capacity
        );

        workerTriangleCounts = Arrays.copyOf(
                workerTriangleCounts,
                capacity
        );

        workerItemStarts = Arrays.copyOf(
                workerItemStarts,
                capacity
        );

        workerItemCounts = Arrays.copyOf(
                workerItemCounts,
                capacity
        );

        workerItemWritePositions = Arrays.copyOf(
                workerItemWritePositions,
                capacity
        );
    }

    public void partitionFrameItemsByFaceCount(
            RenderFrame frame,
            int workers
    ) {
        ensureWorkerPartitionCapacity(workers);

        Arrays.fill(workerFaceLoads, 0, workers, 0);
        Arrays.fill(workerItemCounts, 0, workers, 0);

        int count = frame.itemCount;

        if (workers == 1) {
            for (int i = 0; i < count; i++) {
                frame.itemWorkers[i] = 0;
                frame.workerOrderedItems[i] = i;

                workerFaceLoads[0] = Math.addExact(
                        workerFaceLoads[0],
                        frame.meshes[i].faceCount
                );
            }

            workerItemStarts[0] = 0;
            workerItemCounts[0] = count;
            return;
        }

        /*
         * Sort primitive keys, keeping the existing largest-first assignment.
         * High bits hold face count; low bits hold the item index.
         */
        for (int i = 0; i < count; i++) {
            frame.weightedItems[i] =
                    ((long) frame.meshes[i].faceCount << 32)
                            | (i & 0xffffffffL);
        }

        Arrays.sort(frame.weightedItems, 0, count);

        for (int order = count - 1; order >= 0; order--) {
            int item = (int) frame.weightedItems[order];
            int target = 0;

            for (int i = 1; i < workers; i++) {
                if (workerFaceLoads[i] < workerFaceLoads[target]) {
                    target = i;
                }
            }

            frame.itemWorkers[item] = target;

            workerFaceLoads[target] = Math.addExact(
                    workerFaceLoads[target],
                    frame.meshes[item].faceCount
            );

            workerItemCounts[target]++;
        }

        int start = 0;

        for (int i = 0; i < workers; i++) {
            workerItemStarts[i] = start;
            workerItemWritePositions[i] = start;
            start += workerItemCounts[i];
        }

        for (int item = 0; item < count; item++) {
            int worker = frame.itemWorkers[item];

            frame.workerOrderedItems[
                    workerItemWritePositions[worker]++
                    ] = item;
        }
    }

    public void bindTriangleRanges(
            RenderFrame frame,
            int workers
    ) {
        int start = 0;

        for (int i = 0; i < workers; i++) {
            workerTriangleStarts[i] = start;
            workerTriangleCounts[i] = 0;

            int end = Math.addExact(
                    start,
                    Math.multiplyExact(workerFaceLoads[i], 2)
            );

            workerContexts[i].batch.bind(
                    frame.triangles,
                    start,
                    end
            );

            start = end;
        }
    }

    public void finishTriangleRanges(
            RenderFrame frame,
            int workers
    ) {
        int total = 0;

        for (int i = 0; i < workers; i++) {
            workerTriangleCounts[i] =
                    workerContexts[i].batch.size();

            total = Math.addExact(
                    total,
                    workerTriangleCounts[i]
            );
        }

        frame.triangles.ensurePhysicalIndexCapacity(total);

        int position = 0;

        for (int i = 0; i < workers; i++) {
            int start = workerTriangleStarts[i];
            int end = start + workerTriangleCounts[i];

            for (int triangle = start; triangle < end; triangle++) {
                frame.triangles.physicalIndices[position++] =
                        triangle;
            }
        }

        frame.triangles.count = total;
    }

    public void execute(
            int workers,
            IntConsumer work
    ) {
        ensureWorkerContexts(workers);
        phases.execute(workers, work);
    }

    public void executeDynamic(
            int workers,
            int itemCount,
            int chunkSize,
            IntConsumer work
    ) {
        phases.executeDynamic(
                workers,
                itemCount,
                chunkSize,
                work
        );
    }
}