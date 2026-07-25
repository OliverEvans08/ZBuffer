package engine.render.raster;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public final class FrameBuffer {
    public BufferedImage image;
    public int[] pixels;
    public int width = -1;
    public int height = -1;

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
                image == null ||
                        width != this.width ||
                        height != this.height
        ) {
            image = new BufferedImage(
                    width,
                    height,
                    BufferedImage.TYPE_INT_ARGB
            );
            pixels = ((DataBufferInt) image
                    .getRaster()
                    .getDataBuffer())
                    .getData();
            this.width = width;
            this.height = height;
        }
    }
}