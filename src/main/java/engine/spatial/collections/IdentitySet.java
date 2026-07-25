package engine.collections;

import java.util.Arrays;
import objects.GameObject;

public final class IdentitySet {

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

    public void begin() {
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

    public boolean add(GameObject object) {
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