package engine.spatial;

final class SpatialEntry {

    final int minX;
    final int maxX;
    final int minZ;
    final int maxZ;
    final boolean giant;

    SpatialEntry(CellRange range) {
        this(
                range.minX(),
                range.maxX(),
                range.minZ(),
                range.maxZ(),
                range.giant()
        );
    }

    SpatialEntry(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            boolean giant
    ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.giant = giant;
    }

    boolean matches(CellRange other) {
        return matches(
                other.minX(),
                other.maxX(),
                other.minZ(),
                other.maxZ(),
                other.giant()
        );
    }

    boolean matches(
            int otherMinX,
            int otherMaxX,
            int otherMinZ,
            int otherMaxZ,
            boolean otherGiant
    ) {
        return minX == otherMinX
                && maxX == otherMaxX
                && minZ == otherMinZ
                && maxZ == otherMaxZ
                && giant == otherGiant;
    }
}