package engine.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Objects;

public final class Texture {

    public enum Wrap {
        CLAMP,
        REPEAT,
    }

    public final int width;
    public final int height;
    public final int[] argb;

    private final int maximumX;
    private final int maximumY;

    public Texture(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Texture dimensions must be positive");
        }

        final int expectedLength;

        try {
            expectedLength = Math.multiplyExact(width, height);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Texture dimensions are too large", exception);
        }

        if (argb == null || argb.length != expectedLength) {
            throw new IllegalArgumentException("Texture pixel array must contain width * height pixels");
        }

        this.width = width;
        this.height = height;
        this.maximumX = width - 1;
        this.maximumY = height - 1;
        this.argb = Arrays.copyOf(argb, argb.length);
    }

    public static Texture fromBufferedImage(BufferedImage image) {
        Objects.requireNonNull(image, "image");

        final BufferedImage argbImage;

        if (image.getType() == BufferedImage.TYPE_INT_ARGB && image.getRaster().getDataBuffer() instanceof DataBufferInt) {
            argbImage = image;
        } else {
            argbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);

            final Graphics2D graphics = argbImage.createGraphics();

            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }

        final int[] pixels = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();

        return new Texture(argbImage.getWidth(), argbImage.getHeight(), pixels);
    }

    public int sampleNearest(double u, double v, Wrap wrap) {
        return sampleNearestFast(u, v, wrap == Wrap.REPEAT);
    }

    public int sampleNearestFast(double u, double v, boolean repeat) {
        if (!Double.isFinite(u) || !Double.isFinite(v)) {
            return argb[0];
        }

        final int x;
        final int y;

        if (repeat) {
            if (u < 0.0 || u >= 1.0) {
                u -= Math.floor(u);
            }

            if (v < 0.0 || v >= 1.0) {
                v -= Math.floor(v);
            }

            x = (int) (u * width);
            y = (int) (v * height);
        } else {
            x = u <= 0.0 ? 0 : u >= 1.0 ? maximumX : (int) (u * width);
            y = v <= 0.0 ? 0 : v >= 1.0 ? maximumY : (int) (v * height);
        }

        return argb[y * width + x];
    }

    public int sampleBilinear(double u, double v, Wrap wrap) {
        final Wrap effectiveWrap = wrap == null ? Wrap.CLAMP : wrap;

        final double wrappedU = wrapCoordinate(u, effectiveWrap);
        final double wrappedV = wrapCoordinate(v, effectiveWrap);

        final double sampleX = wrappedU * maximumX;
        final double sampleY = wrappedV * maximumY;

        final int x0 = (int) sampleX;
        final int y0 = (int) sampleY;
        final int x1 = Math.min(maximumX, x0 + 1);
        final int y1 = Math.min(maximumY, y0 + 1);
        final float blendX = (float) (sampleX - x0);
        final float blendY = (float) (sampleY - y0);

        return bilerpArgb(
                argb[y0 * width + x0],
                argb[y0 * width + x1],
                argb[y1 * width + x0],
                argb[y1 * width + x1],
                blendX,
                blendY
        );
    }

    private static int bilerpArgb(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            float blendX,
            float blendY
    ) {
        final int alpha = interpolateChannel(
                topLeft >>> 24,
                topRight >>> 24,
                bottomLeft >>> 24,
                bottomRight >>> 24,
                blendX,
                blendY
        );

        final int red = interpolateChannel(
                topLeft >>> 16,
                topRight >>> 16,
                bottomLeft >>> 16,
                bottomRight >>> 16,
                blendX,
                blendY
        );

        final int green = interpolateChannel(
                topLeft >>> 8,
                topRight >>> 8,
                bottomLeft >>> 8,
                bottomRight >>> 8,
                blendX,
                blendY
        );

        final int blue = interpolateChannel(topLeft, topRight, bottomLeft, bottomRight, blendX, blendY);

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int interpolateChannel(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            float blendX,
            float blendY
    ) {
        final float top = (topLeft & 255) + ((topRight & 255) - (topLeft & 255)) * blendX;
        final float bottom = (bottomLeft & 255) + ((bottomRight & 255) - (bottomLeft & 255)) * blendX;

        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * blendY)));
    }

    private static double wrapCoordinate(double coordinate, Wrap wrap) {
        if (!Double.isFinite(coordinate)) {
            return 0.0;
        }

        if (wrap == Wrap.REPEAT) {
            return coordinate - Math.floor(coordinate);
        }

        return Math.max(0.0, Math.min(1.0, coordinate));
    }
}