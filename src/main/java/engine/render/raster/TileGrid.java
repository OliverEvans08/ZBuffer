package engine.render.raster;

import engine.render.util.IntList;
import java.util.Arrays;

public final class TileGrid {
    public IntList[] bins = new IntList[0];
    public float[] depthMinimum = new float[0];

    public void ensure(int requiredTiles) {
        if (bins.length < requiredTiles) {
            final IntList[] expanded =
                    Arrays.copyOf(bins, requiredTiles);

            for (
                    int tile = bins.length;
                    tile < requiredTiles;
                    tile++
            ) {
                expanded[tile] = new IntList(64);
            }

            bins = expanded;
        }

        if (depthMinimum.length < requiredTiles) {
            depthMinimum = new float[
                    growCapacity(
                            depthMinimum.length,
                            requiredTiles,
                            64
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