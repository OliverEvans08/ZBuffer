package engine.collections;

import java.util.ArrayList;
import objects.GameObject;

public final class LongBucketBuilder {

    private long[] keys;
    private ArrayList<GameObject>[] values;
    private byte[] used;
    private int mask;
    private int size;
    private int resizeAt;

    @SuppressWarnings("unchecked")
    public LongBucketBuilder(int expectedSize) {
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

    public void add(
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

    public LongBucketMap freeze() {
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