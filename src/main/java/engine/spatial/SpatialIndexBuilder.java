package engine.spatial;

import engine.collections.LongBucketBuilder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import objects.GameObject;

final class SpatialIndexBuilder {

    static SpatialHashSnapshot rebuildSnapshot(
            IdentityHashMap<GameObject, SpatialEntry> entries
    ) {
        if (entries.isEmpty()) {
            return SpatialHashSnapshot.EMPTY;
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
                Map.Entry<GameObject, SpatialEntry> mapEntry
                : entries.entrySet()
        ) {
            final GameObject object =
                    mapEntry.getKey();
            final SpatialEntry entry =
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

        return new SpatialHashSnapshot(
                mutableBuckets.freeze(),
                List.copyOf(objects),
                List.copyOf(giants)
        );
    }

    static long key(
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
}