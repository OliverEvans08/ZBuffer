package engine.spatial;

import engine.collections.IdentitySet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import objects.GameObject;
import util.AABB;

final class SpatialQuery {

    private static final double EDGE_EPSILON = 1.0e-9;
    private static final long MAX_CELLS_PER_QUERY = 262_144L;

    static void queryXZ(
            SpatialHashSnapshot current,
            double inverseCellSize,
            ThreadLocal<IdentitySet> visited,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        Objects.requireNonNull(output, "output");
        output.clear();

        if (
                !CellRange.allFinite(
                        minX,
                        maxX,
                        minZ,
                        maxZ
                )
        ) {
            return;
        }

        final double queryMinX =
                Math.min(minX, maxX);
        final double queryMaxX =
                Math.max(minX, maxX);
        final double queryMinZ =
                Math.min(minZ, maxZ);
        final double queryMaxZ =
                Math.max(minZ, maxZ);

        final int minimumCellX =
                CellRange.floorToInt(
                        queryMinX * inverseCellSize
                );
        final int maximumCellX =
                Math.max(
                        minimumCellX,
                        CellRange.floorToInt(
                                (queryMaxX - EDGE_EPSILON)
                                        * inverseCellSize
                        )
                );
        final int minimumCellZ =
                CellRange.floorToInt(
                        queryMinZ * inverseCellSize
                );
        final int maximumCellZ =
                Math.max(
                        minimumCellZ,
                        CellRange.floorToInt(
                                (queryMaxZ - EDGE_EPSILON)
                                        * inverseCellSize
                        )
                );

        final long queryCellCount =
                CellRange.cellCount(
                        minimumCellX,
                        maximumCellX,
                        minimumCellZ,
                        maximumCellZ
                );

        if (
                queryCellCount <= 0L
                        || queryCellCount
                        > MAX_CELLS_PER_QUERY
                        || queryCellCount
                        > Math.max(
                        64L,
                        (long) current.objects().size() * 2L
                )
        ) {
            scanAll(
                    current.objects(),
                    queryMinX,
                    queryMaxX,
                    queryMinZ,
                    queryMaxZ,
                    output
            );
            return;
        }

        final IdentitySet seen = visited.get();

        seen.begin();

        int cellX = minimumCellX;

        while (true) {
            int cellZ = minimumCellZ;

            while (true) {
                final GameObject[] bucket =
                        current.cells().get(
                                SpatialIndexBuilder.key(
                                        cellX,
                                        cellZ
                                )
                        );

                if (bucket != null) {
                    for (
                            int i = 0;
                            i < bucket.length;
                            i++
                    ) {
                        final GameObject object =
                                bucket[i];

                        if (
                                object != null
                                        && seen.add(object)
                        ) {
                            output.add(object);
                        }
                    }
                }

                if (cellZ == maximumCellZ) {
                    break;
                }

                cellZ++;
            }

            if (cellX == maximumCellX) {
                break;
            }

            cellX++;
        }

        final List<GameObject> giants =
                current.giants();

        for (
                int i = 0, count = giants.size();
                i < count;
                i++
        ) {
            final GameObject object =
                    giants.get(i);

            if (object == null) {
                continue;
            }

            final AABB bounds =
                    object.getWorldAABB();

            if (
                    CellRange.isValidBounds(bounds)
                            && CellRange.overlapsXZ(
                            bounds,
                            queryMinX,
                            queryMaxX,
                            queryMinZ,
                            queryMaxZ
                    )
            ) {
                output.add(object);
            }
        }
    }

    private static void scanAll(
            List<GameObject> objects,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        for (
                int i = 0, count = objects.size();
                i < count;
                i++
        ) {
            final GameObject object =
                    objects.get(i);

            if (object == null) {
                continue;
            }

            final AABB bounds =
                    object.getWorldAABB();

            if (
                    CellRange.isValidBounds(bounds)
                            && CellRange.overlapsXZ(
                            bounds,
                            minX,
                            maxX,
                            minZ,
                            maxZ
                    )
            ) {
                output.add(object);
            }
        }
    }
}