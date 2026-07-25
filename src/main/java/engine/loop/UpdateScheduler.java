package engine.loop;

import engine.EngineSettings;
import engine.scene.FrameGraph;
import engine.scene.TransformPublisher;
import engine.scene.UpdateContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import objects.GameObject;

public final class UpdateScheduler {

    private final ExecutorService updatePool;
    private final int maximumUpdateWorkers;
    private final TransformPublisher transformPublisher;

    public UpdateScheduler(ExecutorService updatePool, int maximumUpdateWorkers, TransformPublisher transformPublisher) {
        this.updatePool = updatePool;
        this.maximumUpdateWorkers = maximumUpdateWorkers;
        this.transformPublisher = transformPublisher;
    }

    public int workerCount(int objectCount) {
        return Math.max(1, Math.min(maximumUpdateWorkers, objectCount));
    }

    public void updateGameplayObjects(GameObject[] objects, int count, double delta) {
        for (int i = 0; i < count; i++) {
            final GameObject object = objects[i];

            if (object == null) {
                continue;
            }

            object.update(delta);

            final engine.animation.Animator animator = object.getAnimator();

            if (animator != null) {
                animator.update(delta);
            }
        }
    }

    public void computeDerivedDataByDepth(FrameGraph graph, int workers, UpdateContext[] updateContexts) {
        for (int depth = 0; depth <= graph.maximumDepth; depth++) {
            final int[] indexes = graph.depthOrder;
            final int rangeStart = graph.depthOffsets[depth];
            final int rangeEnd = graph.depthOffsets[depth + 1];
            final int count = rangeEnd - rangeStart;

            if (count == 0) {
                continue;
            }

            final int depthWorkers = count < EngineSettings.DERIVED_PARALLEL_THRESHOLD
                    ? 1
                    : Math.max(1, Math.min(workers, count));

            if (depthWorkers == 1) {
                transformPublisher.computeDerivedRange(
                        graph,
                        indexes,
                        rangeStart,
                        rangeEnd,
                        updateContexts[0],
                        true
                );
                continue;
            }

            final CountDownLatch latch = new CountDownLatch(depthWorkers);
            final AtomicReference<Throwable> failure = transformPublisher.getWorkerFailure();

            failure.set(null);

            for (int worker = 0; worker < depthWorkers; worker++) {
                final int start = rangeStart + (worker * count) / depthWorkers;
                final int end = rangeStart + ((worker + 1) * count) / depthWorkers;
                final UpdateContext context = updateContexts[worker];

                updatePool.execute(() -> {
                    try {
                        transformPublisher.computeDerivedRange(graph, indexes, start, end, context, false);
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
    }

    public static void awaitUninterruptibly(CountDownLatch latch) {
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

    public static void rethrowWorkerFailure(Throwable throwable) {
        if (throwable == null) {
            return;
        }

        if (throwable instanceof RuntimeException exception) {
            throw exception;
        }

        if (throwable instanceof Error error) {
            throw error;
        }

        throw new IllegalStateException("Update worker failed", throwable);
    }
}