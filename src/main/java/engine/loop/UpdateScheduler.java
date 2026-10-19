package engine.loop;

import engine.EngineSettings;
import engine.concurrent.ParallelExecutor;
import engine.scene.FrameGraph;
import engine.scene.TransformPublisher;
import engine.scene.UpdateContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

import objects.GameObject;

/**
 * Gameplay stays ordered.
 * Derived data runs in parent-before-child phases.
 *
 * One simulation thread owns this scheduler and its phase preparation.
 */
public final class UpdateScheduler {

    private final int maximumUpdateWorkers;
    private final TransformPublisher transformPublisher;
    private final ParallelExecutor phases;

    private FrameGraph currentGraph;
    private UpdateContext[] currentContexts;

    private int rangeStart;
    private int rangeCount;
    private int phaseWorkers;

    private final IntConsumer derivedWork = this::computeWorkerRange;

    public UpdateScheduler(
            ExecutorService updatePool,
            int maximumUpdateWorkers,
            TransformPublisher transformPublisher
    ) {
        this.maximumUpdateWorkers =
                Math.max(1, maximumUpdateWorkers);

        this.transformPublisher = transformPublisher;

        phases = new ParallelExecutor(
                updatePool,
                this.maximumUpdateWorkers
        );
    }

    public int workerCount(int objectCount) {
        return Math.max(
                1,
                Math.min(maximumUpdateWorkers, objectCount)
        );
    }

    public void updateGameplayObjects(
            GameObject[] objects,
            int count,
            double delta
    ) {
        for (int i = 0; i < count; i++) {
            GameObject object = objects[i];

            if (object == null) {
                continue;
            }

            object.update(delta);

            engine.animation.Animator animator =
                    object.getAnimator();

            if (animator != null) {
                animator.update(delta);
            }
        }
    }

    public void computeDerivedDataByDepth(
            FrameGraph graph,
            int workers,
            UpdateContext[] contexts
    ) {
        currentGraph = graph;
        currentContexts = contexts;

        try {
            for (int depth = 0; depth <= graph.maximumDepth; depth++) {
                rangeStart = graph.depthOffsets[depth];

                rangeCount =
                        graph.depthOffsets[depth + 1] - rangeStart;

                if (rangeCount == 0) {
                    continue;
                }

                /*
                 * Treat the threshold as a minimum useful batch per worker,
                 * instead of allowing dozens of tiny jobs at the threshold.
                 */
                int usefulWorkers = Math.max(
                        1,
                        rangeCount / Math.max(
                                1,
                                EngineSettings.DERIVED_PARALLEL_THRESHOLD
                        )
                );

                phaseWorkers = Math.min(
                        Math.min(workers, contexts.length),
                        Math.min(maximumUpdateWorkers, usefulWorkers)
                );

                if (phaseWorkers < 1) {
                    throw new IllegalArgumentException(
                            "No update context"
                    );
                }

                if (phaseWorkers == 1) {
                    /*
                     * This runs on the simulation thread, so a large mesh
                     * may use vertex-level parallelism.
                     */
                    transformPublisher.computeDerivedRange(
                            graph,
                            graph.depthOrder,
                            rangeStart,
                            rangeStart + rangeCount,
                            contexts[0],
                            true
                    );
                } else {
                    phases.execute(phaseWorkers, derivedWork);
                }
            }
        } finally {
            currentGraph = null;
            currentContexts = null;
        }
    }

    private void computeWorkerRange(int worker) {
        int start = rangeStart + (int) (
                (long) worker * rangeCount / phaseWorkers
        );

        int end = rangeStart + (int) (
                (long) (worker + 1) * rangeCount / phaseWorkers
        );

        /*
         * Never submit nested vertex work from a pool worker.
         * Each worker has its own spatial-index staging buffers.
         */
        transformPublisher.computeDerivedRange(
                currentGraph,
                currentGraph.depthOrder,
                start,
                end,
                currentContexts[worker],
                false
        );
    }

    /**
     * Compatibility helper for existing callers.
     */
    public static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;

        for (;;) {
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

    public static void rethrowWorkerFailure(Throwable throwable) {
        ParallelExecutor.rethrow(throwable);
    }
}