package engine.spatial;

import util.AABB;

record CellRange(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        boolean giant
) {

    private static final double EDGE_EPSILON = 1.0e-9;
    private static final long MAX_CELLS_PER_OBJECT = 65_536L;

    static CellRange of(
            AABB bounds,
            double inverseCellSize
    ) {
        if (!isValidBounds(bounds)) {
            return null;
        }

        return of(
                bounds.minX,
                bounds.maxX,
                bounds.minZ,
                bounds.maxZ,
                inverseCellSize
        );
    }

    static CellRange of(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            double inverseCellSize
    ) {
        if (
                !allFinite(minX, maxX, minZ, maxZ)
                        || minX > maxX
                        || minZ > maxZ
        ) {
            return null;
        }

        return of(
                floorToInt(minX * inverseCellSize),
                floorToInt(
                        (maxX - EDGE_EPSILON)
                                * inverseCellSize
                ),
                floorToInt(minZ * inverseCellSize),
                floorToInt(
                        (maxZ - EDGE_EPSILON)
                                * inverseCellSize
                )
        );
    }

    static CellRange of(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        final int normalizedMaxX =
                Math.max(minX, maxX);
        final int normalizedMaxZ =
                Math.max(minZ, maxZ);

        final long count =
                cellCount(
                        minX,
                        normalizedMaxX,
                        minZ,
                        normalizedMaxZ
                );

        return new CellRange(
                minX,
                normalizedMaxX,
                minZ,
                normalizedMaxZ,
                count <= 0L
                        || count
                        > MAX_CELLS_PER_OBJECT
        );
    }

    long cellCount() {
        return cellCount(
                minX,
                maxX,
                minZ,
                maxZ
        );
    }

    static int floorToInt(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }

        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) Math.floor(value);
    }

    static boolean isValidBounds(
            AABB bounds
    ) {
        return bounds != null
                && allFinite(
                bounds.minX,
                bounds.maxX,
                bounds.minZ,
                bounds.maxZ
        )
                && bounds.minX <= bounds.maxX
                && bounds.minZ <= bounds.maxZ;
    }

    static boolean allFinite(
            double a,
            double b,
            double c,
            double d
    ) {
        return Double.isFinite(a)
                && Double.isFinite(b)
                && Double.isFinite(c)
                && Double.isFinite(d);
    }

    static boolean overlapsXZ(
            AABB bounds,
            double minX,
            double maxX,
            double minZ,
            double maxZ
    ) {
        return bounds.maxX >= minX
                && bounds.minX <= maxX
                && bounds.maxZ >= minZ
                && bounds.minZ <= maxZ;
    }

    static long cellCount(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        final long spanX =
                (long) maxX - minX + 1L;
        final long spanZ =
                (long) maxZ - minZ + 1L;

        if (
                spanX <= 0L
                        || spanZ <= 0L
                        || spanX > Long.MAX_VALUE / spanZ
        ) {
            return Long.MAX_VALUE;
        }

        return spanX * spanZ;
    }

    static boolean isGiant(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        final long count =
                cellCount(
                        minX,
                        maxX,
                        minZ,
                        maxZ
                );

        return count <= 0L
                || count > MAX_CELLS_PER_OBJECT;
    }
}