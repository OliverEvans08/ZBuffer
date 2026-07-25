package engine.render.geometry;

import engine.render.lighting.MaterialState;
import java.util.Arrays;

public final class TriangleBuffer {
    public int count;

    public double[] x0 = new double[0];
    public double[] y0 = new double[0];
    public double[] x1 = new double[0];
    public double[] y1 = new double[0];
    public double[] x2 = new double[0];
    public double[] y2 = new double[0];
    public float[] inverseZ0 = new float[0];
    public float[] inverseZ1 = new float[0];
    public float[] inverseZ2 = new float[0];
    public float[] uOverZ0 = new float[0];
    public float[] vOverZ0 = new float[0];
    public float[] uOverZ1 = new float[0];
    public float[] vOverZ1 = new float[0];
    public float[] uOverZ2 = new float[0];
    public float[] vOverZ2 = new float[0];
    public MaterialState[] materials = new MaterialState[0];
    public int[] shades = new int[0];
    public int[] minimumX = new int[0];
    public int[] maximumX = new int[0];
    public int[] minimumY = new int[0];
    public int[] maximumY = new int[0];
    public long[] sortKeys = new long[0];
    public int[] order = new int[0];
    public int[] physicalIndices = new int[0];

    public void ensureCapacity(int required) {
        if (x0.length >= required) {
            return;
        }

        int capacity = x0.length == 0 ? 2048 : x0.length;

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }

        x0 = Arrays.copyOf(x0, capacity);
        y0 = Arrays.copyOf(y0, capacity);
        x1 = Arrays.copyOf(x1, capacity);
        y1 = Arrays.copyOf(y1, capacity);
        x2 = Arrays.copyOf(x2, capacity);
        y2 = Arrays.copyOf(y2, capacity);
        inverseZ0 = Arrays.copyOf(inverseZ0, capacity);
        inverseZ1 = Arrays.copyOf(inverseZ1, capacity);
        inverseZ2 = Arrays.copyOf(inverseZ2, capacity);
        uOverZ0 = Arrays.copyOf(uOverZ0, capacity);
        vOverZ0 = Arrays.copyOf(vOverZ0, capacity);
        uOverZ1 = Arrays.copyOf(uOverZ1, capacity);
        vOverZ1 = Arrays.copyOf(vOverZ1, capacity);
        uOverZ2 = Arrays.copyOf(uOverZ2, capacity);
        vOverZ2 = Arrays.copyOf(vOverZ2, capacity);
        materials = Arrays.copyOf(materials, capacity);
        shades = Arrays.copyOf(shades, capacity);
        minimumX = Arrays.copyOf(minimumX, capacity);
        maximumX = Arrays.copyOf(maximumX, capacity);
        minimumY = Arrays.copyOf(minimumY, capacity);
        maximumY = Arrays.copyOf(maximumY, capacity);
    }

    public void ensureSortCapacity(int required) {
        if (sortKeys.length < required) {
            final int capacity =
                    growCapacity(
                            sortKeys.length,
                            required,
                            2048
                    );
            sortKeys = new long[capacity];
            order = new int[capacity];
        }
    }

    public void ensurePhysicalIndexCapacity(int required) {
        if (physicalIndices.length < required) {
            physicalIndices = new int[
                    growCapacity(
                            physicalIndices.length,
                            required,
                            2048
                    )
                    ];
        }
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity = Math.max(minimum, current);

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity <<= 1;
        }

        return capacity;
    }
}