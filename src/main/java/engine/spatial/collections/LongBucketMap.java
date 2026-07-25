package engine.collections;

import objects.GameObject;

public final class LongBucketMap {

    public static final LongBucketMap EMPTY =
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

    LongBucketMap(
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

    public GameObject[] get(long key) {
        int index = mix(key) & mask;

        while (used[index] != 0) {
            if (keys[index] == key) {
                return values[index];
            }

            index = (index + 1) & mask;
        }

        return null;
    }

    static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;

        return (int) value;
    }
}