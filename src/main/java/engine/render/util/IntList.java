package engine.render.util;

import java.util.Arrays;

public final class IntList {
    public int[] values;
    public int size;

    public IntList(int initialCapacity) {
        values = new int[
                Math.max(16, initialCapacity)
                ];
    }

    public void clear() {
        size = 0;
    }

    public void add(int value) {
        if (size == values.length) {
            values = Arrays.copyOf(
                    values,
                    values.length << 1
            );
        }

        values[size++] = value;
    }
}