package engine.render;

public record RenderStats(
        int visibleObjects,
        int cameraPassTriangles,
        long shadowPassTriangles,
        int nearbyChunks,
        int visibleChunks,
        long tileReferences,
        long pixelsShaded,
        long setupNanos,
        long sceneNanos,
        long shadowNanos,
        long cameraPassNanos,
        long cameraTransformationNanos,
        long clippingNanos,
        long triangleEmissionNanos,
        long tileBinningNanos,
        long rasterNanos,
        long fxaaNanos,
        long blitNanos,
        long totalNanos
) {
    private static final RenderStats EMPTY =
            new RenderStats(
                    0,
                    0,
                    0L,
                    0,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L
            );

    public static RenderStats empty() {
        return EMPTY;
    }

    public double setupMilliseconds() {
        return toMilliseconds(
                setupNanos
        );
    }

    public double sceneMilliseconds() {
        return toMilliseconds(
                sceneNanos
        );
    }

    public double shadowMilliseconds() {
        return toMilliseconds(
                shadowNanos
        );
    }

    public double cameraPassMilliseconds() {
        return toMilliseconds(
                cameraPassNanos
        );
    }

    public double cameraTransformationMilliseconds() {
        return toMilliseconds(
                cameraTransformationNanos
        );
    }

    public double clippingMilliseconds() {
        return toMilliseconds(
                clippingNanos
        );
    }

    public double triangleEmissionMilliseconds() {
        return toMilliseconds(
                triangleEmissionNanos
        );
    }

    public double tileBinningMilliseconds() {
        return toMilliseconds(
                tileBinningNanos
        );
    }

    public double rasterMilliseconds() {
        return toMilliseconds(
                rasterNanos
        );
    }

    public double fxaaMilliseconds() {
        return toMilliseconds(
                fxaaNanos
        );
    }

    public double blitMilliseconds() {
        return toMilliseconds(
                blitNanos
        );
    }

    public double totalMilliseconds() {
        return toMilliseconds(
                totalNanos
        );
    }

    private static double toMilliseconds(
            long nanoseconds
    ) {
        return nanoseconds /
                1_000_000.0;
    }
}