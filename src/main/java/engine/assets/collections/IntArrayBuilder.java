package engine.assets.collections;

import java.util.Arrays;

/**
 * Flat growable primitive integer buffer used during offline compilation.
 */
public final class IntArrayBuilder {

    private int[] values;
    private int size;

    public IntArrayBuilder(
            int initialCapacity
    ) {
        values =
                new int[
                        Math.max(
                                16,
                                initialCapacity
                        )
                        ];
    }

    public void add(
            int value
    ) {
        ensureCapacity(size + 1);
        values[size++] = value;
    }

    public void set(
            int index,
            int value
    ) {
        values[index] = value;
    }

    public int size() {
        return size;
    }

    public int[] toArray() {
        return Arrays.copyOf(
                values,
                size
        );
    }

    private void ensureCapacity(
            int required
    ) {
        if (required <= values.length) {
            return;
        }

        int capacity =
                values.length;

        while (capacity < required) {
            capacity =
                    Math.max(
                            capacity + 1,
                            capacity
                                    + (
                                    capacity >>> 1
                            )
                    );
        }

        values =
                Arrays.copyOf(
                        values,
                        capacity
                );
    }
}