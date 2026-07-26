package engine.render;

public record RenderStats(
        int visibleObjects,
        int cameraPassTriangles,
        long shadowPassTriangles,
        long tileReferences,
        long pixelsShaded,
        long setupNanos,
        long sceneNanos,
        long shadowNanos,
        long cameraPassNanos,
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
        return toMilliseconds(setupNanos);
    }

    public double sceneMilliseconds() {
        return toMilliseconds(sceneNanos);
    }

    public double shadowMilliseconds() {
        return toMilliseconds(shadowNanos);
    }

    public double cameraPassMilliseconds() {
        return toMilliseconds(cameraPassNanos);
    }

    public double tileBinningMilliseconds() {
        return toMilliseconds(tileBinningNanos);
    }

    public double rasterMilliseconds() {
        return toMilliseconds(rasterNanos);
    }

    public double fxaaMilliseconds() {
        return toMilliseconds(fxaaNanos);
    }

    public double blitMilliseconds() {
        return toMilliseconds(blitNanos);
    }

    public double totalMilliseconds() {
        return toMilliseconds(totalNanos);
    }

    private static double toMilliseconds(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }
}