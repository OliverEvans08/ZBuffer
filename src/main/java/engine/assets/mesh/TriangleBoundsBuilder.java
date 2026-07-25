package engine.assets.mesh;

public final class TriangleBoundsBuilder {

    private TriangleBoundsBuilder() {
    }

    public static float[] create(
            int triangleCount
    ) {
        return new float[triangleCount * 6];
    }

    public static void store(
            float[] triangleBounds,
            int triangle,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz
    ) {
        final int boundsOffset =
                triangle * 6;

        triangleBounds[boundsOffset] =
                Math.min(
                        ax,
                        Math.min(bx, cx)
                );
        triangleBounds[boundsOffset + 1] =
                Math.min(
                        ay,
                        Math.min(by, cy)
                );
        triangleBounds[boundsOffset + 2] =
                Math.min(
                        az,
                        Math.min(bz, cz)
                );

        triangleBounds[boundsOffset + 3] =
                Math.max(
                        ax,
                        Math.max(bx, cx)
                );
        triangleBounds[boundsOffset + 4] =
                Math.max(
                        ay,
                        Math.max(by, cy)
                );
        triangleBounds[boundsOffset + 5] =
                Math.max(
                        az,
                        Math.max(bz, cz)
                );
    }
}