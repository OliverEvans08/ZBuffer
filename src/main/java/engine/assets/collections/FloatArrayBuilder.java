package engine.assets.collections;

import java.util.Arrays;

/**
 * Flat growable primitive float buffer used during offline compilation.
 */
public final class FloatArrayBuilder {

    private float[] values;
    private int size;

    public FloatArrayBuilder(
            int initialCapacity
    ) {
        values =
                new float[
                        Math.max(
                                16,
                                initialCapacity
                        )
                        ];
    }

    public void add(
            float value
    ) {
        ensureCapacity(size + 1);
        values[size++] = value;
    }

    public float get(
            int index
    ) {
        if (
                index < 0
                        || index >= size
        ) {
            throw new IndexOutOfBoundsException(
                    index
            );
        }

        return values[index];
    }

    public int size() {
        return size;
    }

    public float[] toArray() {
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