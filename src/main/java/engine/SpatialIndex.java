package engine;

import objects.GameObject;

import java.util.ArrayList;

/** Lock-free-query XZ broad-phase index for active, visible renderables. */
public final class SpatialIndex {

    private final SpatialHashIndex delegate;

    public SpatialIndex(double cellSize) {
        delegate = new SpatialHashIndex(
                cellSize,
                object -> object.isActive() && object.isVisible()
        );
    }

    public void clear() {
        delegate.clear();
    }

    public void remove(GameObject object) {
        delegate.remove(object);
    }

    public void sync(GameObject object) {
        delegate.sync(object);
    }

    public void syncBatch(SyncBuffer buffer) {
        delegate.syncBatch(buffer);
    }

    public void syncBatches(
            SyncBuffer[] buffers,
            int bufferCount
    ) {
        delegate.syncBatches(buffers, bufferCount);
    }

    public void queryXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        delegate.queryXZ(minX, maxX, minZ, maxZ, output);
    }

    public static final class SyncBuffer
            extends SpatialHashIndex.SyncBuffer {
    }
}