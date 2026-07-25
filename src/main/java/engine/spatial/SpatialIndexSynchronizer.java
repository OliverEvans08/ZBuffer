package engine.spatial;

import engine.ColliderIndex;
import engine.EngineSettings;
import engine.SpatialIndex;
import engine.scene.DerivedCacheEntry;
import engine.scene.UpdateContext;
import java.util.ArrayList;
import java.util.List;
import objects.GameObject;
import util.AABB;

public final class SpatialIndexSynchronizer {

    private final ColliderIndex colliderIndex =
            new ColliderIndex(
                    EngineSettings.COLLIDER_CELL_SIZE
            );

    private final SpatialIndex renderIndex =
            new SpatialIndex(
                    EngineSettings.COLLIDER_CELL_SIZE
            );

    private final double inverseCellSize =
            1.0 / EngineSettings.COLLIDER_CELL_SIZE;

    private final ColliderIndex.SyncBuffer removedColliders =
            new ColliderIndex.SyncBuffer();

    private final SpatialIndex.SyncBuffer removedRenderables =
            new SpatialIndex.SyncBuffer();

    private ColliderIndex.SyncBuffer[] colliderBuffers =
            new ColliderIndex.SyncBuffer[0];

    private SpatialIndex.SyncBuffer[] renderBuffers =
            new SpatialIndex.SyncBuffer[0];

    public void beginTick() {
        removedColliders.reset();
        removedRenderables.reset();
    }

    public void syncImmediate(GameObject object) {
        colliderIndex.sync(object);
        renderIndex.sync(object);
    }

    public void enqueueRemoval(GameObject object) {
        removedColliders.add(
                object,
                false,
                0,
                0,
                0,
                0
        );

        removedRenderables.add(
                object,
                false,
                0,
                0,
                0,
                0
        );
    }

    public void queryNearbyCollidersXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        colliderIndex.queryXZ(
                minX,
                maxX,
                minZ,
                maxZ,
                output
        );
    }

    public void queryNearbyRenderablesXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        renderIndex.queryXZ(
                minX,
                maxX,
                minZ,
                maxZ,
                output
        );
    }

    public List<GameObject> getColliders() {
        return colliderIndex.getColliders();
    }

    public void addIndexUpdates(
            UpdateContext context,
            DerivedCacheEntry cache,
            GameObject object,
            boolean solid,
            boolean renderable,
            AABB bounds
    ) {
        if (!solid && !renderable) {
            if (cache.colliderIndexed) {
                context.colliders.add(
                        object,
                        false,
                        0,
                        0,
                        0,
                        0
                );

                cache.colliderIndexed = false;
            }

            if (cache.renderableIndexed) {
                context.renderables.add(
                        object,
                        false,
                        0,
                        0,
                        0,
                        0
                );

                cache.renderableIndexed = false;
            }

            return;
        }

        int minimumCellX =
                cellMinimum(bounds.minX);
        int maximumCellX =
                cellMaximum(bounds.maxX);
        int minimumCellZ =
                cellMinimum(bounds.minZ);
        int maximumCellZ =
                cellMaximum(bounds.maxZ);

        if (maximumCellX < minimumCellX) {
            maximumCellX = minimumCellX;
        }

        if (maximumCellZ < minimumCellZ) {
            maximumCellZ = minimumCellZ;
        }

        final boolean cellsChanged =
                !cache.indexCellsInitialized
                        || cache.minimumCellX
                        != minimumCellX
                        || cache.maximumCellX
                        != maximumCellX
                        || cache.minimumCellZ
                        != minimumCellZ
                        || cache.maximumCellZ
                        != maximumCellZ;

        if (solid) {
            if (
                    !cache.colliderIndexed
                            || cellsChanged
            ) {
                context.colliders.add(
                        object,
                        true,
                        minimumCellX,
                        maximumCellX,
                        minimumCellZ,
                        maximumCellZ
                );
            }
        } else if (cache.colliderIndexed) {
            context.colliders.add(
                    object,
                    false,
                    0,
                    0,
                    0,
                    0
            );
        }

        if (renderable) {
            if (
                    !cache.renderableIndexed
                            || cellsChanged
            ) {
                context.renderables.add(
                        object,
                        true,
                        minimumCellX,
                        maximumCellX,
                        minimumCellZ,
                        maximumCellZ
                );
            }
        } else if (cache.renderableIndexed) {
            context.renderables.add(
                    object,
                    false,
                    0,
                    0,
                    0,
                    0
            );
        }

        cache.colliderIndexed = solid;
        cache.renderableIndexed = renderable;
        cache.indexCellsInitialized = true;
        cache.minimumCellX = minimumCellX;
        cache.maximumCellX = maximumCellX;
        cache.minimumCellZ = minimumCellZ;
        cache.maximumCellZ = maximumCellZ;
    }

    public void flushAndPublish(
            int workers,
            UpdateContext[] updateContexts
    ) {
        ensureBufferCapacity(workers);

        for (int i = 0; i < workers; i++) {
            colliderBuffers[i] =
                    updateContexts[i].colliders;
            renderBuffers[i] =
                    updateContexts[i].renderables;
        }

        int colliderBufferCount = workers;
        int renderBufferCount = workers;

        final ColliderIndex.SyncBuffer[]
                activeColliderBuffers =
                colliderBuffers;

        final SpatialIndex.SyncBuffer[]
                activeRenderBuffers =
                renderBuffers;

        if (removedColliders.size > 0) {
            colliderBuffers[workers] =
                    removedColliders;
            colliderBufferCount = workers + 1;
        }

        if (removedRenderables.size > 0) {
            renderBuffers[workers] =
                    removedRenderables;
            renderBufferCount = workers + 1;
        }

        colliderIndex.syncBatches(
                activeColliderBuffers,
                colliderBufferCount
        );

        renderIndex.syncBatches(
                activeRenderBuffers,
                renderBufferCount
        );

        final int writeFrame =
                1 - GameObject.getPublishedFrameIndex();

        GameObject.setPublishedFrameIndex(writeFrame);

        removedColliders.reset();
        removedRenderables.reset();
    }

    private void ensureBufferCapacity(int workers) {
        if (
                colliderBuffers.length
                        < workers + 1
        ) {
            colliderBuffers =
                    new ColliderIndex.SyncBuffer[
                            workers + 1
                            ];
        }

        if (
                renderBuffers.length
                        < workers + 1
        ) {
            renderBuffers =
                    new SpatialIndex.SyncBuffer[
                            workers + 1
                            ];
        }
    }

    private int cellMinimum(double world) {
        return floorToInt(
                world * inverseCellSize
        );
    }

    private int cellMaximum(double world) {
        return floorToInt(
                (
                        world
                                - EngineSettings.EDGE_EPSILON
                ) * inverseCellSize
        );
    }

    private static int floorToInt(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }

        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) Math.floor(value);
    }
}