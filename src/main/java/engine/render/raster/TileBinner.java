package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import engine.render.util.IntList;
import java.util.Arrays;

public final class TileBinner {
    private TileBinner() {
    }

    public static void bin(
            TriangleBuffer triangles,
            TileGrid grid,
            int width,
            int height
    ) {
        final int tileColumns =
                (width + RenderSettings.TILE_SIZE - 1) >>
                        RenderSettings.TILE_SHIFT;
        final int tileRows =
                (height + RenderSettings.TILE_SIZE - 1) >>
                        RenderSettings.TILE_SHIFT;
        final int tileCount = tileColumns * tileRows;

        grid.ensure(tileCount);
        sortTrianglesFrontToBack(triangles);

        final IntList[] bins = grid.bins;

        for (int tile = 0; tile < tileCount; tile++) {
            bins[tile].clear();
            grid.depthMinimum[tile] =
                    Float.NEGATIVE_INFINITY;
        }

        final int[] order = triangles.order;
        final int[] minimumX = triangles.minimumX;
        final int[] maximumX = triangles.maximumX;
        final int[] minimumY = triangles.minimumY;
        final int[] maximumY = triangles.maximumY;

        for (
                int position = 0;
                position < triangles.count;
                position++
        ) {
            final int triangle = order[position];
            final int firstTileX =
                    minimumX[triangle] >>
                            RenderSettings.TILE_SHIFT;
            final int lastTileX =
                    maximumX[triangle] >>
                            RenderSettings.TILE_SHIFT;
            final int firstTileY =
                    minimumY[triangle] >>
                            RenderSettings.TILE_SHIFT;
            final int lastTileY =
                    maximumY[triangle] >>
                            RenderSettings.TILE_SHIFT;

            for (
                    int tileY = firstTileY;
                    tileY <= lastTileY;
                    tileY++
            ) {
                final int row = tileY * tileColumns;

                for (
                        int tileX = firstTileX;
                        tileX <= lastTileX;
                        tileX++
                ) {
                    bins[row + tileX].add(triangle);
                }
            }
        }
    }

    private static void sortTrianglesFrontToBack(
            TriangleBuffer triangles
    ) {
        final int count = triangles.count;
        triangles.ensureSortCapacity(count);

        final long[] keys = triangles.sortKeys;
        final float[] inverseZ0 = triangles.inverseZ0;
        final float[] inverseZ1 = triangles.inverseZ1;
        final float[] inverseZ2 = triangles.inverseZ2;
        final int[] physicalIndices =
                triangles.physicalIndices;

        for (int position = 0; position < count; position++) {
            final int triangle =
                    physicalIndices[position];
            final float depth =
                    (inverseZ0[triangle] +
                            inverseZ1[triangle] +
                            inverseZ2[triangle]) *
                            (1.0f / 3.0f);
            final int depthBits =
                    Float.floatToRawIntBits(depth);
            final long descendingDepth =
                    Integer.MAX_VALUE - (long) depthBits;

            keys[position] =
                    (descendingDepth << 32) |
                            (triangle & 0xFFFFFFFFL);
        }

        Arrays.sort(keys, 0, count);

        final int[] order = triangles.order;

        for (int position = 0; position < count; position++) {
            order[position] = (int) keys[position];
        }
    }
}