package engine.spatial;

import engine.collections.IdentitySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import objects.GameObject;

public final class SpatialHashIndex {

    private final double inverseCellSize;
    private final Predicate<GameObject> inclusionPredicate;
    private final IdentityHashMap<GameObject, SpatialEntry> entries =
            new IdentityHashMap<>();
    private final Object writeLock = new Object();

    private final ThreadLocal<IdentitySet> visited =
            ThreadLocal.withInitial(IdentitySet::new);

    private volatile SpatialHashSnapshot snapshot =
            SpatialHashSnapshot.EMPTY;

    public SpatialHashIndex(
            double cellSize,
            Predicate<GameObject> inclusionPredicate
    ) {
        if (
                !Double.isFinite(cellSize)
                        || cellSize <= 0.0
        ) {
            throw new IllegalArgumentException(
                    "cellSize must be finite and > 0"
            );
        }

        inverseCellSize = 1.0 / cellSize;
        this.inclusionPredicate =
                Objects.requireNonNull(
                        inclusionPredicate,
                        "inclusionPredicate"
                );
    }

    public List<GameObject> objects() {
        return snapshot.objects();
    }

    public void clear() {
        synchronized (writeLock) {
            entries.clear();
            snapshot = SpatialHashSnapshot.EMPTY;
        }
    }

    public void remove(GameObject object) {
        if (object == null) {
            return;
        }

        synchronized (writeLock) {
            if (entries.remove(object) != null) {
                snapshot =
                        SpatialIndexBuilder.rebuildSnapshot(
                                entries
                        );
            }
        }
    }

    public void sync(GameObject object) {
        if (object == null) {
            return;
        }

        synchronized (writeLock) {
            if (!inclusionPredicate.test(object)) {
                if (entries.remove(object) != null) {
                    snapshot =
                            SpatialIndexBuilder.rebuildSnapshot(
                                    entries
                            );
                }

                return;
            }

            final CellRange range =
                    CellRange.of(
                            object.getWorldAABB(),
                            inverseCellSize
                    );

            if (range == null) {
                if (entries.remove(object) != null) {
                    snapshot =
                            SpatialIndexBuilder.rebuildSnapshot(
                                    entries
                            );
                }

                return;
            }

            final SpatialEntry current =
                    entries.get(object);

            if (
                    current != null
                            && current.matches(range)
            ) {
                return;
            }

            entries.put(
                    object,
                    new SpatialEntry(range)
            );
            snapshot =
                    SpatialIndexBuilder.rebuildSnapshot(
                            entries
                    );
        }
    }

    public void syncBatch(SyncBuffer buffer) {
        if (buffer == null || buffer.size == 0) {
            return;
        }

        syncBatches(
                new SyncBuffer[]{buffer},
                1
        );
    }

    public void syncBatches(
            SyncBuffer[] buffers,
            int bufferCount
    ) {
        if (buffers == null || bufferCount <= 0) {
            return;
        }

        synchronized (writeLock) {
            boolean dirty = false;

            final int count =
                    Math.min(
                            bufferCount,
                            buffers.length
                    );

            for (
                    int bufferIndex = 0;
                    bufferIndex < count;
                    bufferIndex++
            ) {
                final SyncBuffer buffer =
                        buffers[bufferIndex];

                if (
                        buffer == null
                                || buffer.size == 0
                ) {
                    continue;
                }

                final int size = buffer.size;

                for (int i = 0; i < size; i++) {
                    final GameObject object =
                            buffer.objects[i];

                    buffer.objects[i] = null;

                    if (object == null) {
                        continue;
                    }

                    if (!buffer.include[i]) {
                        dirty |=
                                entries.remove(object) != null;
                        continue;
                    }

                    final SpatialEntry current =
                            entries.get(object);

                    final int minCellX =
                            buffer.minCellX[i];
                    final int maxCellX =
                            Math.max(
                                    minCellX,
                                    buffer.maxCellX[i]
                            );
                    final int minCellZ =
                            buffer.minCellZ[i];
                    final int maxCellZ =
                            Math.max(
                                    minCellZ,
                                    buffer.maxCellZ[i]
                            );

                    final boolean giant =
                            CellRange.isGiant(
                                    minCellX,
                                    maxCellX,
                                    minCellZ,
                                    maxCellZ
                            );

                    if (
                            current == null
                                    || !current.matches(
                                    minCellX,
                                    maxCellX,
                                    minCellZ,
                                    maxCellZ,
                                    giant
                            )
                    ) {
                        entries.put(
                                object,
                                new SpatialEntry(
                                        minCellX,
                                        maxCellX,
                                        minCellZ,
                                        maxCellZ,
                                        giant
                                )
                        );

                        dirty = true;
                    }
                }

                buffer.size = 0;
            }

            if (dirty) {
                snapshot =
                        SpatialIndexBuilder.rebuildSnapshot(
                                entries
                        );
            }
        }
    }

    public void queryXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        SpatialQuery.queryXZ(
                snapshot,
                inverseCellSize,
                visited,
                minX,
                maxX,
                minZ,
                maxZ,
                output
        );
    }

    public static class SyncBuffer {

        GameObject[] objects = new GameObject[0];
        boolean[] include = new boolean[0];
        int[] minCellX = new int[0];
        int[] maxCellX = new int[0];
        int[] minCellZ = new int[0];
        int[] maxCellZ = new int[0];

        public int size;

        public final void reset() {
            Arrays.fill(
                    objects,
                    0,
                    size,
                    null
            );

            size = 0;
        }

        public final void add(
                GameObject object,
                boolean shouldInclude,
                int minX,
                int maxX,
                int minZ,
                int maxZ
        ) {
            ensureCapacity(size + 1);

            objects[size] = object;
            include[size] = shouldInclude;
            minCellX[size] = minX;
            maxCellX[size] = maxX;
            minCellZ[size] = minZ;
            maxCellZ[size] = maxZ;
            size++;
        }

        private void ensureCapacity(int required) {
            if (objects.length >= required) {
                return;
            }

            int capacity =
                    objects.length == 0
                            ? 256
                            : objects.length;

            while (capacity < required) {
                if (
                        capacity
                                > Integer.MAX_VALUE / 2
                ) {
                    capacity = required;
                    break;
                }

                capacity *= 2;
            }

            objects =
                    Arrays.copyOf(objects, capacity);
            include =
                    Arrays.copyOf(include, capacity);
            minCellX =
                    Arrays.copyOf(minCellX, capacity);
            maxCellX =
                    Arrays.copyOf(maxCellX, capacity);
            minCellZ =
                    Arrays.copyOf(minCellZ, capacity);
            maxCellZ =
                    Arrays.copyOf(maxCellZ, capacity);
        }
    }
}