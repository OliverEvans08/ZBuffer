package engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import objects.GameObject;
import util.AABB;

final class SpatialHashIndex {

    private static final double EDGE_EPSILON = 1.0e-9;
    private static final long MAX_CELLS_PER_OBJECT = 65_536L;
    private static final long MAX_CELLS_PER_QUERY = 262_144L;

    private final double inverseCellSize;
    private final Predicate<GameObject> inclusionPredicate;
    private final IdentityHashMap<GameObject, Entry> entries =
            new IdentityHashMap<>();
    private final Object writeLock = new Object();

    private final ThreadLocal<IdentitySet> visited =
            ThreadLocal.withInitial(IdentitySet::new);

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    SpatialHashIndex(
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

    List<GameObject> objects() {
        return snapshot.objects();
    }

    void clear() {
        synchronized (writeLock) {
            entries.clear();
            snapshot = Snapshot.EMPTY;
        }
    }

    void remove(GameObject object) {
        if (object == null) {
            return;
        }

        synchronized (writeLock) {
            if (entries.remove(object) != null) {
                snapshot = rebuildSnapshot();
            }
        }
    }

    void sync(GameObject object) {
        if (object == null) {
            return;
        }

        synchronized (writeLock) {
            if (!inclusionPredicate.test(object)) {
                if (entries.remove(object) != null) {
                    snapshot = rebuildSnapshot();
                }

                return;
            }

            final CellRange range =
                    cellRange(object.getWorldAABB());

            if (range == null) {
                if (entries.remove(object) != null) {
                    snapshot = rebuildSnapshot();
                }

                return;
            }

            final Entry current =
                    entries.get(object);

            if (
                    current != null
                            && current.matches(range)
            ) {
                return;
            }

            entries.put(object, new Entry(range));
            snapshot = rebuildSnapshot();
        }
    }

    void syncBatch(SyncBuffer buffer) {
        if (buffer == null || buffer.size == 0) {
            return;
        }

        syncBatches(
                new SyncBuffer[]{buffer},
                1
        );
    }

    void syncBatches(
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

                    final Entry current =
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
                            isGiant(
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
                                new Entry(
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
                snapshot = rebuildSnapshot();
            }
        }
    }

    void queryXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        Objects.requireNonNull(output, "output");
        output.clear();

        if (!allFinite(minX, maxX, minZ, maxZ)) {
            return;
        }

        final double queryMinX =
                Math.min(minX, maxX);
        final double queryMaxX =
                Math.max(minX, maxX);
        final double queryMinZ =
                Math.min(minZ, maxZ);
        final double queryMaxZ =
                Math.max(minZ, maxZ);

        final Snapshot current = snapshot;

        final int minimumCellX =
                floorToInt(
                        queryMinX * inverseCellSize
                );
        final int maximumCellX =
                Math.max(
                        minimumCellX,
                        floorToInt(
                                (queryMaxX - EDGE_EPSILON)
                                        * inverseCellSize
                        )
                );
        final int minimumCellZ =
                floorToInt(
                        queryMinZ * inverseCellSize
                );
        final int maximumCellZ =
                Math.max(
                        minimumCellZ,
                        floorToInt(
                                (queryMaxZ - EDGE_EPSILON)
                                        * inverseCellSize
                        )
                );

        final long queryCellCount =
                cellCount(
                        minimumCellX,
                        maximumCellX,
                        minimumCellZ,
                        maximumCellZ
                );

        if (
                queryCellCount <= 0L
                        || queryCellCount
                        > MAX_CELLS_PER_QUERY
                        || queryCellCount
                        > Math.max(
                        64L,
                        (long) current.objects().size() * 2L
                )
        ) {
            scanAll(
                    current.objects(),
                    queryMinX,
                    queryMaxX,
                    queryMinZ,
                    queryMaxZ,
                    output
            );
            return;
        }

        final IdentitySet seen = visited.get();

        seen.begin();

        int cellX = minimumCellX;

        while (true) {
            int cellZ = minimumCellZ;

            while (true) {
                final GameObject[] bucket =
                        current.cells().get(
                                key(cellX, cellZ)
                        );

                if (bucket != null) {
                    for (
                            int i = 0;
                            i < bucket.length;
                            i++
                    ) {
                        final GameObject object =
                                bucket[i];

                        if (
                                object != null
                                        && seen.add(object)
                        ) {
                            output.add(object);
                        }
                    }
                }

                if (cellZ == maximumCellZ) {
                    break;
                }

                cellZ++;
            }

            if (cellX == maximumCellX) {
                break;
            }

            cellX++;
        }

        final List<GameObject> giants =
                current.giants();

        for (
                int i = 0, count = giants.size();
                i < count;
                i++
        ) {
            final GameObject object =
                    giants.get(i);

            if (object == null) {
                continue;
            }

            final AABB bounds =
                    object.getWorldAABB();

            if (
                    isValidBounds(bounds)
                            && overlapsXZ(
                            bounds,
                            queryMinX,
                            queryMaxX,
                            queryMinZ,
                            queryMaxZ
                    )
            ) {
                output.add(object);
            }
        }
    }

    private Snapshot rebuildSnapshot() {
        if (entries.isEmpty()) {
            return Snapshot.EMPTY;
        }

        final int objectCount = entries.size();

        final LongBucketBuilder mutableBuckets =
                new LongBucketBuilder(
                        mapCapacity(objectCount * 2L)
                );

        final ArrayList<GameObject> objects =
                new ArrayList<>(objectCount);
        final ArrayList<GameObject> giants =
                new ArrayList<>(
                        Math.min(64, objectCount)
                );

        for (
                Map.Entry<GameObject, Entry> mapEntry
                : entries.entrySet()
        ) {
            final GameObject object =
                    mapEntry.getKey();
            final Entry entry =
                    mapEntry.getValue();

            if (object == null || entry == null) {
                continue;
            }

            objects.add(object);

            if (entry.giant) {
                giants.add(object);
                continue;
            }

            int cellX = entry.minX;

            while (true) {
                int cellZ = entry.minZ;

                while (true) {
                    mutableBuckets.add(
                            key(cellX, cellZ),
                            object
                    );

                    if (cellZ == entry.maxZ) {
                        break;
                    }

                    cellZ++;
                }

                if (cellX == entry.maxX) {
                    break;
                }

                cellX++;
            }
        }

        return new Snapshot(
                mutableBuckets.freeze(),
                List.copyOf(objects),
                List.copyOf(giants)
        );
    }

    private static void scanAll(
            List<GameObject> objects,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        for (
                int i = 0, count = objects.size();
                i < count;
                i++
        ) {
            final GameObject object =
                    objects.get(i);

            if (object == null) {
                continue;
            }

            final AABB bounds =
                    object.getWorldAABB();

            if (
                    isValidBounds(bounds)
                            && overlapsXZ(
                            bounds,
                            minX,
                            maxX,
                            minZ,
                            maxZ
                    )
            ) {
                output.add(object);
            }
        }
    }

    private CellRange cellRange(AABB bounds) {
        if (!isValidBounds(bounds)) {
            return null;
        }

        return cellRange(
                bounds.minX,
                bounds.maxX,
                bounds.minZ,
                bounds.maxZ
        );
    }

    private CellRange cellRange(
            double minX,
            double maxX,
            double minZ,
            double maxZ
    ) {
        if (
                !allFinite(minX, maxX, minZ, maxZ)
                        || minX > maxX
                        || minZ > maxZ
        ) {
            return null;
        }

        return CellRange.of(
                floorToInt(minX * inverseCellSize),
                floorToInt(
                        (maxX - EDGE_EPSILON)
                                * inverseCellSize
                ),
                floorToInt(minZ * inverseCellSize),
                floorToInt(
                        (maxZ - EDGE_EPSILON)
                                * inverseCellSize
                )
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

    private static boolean isValidBounds(
            AABB bounds
    ) {
        return bounds != null
                && allFinite(
                bounds.minX,
                bounds.maxX,
                bounds.minZ,
                bounds.maxZ
        )
                && bounds.minX <= bounds.maxX
                && bounds.minZ <= bounds.maxZ;
    }

    private static boolean allFinite(
            double a,
            double b,
            double c,
            double d
    ) {
        return Double.isFinite(a)
                && Double.isFinite(b)
                && Double.isFinite(c)
                && Double.isFinite(d);
    }

    private static boolean overlapsXZ(
            AABB bounds,
            double minX,
            double maxX,
            double minZ,
            double maxZ
    ) {
        return bounds.maxX >= minX
                && bounds.minX <= maxX
                && bounds.maxZ >= minZ
                && bounds.minZ <= maxZ;
    }

    private static long key(
            int cellX,
            int cellZ
    ) {
        return ((long) cellX << 32)
                | (cellZ & 0xFFFF_FFFFL);
    }

    private static int mapCapacity(long expectedSize) {
        if (expectedSize <= 0L) {
            return 16;
        }

        final long capacity =
                (long) Math.ceil(
                        expectedSize / 0.75d
                );

        return (int) Math.min(
                1L << 20,
                Math.max(16L, capacity)
        );
    }

    private static long cellCount(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        final long spanX =
                (long) maxX - minX + 1L;
        final long spanZ =
                (long) maxZ - minZ + 1L;

        if (
                spanX <= 0L
                        || spanZ <= 0L
                        || spanX > Long.MAX_VALUE / spanZ
        ) {
            return Long.MAX_VALUE;
        }

        return spanX * spanZ;
    }

    private static boolean isGiant(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        final long count =
                cellCount(
                        minX,
                        maxX,
                        minZ,
                        maxZ
                );

        return count <= 0L
                || count > MAX_CELLS_PER_OBJECT;
    }

    private static final class Entry {

        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final boolean giant;

        private Entry(CellRange range) {
            this(
                    range.minX(),
                    range.maxX(),
                    range.minZ(),
                    range.maxZ(),
                    range.giant()
            );
        }

        private Entry(
                int minX,
                int maxX,
                int minZ,
                int maxZ,
                boolean giant
        ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.giant = giant;
        }

        private boolean matches(CellRange other) {
            return matches(
                    other.minX(),
                    other.maxX(),
                    other.minZ(),
                    other.maxZ(),
                    other.giant()
            );
        }

        private boolean matches(
                int otherMinX,
                int otherMaxX,
                int otherMinZ,
                int otherMaxZ,
                boolean otherGiant
        ) {
            return minX == otherMinX
                    && maxX == otherMaxX
                    && minZ == otherMinZ
                    && maxZ == otherMaxZ
                    && giant == otherGiant;
        }
    }

    private record CellRange(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            boolean giant
    ) {

        private static CellRange of(
                int minX,
                int maxX,
                int minZ,
                int maxZ
        ) {
            final int normalizedMaxX =
                    Math.max(minX, maxX);
            final int normalizedMaxZ =
                    Math.max(minZ, maxZ);

            final long count =
                    cellCount(
                            minX,
                            normalizedMaxX,
                            minZ,
                            normalizedMaxZ
                    );

            return new CellRange(
                    minX,
                    normalizedMaxX,
                    minZ,
                    normalizedMaxZ,
                    count <= 0L
                            || count
                            > MAX_CELLS_PER_OBJECT
            );
        }

        private long cellCount() {
            return cellCount(
                    minX,
                    maxX,
                    minZ,
                    maxZ
            );
        }

        private static long cellCount(
                int minX,
                int maxX,
                int minZ,
                int maxZ
        ) {
            return SpatialHashIndex.cellCount(
                    minX,
                    maxX,
                    minZ,
                    maxZ
            );
        }
    }

    static class SyncBuffer {

        GameObject[] objects = new GameObject[0];
        boolean[] include = new boolean[0];
        int[] minCellX = new int[0];
        int[] maxCellX = new int[0];
        int[] minCellZ = new int[0];
        int[] maxCellZ = new int[0];

        int size;

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

    private record Snapshot(
            LongBucketMap cells,
            List<GameObject> objects,
            List<GameObject> giants
    ) {

        private static final Snapshot EMPTY =
                new Snapshot(
                        LongBucketMap.EMPTY,
                        List.of(),
                        List.of()
                );
    }

    private static final class LongBucketBuilder {

        private long[] keys;
        private ArrayList<GameObject>[] values;
        private byte[] used;
        private int mask;
        private int size;
        private int resizeAt;

        @SuppressWarnings("unchecked")
        private LongBucketBuilder(int expectedSize) {
            int capacity = 16;

            final int required =
                    Math.max(2, expectedSize);

            while (
                    capacity < required
                            && capacity < 1 << 30
            ) {
                capacity <<= 1;
            }

            keys = new long[capacity];
            values =
                    (ArrayList<GameObject>[])
                            new ArrayList<?>[capacity];
            used = new byte[capacity];
            mask = capacity - 1;
            resizeAt =
                    capacity - (capacity >>> 2);
        }

        private void add(
                long key,
                GameObject object
        ) {
            int index =
                    LongBucketMap.mix(key) & mask;

            while (used[index] != 0) {
                if (keys[index] == key) {
                    values[index].add(object);
                    return;
                }

                index = (index + 1) & mask;
            }

            if (size >= resizeAt) {
                grow();
                add(key, object);
                return;
            }

            used[index] = 1;
            keys[index] = key;

            final ArrayList<GameObject> bucket =
                    new ArrayList<>(8);

            bucket.add(object);
            values[index] = bucket;
            size++;
        }

        @SuppressWarnings("unchecked")
        private void grow() {
            if (keys.length >= 1 << 30) {
                throw new IllegalStateException(
                        "Spatial hash contains too many cells"
                );
            }

            final long[] oldKeys = keys;
            final ArrayList<GameObject>[] oldValues =
                    values;
            final byte[] oldUsed = used;
            final int capacity =
                    oldKeys.length << 1;

            keys = new long[capacity];
            values =
                    (ArrayList<GameObject>[])
                            new ArrayList<?>[capacity];
            used = new byte[capacity];
            mask = capacity - 1;
            resizeAt =
                    capacity - (capacity >>> 2);

            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i] == 0) {
                    continue;
                }

                int index =
                        LongBucketMap.mix(oldKeys[i])
                                & mask;

                while (used[index] != 0) {
                    index = (index + 1) & mask;
                }

                used[index] = 1;
                keys[index] = oldKeys[i];
                values[index] = oldValues[i];
            }
        }

        private LongBucketMap freeze() {
            if (size == 0) {
                return LongBucketMap.EMPTY;
            }

            final GameObject[][] frozenValues =
                    new GameObject[values.length][];

            for (int i = 0; i < values.length; i++) {
                if (used[i] != 0) {
                    frozenValues[i] =
                            values[i].toArray(
                                    GameObject[]::new
                            );
                }
            }

            return new LongBucketMap(
                    keys,
                    frozenValues,
                    used,
                    mask
            );
        }
    }

    private static final class LongBucketMap {

        private static final LongBucketMap EMPTY =
                new LongBucketMap(
                        new long[1],
                        new GameObject[1][],
                        new byte[1],
                        0
                );

        private final long[] keys;
        private final GameObject[][] values;
        private final byte[] used;
        private final int mask;

        private LongBucketMap(
                long[] keys,
                GameObject[][] values,
                byte[] used,
                int mask
        ) {
            this.keys = keys;
            this.values = values;
            this.used = used;
            this.mask = mask;
        }

        private GameObject[] get(long key) {
            int index = mix(key) & mask;

            while (used[index] != 0) {
                if (keys[index] == key) {
                    return values[index];
                }

                index = (index + 1) & mask;
            }

            return null;
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;

            return (int) value;
        }
    }

    private static final class IdentitySet {

        private static final int INITIAL_CAPACITY =
                1_024;
        private static final int PHYSICAL_CLEAR_INTERVAL =
                256;

        private Object[] keys =
                new Object[INITIAL_CAPACITY];
        private int[] generations =
                new int[INITIAL_CAPACITY];

        private int mask =
                INITIAL_CAPACITY - 1;
        private int resizeAt =
                INITIAL_CAPACITY
                        - (INITIAL_CAPACITY >>> 2);

        private int generation;
        private int resetsSinceClear;
        private int size;

        private void begin() {
            size = 0;
            resetsSinceClear++;

            if (
                    generation == Integer.MAX_VALUE
                            || resetsSinceClear
                            >= PHYSICAL_CLEAR_INTERVAL
            ) {
                Arrays.fill(keys, null);
                Arrays.fill(generations, 0);

                generation = 1;
                resetsSinceClear = 0;
                return;
            }

            generation++;

            if (generation == 0) {
                generation = 1;
            }
        }

        private boolean add(GameObject object) {
            if (size >= resizeAt) {
                grow();
            }

            int index =
                    mixIdentity(
                            System.identityHashCode(object)
                    ) & mask;

            while (
                    generations[index] == generation
            ) {
                if (keys[index] == object) {
                    return false;
                }

                index = (index + 1) & mask;
            }

            keys[index] = object;
            generations[index] = generation;
            size++;

            return true;
        }

        private void grow() {
            if (keys.length >= 1 << 30) {
                throw new IllegalStateException(
                        "Spatial query contains too many unique objects"
                );
            }

            final Object[] oldKeys = keys;
            final int[] oldGenerations =
                    generations;
            final int oldGeneration =
                    generation;
            final int capacity =
                    oldKeys.length << 1;

            keys = new Object[capacity];
            generations = new int[capacity];
            mask = capacity - 1;
            resizeAt =
                    capacity - (capacity >>> 2);

            for (int i = 0; i < oldKeys.length; i++) {
                if (
                        oldGenerations[i]
                                != oldGeneration
                ) {
                    continue;
                }

                final Object key = oldKeys[i];

                int index =
                        mixIdentity(
                                System.identityHashCode(key)
                        ) & mask;

                while (
                        generations[index]
                                == generation
                ) {
                    index = (index + 1) & mask;
                }

                keys[index] = key;
                generations[index] = generation;
            }
        }

        private static int mixIdentity(int value) {
            value ^= value >>> 16;
            value *= 0x7FEB352D;
            value ^= value >>> 15;
            value *= 0x846CA68B;
            value ^= value >>> 16;

            return value;
        }
    }
}