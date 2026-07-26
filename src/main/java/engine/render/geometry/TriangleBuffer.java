package engine.render.geometry;

import engine.render.lighting.MaterialState;
import java.util.Arrays;

public final class TriangleBuffer {
    /*
     * The front-to-back sorter uses three stable radix passes: 11, 11 and 10
     * bits. A 2,048-entry histogram is only 8 KiB, remains cache-friendly, and
     * avoids clearing/traversing the former 65,536-entry (256 KiB) histogram.
     */
    public static final int RADIX_BITS = 11;
    public static final int RADIX_SIZE = 1 << RADIX_BITS;
    public static final int RADIX_MASK = RADIX_SIZE - 1;

    public static final int RADIX_FINAL_BITS =
            Integer.SIZE - (RADIX_BITS << 1);

    public static final int RADIX_FINAL_SIZE =
            1 << RADIX_FINAL_BITS;

    public static final int RADIX_FINAL_MASK =
            RADIX_FINAL_SIZE - 1;

    public int count;

    public double[] x0 = new double[0];
    public double[] y0 = new double[0];

    public double[] x1 = new double[0];
    public double[] y1 = new double[0];

    public double[] x2 = new double[0];
    public double[] y2 = new double[0];

    /*
     * Fixed-point edge equations are prepared once when a triangle enters the
     * batch. A triangle may be referenced by many tiles; keeping the setup
     * here prevents every tile from repeating six rounds, three cross
     * products, edge-step construction and interpolation-plane construction.
     */
    public long[] edge0A = new long[0];
    public long[] edge0B = new long[0];
    public long[] edge0C = new long[0];

    public long[] edge1A = new long[0];
    public long[] edge1B = new long[0];
    public long[] edge1C = new long[0];

    public long[] edge2A = new long[0];
    public long[] edge2B = new long[0];
    public long[] edge2C = new long[0];

    public float[] inverseZ0 = new float[0];
    public float[] inverseZ1 = new float[0];
    public float[] inverseZ2 = new float[0];

    public float[] maximumInverseZ = new float[0];
    public float[] averageInverseZ = new float[0];

    public float[] uOverZ0 = new float[0];
    public float[] vOverZ0 = new float[0];

    public float[] uOverZ1 = new float[0];
    public float[] vOverZ1 = new float[0];

    public float[] uOverZ2 = new float[0];
    public float[] vOverZ2 = new float[0];

    /*
     * Attribute planes at pixel centre (0.5, 0.5), plus one-pixel X/Y
     * increments. Raster tiles can jump directly to their first pixel using
     * origin + x * stepX + y * stepY.
     */
    public double[] inverseZOrigin = new double[0];
    public double[] inverseZStepX = new double[0];
    public double[] inverseZStepY = new double[0];

    public double[] uOverZOrigin = new double[0];
    public double[] uOverZStepX = new double[0];
    public double[] uOverZStepY = new double[0];

    public double[] vOverZOrigin = new double[0];
    public double[] vOverZStepX = new double[0];
    public double[] vOverZStepY = new double[0];

    public double[] normalX = new double[0];
    public double[] normalY = new double[0];
    public double[] normalZ = new double[0];

    public byte[] doubleSided = new byte[0];

    /*
     * Dynamic lights are represented as a compact per-triangle bit mask.
     * Invariant directional light is accumulated once during triangle setup.
     */
    public int[] lightMasks = new int[0];
    public float[] baseLightRed = new float[0];
    public float[] baseLightGreen = new float[0];
    public float[] baseLightBlue = new float[0];

    public MaterialState[] materials =
            new MaterialState[0];

    public int[] shades = new int[0];

    public int[] minimumX = new int[0];
    public int[] maximumX = new int[0];
    public int[] minimumY = new int[0];
    public int[] maximumY = new int[0];

    /*
     * Front-to-back ordering buffers.
     *
     * sortKeys contains an unsigned-sortable descending depth key.
     * The sorter swaps these reusable array references between passes, so no
     * final full-array copy is required regardless of which buffer owns the
     * sorted result.
     */
    public int[] sortKeys = new int[0];
    public int[] temporarySortKeys = new int[0];

    public int[] order = new int[0];
    public int[] temporaryOrder = new int[0];

    /*
     * The histogram is allocated lazily. At 2,048 entries it occupies about
     * 8 KiB instead of the previous 256 KiB.
     */
    public int[] radixHistogram = new int[0];

    /*
     * Maps the compact logical triangle sequence to the physical ranges
     * reserved for triangle-build workers.
     */
    public int[] physicalIndices = new int[0];

    public void ensureCapacity(int required) {
        if (x0.length >= required) {
            return;
        }

        final int capacity =
                growCapacity(
                        x0.length,
                        required,
                        2_048
                );

        x0 = Arrays.copyOf(x0, capacity);
        y0 = Arrays.copyOf(y0, capacity);

        x1 = Arrays.copyOf(x1, capacity);
        y1 = Arrays.copyOf(y1, capacity);

        x2 = Arrays.copyOf(x2, capacity);
        y2 = Arrays.copyOf(y2, capacity);

        edge0A = Arrays.copyOf(edge0A, capacity);
        edge0B = Arrays.copyOf(edge0B, capacity);
        edge0C = Arrays.copyOf(edge0C, capacity);

        edge1A = Arrays.copyOf(edge1A, capacity);
        edge1B = Arrays.copyOf(edge1B, capacity);
        edge1C = Arrays.copyOf(edge1C, capacity);

        edge2A = Arrays.copyOf(edge2A, capacity);
        edge2B = Arrays.copyOf(edge2B, capacity);
        edge2C = Arrays.copyOf(edge2C, capacity);

        inverseZ0 = Arrays.copyOf(inverseZ0, capacity);
        inverseZ1 = Arrays.copyOf(inverseZ1, capacity);
        inverseZ2 = Arrays.copyOf(inverseZ2, capacity);

        maximumInverseZ =
                Arrays.copyOf(
                        maximumInverseZ,
                        capacity
                );

        averageInverseZ =
                Arrays.copyOf(
                        averageInverseZ,
                        capacity
                );

        uOverZ0 = Arrays.copyOf(uOverZ0, capacity);
        vOverZ0 = Arrays.copyOf(vOverZ0, capacity);

        uOverZ1 = Arrays.copyOf(uOverZ1, capacity);
        vOverZ1 = Arrays.copyOf(vOverZ1, capacity);

        uOverZ2 = Arrays.copyOf(uOverZ2, capacity);
        vOverZ2 = Arrays.copyOf(vOverZ2, capacity);

        inverseZOrigin =
                Arrays.copyOf(
                        inverseZOrigin,
                        capacity
                );

        inverseZStepX =
                Arrays.copyOf(
                        inverseZStepX,
                        capacity
                );

        inverseZStepY =
                Arrays.copyOf(
                        inverseZStepY,
                        capacity
                );

        uOverZOrigin =
                Arrays.copyOf(
                        uOverZOrigin,
                        capacity
                );

        uOverZStepX =
                Arrays.copyOf(
                        uOverZStepX,
                        capacity
                );

        uOverZStepY =
                Arrays.copyOf(
                        uOverZStepY,
                        capacity
                );

        vOverZOrigin =
                Arrays.copyOf(
                        vOverZOrigin,
                        capacity
                );

        vOverZStepX =
                Arrays.copyOf(
                        vOverZStepX,
                        capacity
                );

        vOverZStepY =
                Arrays.copyOf(
                        vOverZStepY,
                        capacity
                );

        normalX = Arrays.copyOf(normalX, capacity);
        normalY = Arrays.copyOf(normalY, capacity);
        normalZ = Arrays.copyOf(normalZ, capacity);

        doubleSided =
                Arrays.copyOf(
                        doubleSided,
                        capacity
                );

        lightMasks =
                Arrays.copyOf(
                        lightMasks,
                        capacity
                );

        baseLightRed =
                Arrays.copyOf(
                        baseLightRed,
                        capacity
                );

        baseLightGreen =
                Arrays.copyOf(
                        baseLightGreen,
                        capacity
                );

        baseLightBlue =
                Arrays.copyOf(
                        baseLightBlue,
                        capacity
                );

        materials =
                Arrays.copyOf(
                        materials,
                        capacity
                );

        shades =
                Arrays.copyOf(
                        shades,
                        capacity
                );

        minimumX =
                Arrays.copyOf(
                        minimumX,
                        capacity
                );

        maximumX =
                Arrays.copyOf(
                        maximumX,
                        capacity
                );

        minimumY =
                Arrays.copyOf(
                        minimumY,
                        capacity
                );

        maximumY =
                Arrays.copyOf(
                        maximumY,
                        capacity
                );
    }

    public void ensureSortCapacity(int required) {
        if (sortKeys.length < required) {
            final int capacity =
                    growCapacity(
                            sortKeys.length,
                            required,
                            2_048
                    );

            sortKeys =
                    new int[capacity];

            temporarySortKeys =
                    new int[capacity];

            order =
                    new int[capacity];

            temporaryOrder =
                    new int[capacity];
        }

        if (radixHistogram.length != RADIX_SIZE) {
            radixHistogram =
                    new int[RADIX_SIZE];
        }
    }

    public void ensurePhysicalIndexCapacity(
            int required
    ) {
        if (physicalIndices.length >= required) {
            return;
        }

        physicalIndices =
                new int[
                        growCapacity(
                                physicalIndices.length,
                                required,
                                2_048
                        )
                        ];
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity =
                Math.max(
                        minimum,
                        current
                );

        while (capacity < required) {
            if (
                    capacity >
                            Integer.MAX_VALUE >>>
                                    1
            ) {
                return required;
            }

            capacity <<=
                    1;
        }

        return capacity;
    }
}