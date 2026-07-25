package engine.render.raster;

public final class DepthBuffer {
    public float[] values;
    private int width = -1;
    private int height = -1;

    public void ensure(int width, int height) {
        final int pixelCount;

        try {
            pixelCount = Math.multiplyExact(
                    width,
                    height
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Render target is too large: " +
                            width +
                            "x" +
                            height,
                    exception
            );
        }

        if (
                values == null ||
                        width != this.width ||
                        height != this.height
        ) {
            values = new float[pixelCount];
            this.width = width;
            this.height = height;
        }
    }
}