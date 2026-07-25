package engine.scene;

import engine.EngineSettings;
import engine.loop.UpdateScheduler;
import engine.spatial.SpatialIndexSynchronizer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import objects.GameObject;
import util.AABB;
import util.Matrix4;
import util.Transform;

public final class TransformPublisher {

    private static final Matrix4 IDENTITY = new Matrix4();

    private final DerivedObjectCache derivedCache;
    private final SpatialIndexSynchronizer spatialIndexes;
    private final ExecutorService updatePool;
    private final int maximumUpdateWorkers;

    private Matrix4[] worldMatrices = new Matrix4[0];
    private long[] worldVersions = new long[0];
    private AABB[] vertexPartialBounds = new AABB[0];

    private final AtomicReference<Throwable> vertexFailure =
            new AtomicReference<>();

    public TransformPublisher(
            DerivedObjectCache derivedCache,
            SpatialIndexSynchronizer spatialIndexes,
            ExecutorService updatePool,
            int maximumUpdateWorkers
    ) {
        this.derivedCache = derivedCache;
        this.spatialIndexes = spatialIndexes;
        this.updatePool = updatePool;
        this.maximumUpdateWorkers = maximumUpdateWorkers;
    }

    public AtomicReference<Throwable> getWorkerFailure() {
        return vertexFailure;
    }

    public void ensureWorldScratch(int required) {
        if (worldMatrices.length < required) {
            worldMatrices = Arrays.copyOf(worldMatrices, required);
        }

        if (worldVersions.length < required) {
            worldVersions = Arrays.copyOf(worldVersions, required);
        }
    }

    public void computeDerivedRange(
            FrameGraph graph,
            int[] indexes,
            int start,
            int end,
            UpdateContext context,
            boolean allowVertexParallel
    ) {
        final int writeFrame = context.writeFrame;

        for (int indexPosition = start; indexPosition < end; indexPosition++) {
            final int objectIndex = indexes[indexPosition];
            final GameObject object = graph.objects[objectIndex];

            if (object == null) {
                continue;
            }

            final boolean active = object.isActive();
            final boolean visible = object.isVisible();
            final boolean solid = object.isSolid();
            final boolean renderable = active && visible;

            final Transform transform = object.getTransform();
            final Matrix4 localMatrix =
                    transform == null ? null : transform.getTransformationMatrix();
            final long localVersion =
                    transform == null ? 0L : transform.getCachedVersion();

            final int parentIndex = graph.parent[objectIndex];
            final long parentVersion =
                    parentIndex >= 0 ? worldVersions[parentIndex] : 0L;
            final long worldVersion =
                    mixWorldVersion(parentVersion, localVersion);

            worldVersions[objectIndex] = worldVersion;

            final DerivedCacheEntry cache = derivedCache.get(object);

            if (cache == null) {
                throw new IllegalStateException(
                        "Missing derived cache entry for " + object
                );
            }

            final Matrix4 worldMatrix = cache.worldMatrix;
            final boolean transformChanged =
                    !cache.worldMatrixInitialized ||
                            cache.lastWorldVersion != worldVersion;

            final double worldX;
            final double worldY;
            final double worldZ;

            if (transformChanged) {
                calculateWorldMatrix(parentIndex, localMatrix, worldMatrix);
                worldMatrix.transformPoint(
                        0.0,
                        0.0,
                        0.0,
                        context.temporaryPoint
                );

                worldX = context.temporaryPoint[0];
                worldY = context.temporaryPoint[1];
                worldZ = context.temporaryPoint[2];

                cache.worldMatrixInitialized = true;
            } else {
                worldX = cache.worldX[cache.currentFrame];
                worldY = cache.worldY[cache.currentFrame];
                worldZ = cache.worldZ[cache.currentFrame];
            }

            worldMatrices[objectIndex] = worldMatrix;

            final double[][] localVertices =
                    renderable || solid ? object.getVertices() : null;
            final boolean hasVertices =
                    localVertices != null && localVertices.length > 0;

            cache.ensureVertexBuffers(
                    hasVertices ? localVertices.length : 0,
                    hasVertices ? localVertices : null
            );

            if (cache.initialized && cache.lastWorldVersion == worldVersion) {
                cache.alignToWriteFrame(writeFrame);
            } else {
                if (hasVertices) {
                    transformVerticesAndComputeBounds(
                            worldMatrix,
                            localVertices,
                            cache.transformedVertices[writeFrame],
                            worldX,
                            worldY,
                            worldZ,
                            allowVertexParallel &&
                                    shouldParallelizeVertices(localVertices.length),
                            cache.bounds[writeFrame]
                    );
                } else {
                    cache.bounds[writeFrame].set(
                            worldX,
                            worldX,
                            worldY,
                            worldY,
                            worldZ,
                            worldZ
                    );
                    cache.transformedVertices[writeFrame] =
                            DerivedCacheEntry.EMPTY_TRANSFORMED_VERTICES;
                }

                cache.worldX[writeFrame] = worldX;
                cache.worldY[writeFrame] = worldY;
                cache.worldZ[writeFrame] = worldZ;
                cache.lastWorldVersion = worldVersion;
                cache.initialized = true;
                cache.currentFrame = writeFrame;
            }

            final AABB publishedBounds = cache.bounds[writeFrame];

            object.publishFrameData(
                    writeFrame,
                    cache.worldX[writeFrame],
                    cache.worldY[writeFrame],
                    cache.worldZ[writeFrame],
                    publishedBounds,
                    cache.transformedVertices[writeFrame]
            );

            spatialIndexes.addIndexUpdates(
                    context,
                    cache,
                    object,
                    solid,
                    renderable,
                    publishedBounds
            );
        }
    }

    private void calculateWorldMatrix(
            int parentIndex,
            Matrix4 localMatrix,
            Matrix4 output
    ) {
        if (parentIndex < 0) {
            if (localMatrix == null) {
                output.setIdentity();
            } else {
                output.set(localMatrix);
            }

            return;
        }

        final Matrix4 parentMatrix = worldMatrices[parentIndex];

        if (parentMatrix == null) {
            if (localMatrix == null) {
                output.setIdentity();
            } else {
                output.set(localMatrix);
            }

            return;
        }

        if (localMatrix == null) {
            output.set(parentMatrix);
        } else {
            parentMatrix.multiply(localMatrix, output);
        }
    }

    private void transformVerticesAndComputeBounds(
            Matrix4 worldMatrix,
            double[][] localVertices,
            double[][] outputVertices,
            double worldX,
            double worldY,
            double worldZ,
            boolean allowParallel,
            AABB outputBounds
    ) {
        if (worldMatrix == null ||
                localVertices == null ||
                localVertices.length == 0 ||
                outputVertices == null) {
            outputBounds.set(
                    worldX,
                    worldX,
                    worldY,
                    worldY,
                    worldZ,
                    worldZ
            );
            return;
        }

        final int vertexCount = localVertices.length;

        if (!allowParallel ||
                vertexCount < EngineSettings.VERTEX_PARALLEL_THRESHOLD ||
                maximumUpdateWorkers <= 1) {
            calculateVertexRange(
                    worldMatrix,
                    localVertices,
                    outputVertices,
                    0,
                    vertexCount,
                    outputBounds,
                    worldX,
                    worldY,
                    worldZ
            );
            return;
        }

        final int chunks = Math.min(
                maximumUpdateWorkers,
                (vertexCount + EngineSettings.VERTEX_PARALLEL_CHUNK - 1) /
                        EngineSettings.VERTEX_PARALLEL_CHUNK
        );

        if (chunks <= 1) {
            calculateVertexRange(
                    worldMatrix,
                    localVertices,
                    outputVertices,
                    0,
                    vertexCount,
                    outputBounds,
                    worldX,
                    worldY,
                    worldZ
            );
            return;
        }

        final CountDownLatch latch = new CountDownLatch(chunks);
        final AtomicReference<Throwable> failure = vertexFailure;

        failure.set(null);
        ensureVertexPartialBounds(chunks);

        final AABB[] partialBounds = vertexPartialBounds;

        for (int chunk = 0; chunk < chunks; chunk++) {
            final int index = chunk;
            final int start = (chunk * vertexCount) / chunks;
            final int end = ((chunk + 1) * vertexCount) / chunks;

            updatePool.execute(() -> {
                try {
                    calculateVertexRange(
                            worldMatrix,
                            localVertices,
                            outputVertices,
                            start,
                            end,
                            partialBounds[index],
                            worldX,
                            worldY,
                            worldZ
                    );
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    latch.countDown();
                }
            });
        }

        UpdateScheduler.awaitUninterruptibly(latch);
        UpdateScheduler.rethrowWorkerFailure(failure.get());

        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < chunks; i++) {
            final AABB bounds = partialBounds[i];

            minimumX = Math.min(minimumX, bounds.minX);
            maximumX = Math.max(maximumX, bounds.maxX);
            minimumY = Math.min(minimumY, bounds.minY);
            maximumY = Math.max(maximumY, bounds.maxY);
            minimumZ = Math.min(minimumZ, bounds.minZ);
            maximumZ = Math.max(maximumZ, bounds.maxZ);
        }

        outputBounds.set(
                minimumX,
                maximumX,
                minimumY,
                maximumY,
                minimumZ,
                maximumZ
        );
    }

    private void ensureVertexPartialBounds(int required) {
        if (vertexPartialBounds.length >= required) {
            return;
        }

        final int oldLength = vertexPartialBounds.length;

        vertexPartialBounds = Arrays.copyOf(
                vertexPartialBounds,
                required
        );

        for (int i = oldLength; i < required; i++) {
            vertexPartialBounds[i] =
                    new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void calculateVertexRange(
            Matrix4 worldMatrix,
            double[][] localVertices,
            double[][] outputVertices,
            int start,
            int end,
            AABB outputBounds,
            double fallbackX,
            double fallbackY,
            double fallbackZ
    ) {
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;

        for (int i = start; i < end; i++) {
            final double[] vertex = localVertices[i];

            if (vertex == null || vertex.length < 3) {
                continue;
            }

            final double[] output = outputVertices[i];

            worldMatrix.transformPoint(
                    vertex[0],
                    vertex[1],
                    vertex[2],
                    output
            );

            final double x = output[0];
            final double y = output[1];
            final double z = output[2];

            minimumX = Math.min(minimumX, x);
            maximumX = Math.max(maximumX, x);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
            minimumZ = Math.min(minimumZ, z);
            maximumZ = Math.max(maximumZ, z);
        }

        if (minimumX == Double.POSITIVE_INFINITY) {
            outputBounds.set(
                    fallbackX,
                    fallbackX,
                    fallbackY,
                    fallbackY,
                    fallbackZ,
                    fallbackZ
            );
        } else {
            outputBounds.set(
                    minimumX,
                    maximumX,
                    minimumY,
                    maximumY,
                    minimumZ,
                    maximumZ
            );
        }
    }

    public void publishInitialDerivedSnapshot(GameObject root) {
        if (root == null) {
            return;
        }

        final ArrayDeque<SnapshotNode> stack = new ArrayDeque<>(256);

        stack.push(new SnapshotNode(root, null, 0L));

        final double[] temporaryPoint = new double[3];

        while (!stack.isEmpty()) {
            final SnapshotNode node = stack.pop();
            final GameObject object = node.object;

            if (object == null) {
                continue;
            }

            final Transform transform = object.getTransform();
            final Matrix4 localMatrix =
                    transform == null ? null : transform.getTransformationMatrix();
            final long localVersion =
                    transform == null ? 0L : transform.getCachedVersion();

            final Matrix4 worldMatrix;

            if (node.parentWorld == null) {
                if (localMatrix == null) {
                    worldMatrix = new Matrix4();
                    worldMatrix.set(IDENTITY);
                } else {
                    worldMatrix = localMatrix;
                }
            } else if (localMatrix == null) {
                worldMatrix = node.parentWorld;
            } else {
                worldMatrix = new Matrix4();
                node.parentWorld.multiply(localMatrix, worldMatrix);
            }

            worldMatrix.transformPoint(
                    0.0,
                    0.0,
                    0.0,
                    temporaryPoint
            );

            final double worldX = temporaryPoint[0];
            final double worldY = temporaryPoint[1];
            final double worldZ = temporaryPoint[2];
            final long worldVersion =
                    mixWorldVersion(node.parentWorldVersion, localVersion);

            final double[][] localVertices = object.getVertices();
            final int vertexCount =
                    localVertices == null ? 0 : localVertices.length;

            final DerivedCacheEntry cache =
                    derivedCache.cacheFor(object);

            cache.ensureVertexBuffers(
                    vertexCount,
                    vertexCount > 0 ? localVertices : null
            );

            if (vertexCount > 0) {
                transformVerticesAndComputeBounds(
                        worldMatrix,
                        localVertices,
                        cache.transformedVertices[0],
                        worldX,
                        worldY,
                        worldZ,
                        true,
                        cache.bounds[0]
                );

                for (int i = 0; i < vertexCount; i++) {
                    final double[] source =
                            cache.transformedVertices[0][i];
                    final double[] destination =
                            cache.transformedVertices[1][i];

                    destination[0] = source[0];
                    destination[1] = source[1];
                    destination[2] = source[2];
                }

                cache.bounds[1].setFrom(cache.bounds[0]);
            } else {
                cache.bounds[0].set(
                        worldX,
                        worldX,
                        worldY,
                        worldY,
                        worldZ,
                        worldZ
                );
                cache.bounds[1].setFrom(cache.bounds[0]);
            }

            cache.worldX[0] = worldX;
            cache.worldY[0] = worldY;
            cache.worldZ[0] = worldZ;
            cache.worldX[1] = worldX;
            cache.worldY[1] = worldY;
            cache.worldZ[1] = worldZ;
            cache.lastWorldVersion = worldVersion;
            cache.initialized = true;
            cache.worldMatrix.set(worldMatrix);
            cache.worldMatrixInitialized = true;
            cache.currentFrame = 0;

            object.publishFrameData(
                    0,
                    worldX,
                    worldY,
                    worldZ,
                    cache.bounds[0],
                    cache.transformedVertices[0]
            );

            object.publishFrameData(
                    1,
                    worldX,
                    worldY,
                    worldZ,
                    cache.bounds[1],
                    cache.transformedVertices[1]
            );

            final List<GameObject> children = object.getChildren();

            if (children != null && !children.isEmpty()) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    final GameObject child = children.get(i);

                    if (child != null) {
                        stack.push(
                                new SnapshotNode(
                                        child,
                                        worldMatrix,
                                        worldVersion
                                )
                        );
                    }
                }
            }
        }
    }

    private boolean shouldParallelizeVertices(int vertexCount) {
        return vertexCount >= EngineSettings.VERTEX_PARALLEL_THRESHOLD &&
                maximumUpdateWorkers > 1 &&
                !Thread.currentThread()
                        .getName()
                        .startsWith("EngineWorker-");
    }

    private static long mixWorldVersion(long parent, long local) {
        long value = parent;

        value ^= local +
                0x9E3779B97F4A7C15L +
                (value << 6) +
                (value >>> 2);

        return value;
    }

    private record SnapshotNode(
            GameObject object,
            Matrix4 parentWorld,
            long parentWorldVersion
    ) {}
}