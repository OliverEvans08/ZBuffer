package engine.render.raster;

import java.util.Arrays;

public final class TileGrid {
    public int[] triangleIndices = new int[0];
    public int[] offsets = new int[1];
    public int[] counts = new int[0];
    public int[] writePositions = new int[0];
    public float[] depthMinimum = new float[0];

    public void ensureTiles(int requiredTiles) {
        if (counts.length >= requiredTiles) {
            return;
        }

        final int capacity = growCapacity(
                counts.length,
                requiredTiles,
                64
        );

        counts = Arrays.copyOf(
                counts,
                capacity
        );

        writePositions = Arrays.copyOf(
                writePositions,
                capacity
        );

        depthMinimum = Arrays.copyOf(
                depthMinimum,
                capacity
        );

        offsets = Arrays.copyOf(
                offsets,
                capacity + 1
        );
    }

    public void ensureTriangleReferences(
            int requiredReferences
    ) {
        if (triangleIndices.length >= requiredReferences) {
            return;
        }

        triangleIndices = Arrays.copyOf(
                triangleIndices,
                growCapacity(
                        triangleIndices.length,
                        requiredReferences,
                        1_024
                )
        );
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity = Math.max(
                minimum,
                current
        );

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }

            capacity <<= 1;
        }

        return capacity;
    }
}