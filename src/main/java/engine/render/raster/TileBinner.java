package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import java.util.Arrays;

public final class TileBinner {
    /*
     * Tiny post-cull batches are faster with insertion sort. Larger batches
     * use a cache-friendly three-pass stable radix sort (11 + 11 + 10 bits).
     */
    private static final int INSERTION_SORT_THRESHOLD =
            96;

    private TileBinner() {
    }

    public static void bin(
            TriangleBuffer triangles,
            TileGrid grid,
            int width,
            int height
    ) {
        final int tileColumns =
                (
                        width +
                                RenderSettings.TILE_SIZE -
                                1
                ) >>
                        RenderSettings.TILE_SHIFT;

        final int tileRows =
                (
                        height +
                                RenderSettings.TILE_SIZE -
                                1
                ) >>
                        RenderSettings.TILE_SHIFT;

        final int tileCount =
                tileColumns *
                        tileRows;

        grid.ensureTiles(
                tileCount
        );

        sortTrianglesFrontToBack(
                triangles
        );

        final int[] counts =
                grid.counts;

        Arrays.fill(
                counts,
                0,
                tileCount,
                0
        );

        final int[] order =
                triangles.order;

        final int[] minimumX =
                triangles.minimumX;

        final int[] maximumX =
                triangles.maximumX;

        final int[] minimumY =
                triangles.minimumY;

        final int[] maximumY =
                triangles.maximumY;

        final int triangleCount =
                triangles.count;

        final int tileShift =
                RenderSettings.TILE_SHIFT;

        long referenceCount =
                0L;

        /*
         * First pass: count tile references.
         *
         * Triangle bounds have already been clipped to the framebuffer during
         * triangle construction, so no tile-coordinate clamping is required.
         */
        for (
                int position = 0;
                position < triangleCount;
                position++
        ) {
            final int triangle =
                    order[position];

            final int firstTileX =
                    minimumX[triangle] >>
                            tileShift;

            final int lastTileX =
                    maximumX[triangle] >>
                            tileShift;

            final int firstTileY =
                    minimumY[triangle] >>
                            tileShift;

            final int lastTileY =
                    maximumY[triangle] >>
                            tileShift;

            final int coveredColumns =
                    lastTileX -
                            firstTileX +
                            1;

            final int coveredRows =
                    lastTileY -
                            firstTileY +
                            1;

            referenceCount +=
                    (long) coveredColumns *
                            coveredRows;

            if (
                    referenceCount >
                            Integer.MAX_VALUE
            ) {
                throw new IllegalStateException(
                        "Too many tile-triangle references"
                );
            }

            int row =
                    firstTileY *
                            tileColumns;

            for (
                    int tileY = firstTileY;
                    tileY <= lastTileY;
                    tileY++,
                            row += tileColumns
            ) {
                final int firstTile =
                        row +
                                firstTileX;

                final int lastTile =
                        row +
                                lastTileX;

                for (
                        int tile = firstTile;
                        tile <= lastTile;
                        tile++
                ) {
                    counts[tile]++;
                }
            }
        }

        final int totalReferences =
                (int) referenceCount;

        grid.ensureTriangleReferences(
                totalReferences
        );

        final int[] offsets =
                grid.offsets;

        final int[] writePositions =
                grid.writePositions;

        int offset =
                0;

        /*
         * Prefix sum. Counts remain separately available for diagnostics and
         * scheduling decisions.
         */
        for (
                int tile = 0;
                tile < tileCount;
                tile++
        ) {
            offsets[tile] =
                    offset;

            writePositions[tile] =
                    offset;

            offset +=
                    counts[tile];
        }

        offsets[tileCount] =
                offset;

        final int[] triangleIndices =
                grid.triangleIndices;

        /*
         * Second pass: emit references.
         *
         * The global triangle order is already front-to-back. Each tile
         * therefore receives front-to-back triangle references without
         * requiring a separate per-tile sort.
         */
        for (
                int position = 0;
                position < triangleCount;
                position++
        ) {
            final int triangle =
                    order[position];

            final int firstTileX =
                    minimumX[triangle] >>
                            tileShift;

            final int lastTileX =
                    maximumX[triangle] >>
                            tileShift;

            final int firstTileY =
                    minimumY[triangle] >>
                            tileShift;

            final int lastTileY =
                    maximumY[triangle] >>
                            tileShift;

            int row =
                    firstTileY *
                            tileColumns;

            for (
                    int tileY = firstTileY;
                    tileY <= lastTileY;
                    tileY++,
                            row += tileColumns
            ) {
                final int firstTile =
                        row +
                                firstTileX;

                final int lastTile =
                        row +
                                lastTileX;

                for (
                        int tile = firstTile;
                        tile <= lastTile;
                        tile++
                ) {
                    triangleIndices[
                            writePositions[tile]++
                            ] =
                            triangle;
                }
            }
        }
    }

    private static void sortTrianglesFrontToBack(
            TriangleBuffer triangles
    ) {
        final int count =
                triangles.count;

        if (count <= 0) {
            return;
        }

        triangles.ensureSortCapacity(
                count
        );

        final int[] keys =
                triangles.sortKeys;

        final int[] order =
                triangles.order;

        final float[] averageInverseZ =
                triangles.averageInverseZ;

        final int[] physicalIndices =
                triangles.physicalIndices;

        /*
         * Build an integer key for descending average inverse-Z.
         *
         * Visible inverse-Z values are positive. Their raw IEEE-754 bit
         * patterns have the same order as their numeric values. Complementing
         * the bits converts nearest-first descending order into ascending
         * integer-key order for the radix sorter.
         */
        for (
                int position = 0;
                position < count;
                position++
        ) {
            final int triangle =
                    physicalIndices[position];

            final float depth =
                    averageInverseZ[triangle];

            keys[position] =
                    ~Float.floatToRawIntBits(
                            depth
                    );

            order[position] =
                    triangle;
        }

        if (
                count <=
                        INSERTION_SORT_THRESHOLD
        ) {
            insertionSort(
                    keys,
                    order,
                    count
            );

            return;
        }

        radixSort11Bit(
                triangles,
                count
        );
    }

    /*
     * Stable insertion sort for tiny batches.
     *
     * This avoids histogram setup when only a small number of triangles remain
     * after clipping and culling.
     */
    private static void insertionSort(
            int[] keys,
            int[] order,
            int count
    ) {
        for (
                int position = 1;
                position < count;
                position++
        ) {
            final int key =
                    keys[position];

            final int triangle =
                    order[position];

            int scan =
                    position -
                            1;

            while (
                    scan >= 0 &&
                            keys[scan] >
                                    key
            ) {
                keys[scan + 1] =
                        keys[scan];

                order[scan + 1] =
                        order[scan];

                scan--;
            }

            keys[scan + 1] =
                    key;

            order[scan + 1] =
                    triangle;
        }
    }

    /*
     * Three-pass stable LSD radix sort:
     *
     * pass 1: low 11 bits
     * pass 2: middle 11 bits
     * pass 3: high 10 bits
     *
     * The histogram occupies 8 KiB instead of 256 KiB, reducing cache
     * pollution and histogram clearing/prefix-sum overhead. Array references
     * are swapped after each pass, so no final full-array copy is required.
     */
    private static void radixSort11Bit(
            TriangleBuffer triangles,
            int count
    ) {
        int[] sourceKeys =
                triangles.sortKeys;

        int[] sourceOrder =
                triangles.order;

        int[] destinationKeys =
                triangles.temporarySortKeys;

        int[] destinationOrder =
                triangles.temporaryOrder;

        final int[] histogram =
                triangles.radixHistogram;

        radixPass(
                sourceKeys,
                sourceOrder,
                destinationKeys,
                destinationOrder,
                histogram,
                count,
                0,
                TriangleBuffer.RADIX_MASK,
                TriangleBuffer.RADIX_SIZE
        );

        int[] swap =
                sourceKeys;

        sourceKeys =
                destinationKeys;

        destinationKeys =
                swap;

        swap =
                sourceOrder;

        sourceOrder =
                destinationOrder;

        destinationOrder =
                swap;

        radixPass(
                sourceKeys,
                sourceOrder,
                destinationKeys,
                destinationOrder,
                histogram,
                count,
                TriangleBuffer.RADIX_BITS,
                TriangleBuffer.RADIX_MASK,
                TriangleBuffer.RADIX_SIZE
        );

        swap =
                sourceKeys;

        sourceKeys =
                destinationKeys;

        destinationKeys =
                swap;

        swap =
                sourceOrder;

        sourceOrder =
                destinationOrder;

        destinationOrder =
                swap;

        radixPass(
                sourceKeys,
                sourceOrder,
                destinationKeys,
                destinationOrder,
                histogram,
                count,
                TriangleBuffer.RADIX_BITS << 1,
                TriangleBuffer.RADIX_FINAL_MASK,
                TriangleBuffer.RADIX_FINAL_SIZE
        );

        /*
         * The final pass wrote into the destination arrays. Publish those
         * arrays by swapping reusable references instead of copying count
         * elements back into the original arrays.
         */
        triangles.sortKeys =
                destinationKeys;

        triangles.order =
                destinationOrder;

        triangles.temporarySortKeys =
                sourceKeys;

        triangles.temporaryOrder =
                sourceOrder;
    }

    private static void radixPass(
            int[] sourceKeys,
            int[] sourceOrder,
            int[] destinationKeys,
            int[] destinationOrder,
            int[] histogram,
            int count,
            int shift,
            int mask,
            int bucketCount
    ) {
        Arrays.fill(
                histogram,
                0,
                bucketCount,
                0
        );

        /*
         * Histogram.
         */
        for (
                int position = 0;
                position < count;
                position++
        ) {
            histogram[
                    (
                            sourceKeys[position] >>>
                                    shift
                    ) &
                            mask
                    ]++;
        }

        /*
         * Convert counts to stable write offsets.
         */
        int offset =
                0;

        for (
                int bucket = 0;
                bucket < bucketCount;
                bucket++
        ) {
            final int bucketSize =
                    histogram[bucket];

            histogram[bucket] =
                    offset;

            offset +=
                    bucketSize;
        }

        /*
         * Stable scatter.
         */
        for (
                int position = 0;
                position < count;
                position++
        ) {
            final int key =
                    sourceKeys[position];

            final int bucket =
                    (
                            key >>>
                                    shift
                    ) &
                            mask;

            final int destination =
                    histogram[bucket]++;

            destinationKeys[destination] =
                    key;

            destinationOrder[destination] =
                    sourceOrder[position];
        }
    }
}