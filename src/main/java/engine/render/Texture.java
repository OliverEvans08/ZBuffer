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
    private final int widthMask;
    private final int heightMask;
    private final boolean powerOfTwoWidth;
    private final boolean powerOfTwoHeight;

    public Texture(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive"
            );
        }

        final int expectedLength;

        try {
            expectedLength = Math.multiplyExact(
                    width,
                    height
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Texture dimensions are too large",
                    exception
            );
        }

        if (
                argb == null ||
                        argb.length != expectedLength
        ) {
            throw new IllegalArgumentException(
                    "Texture pixel array must contain width * height pixels"
            );
        }

        this.width = width;
        this.height = height;

        maximumX = width - 1;
        maximumY = height - 1;

        powerOfTwoWidth =
                (width & maximumX) == 0;

        powerOfTwoHeight =
                (height & maximumY) == 0;

        widthMask =
                powerOfTwoWidth
                        ? maximumX
                        : -1;

        heightMask =
                powerOfTwoHeight
                        ? maximumY
                        : -1;

        this.argb = Arrays.copyOf(
                argb,
                argb.length
        );
    }

    public static Texture fromBufferedImage(
            BufferedImage image
    ) {
        Objects.requireNonNull(
                image,
                "image"
        );

        final BufferedImage argbImage;

        if (
                image.getType() ==
                        BufferedImage.TYPE_INT_ARGB &&
                        image.getRaster()
                                .getDataBuffer() instanceof
                                DataBufferInt
        ) {
            argbImage = image;
        } else {
            argbImage =
                    new BufferedImage(
                            image.getWidth(),
                            image.getHeight(),
                            BufferedImage.TYPE_INT_ARGB
                    );

            final Graphics2D graphics =
                    argbImage.createGraphics();

            try {
                graphics.drawImage(
                        image,
                        0,
                        0,
                        null
                );
            } finally {
                graphics.dispose();
            }
        }

        final int[] pixels =
                (
                        (DataBufferInt)
                                argbImage
                                        .getRaster()
                                        .getDataBuffer()
                ).getData();

        return new Texture(
                argbImage.getWidth(),
                argbImage.getHeight(),
                pixels
        );
    }

    public int sampleNearest(
            double u,
            double v,
            Wrap wrap
    ) {
        return sampleNearestFast(
                u,
                v,
                wrap == Wrap.REPEAT
        );
    }

    public int sampleNearestFast(
            double u,
            double v,
            boolean repeat
    ) {
        if (
                !Double.isFinite(u) ||
                        !Double.isFinite(v)
        ) {
            return argb[0];
        }

        final int x;
        final int y;

        if (repeat) {
            x = repeatIndex(
                    u,
                    width,
                    widthMask,
                    powerOfTwoWidth
            );

            y = repeatIndex(
                    v,
                    height,
                    heightMask,
                    powerOfTwoHeight
            );
        } else {
            x = clampIndex(
                    u,
                    width,
                    maximumX
            );

            y = clampIndex(
                    v,
                    height,
                    maximumY
            );
        }

        return argb[
                y * width +
                        x
                ];
    }

    public int sampleBilinear(
            double u,
            double v,
            Wrap wrap
    ) {
        if (
                !Double.isFinite(u) ||
                        !Double.isFinite(v)
        ) {
            return argb[0];
        }

        return wrap == Wrap.REPEAT
                ? sampleBilinearRepeat(
                u,
                v
        )
                : sampleBilinearClamp(
                u,
                v
        );
    }

    private int sampleBilinearClamp(
            double u,
            double v
    ) {
        if (u <= 0.0) {
            u = 0.0;
        } else if (u >= 1.0) {
            u = 1.0;
        }

        if (v <= 0.0) {
            v = 0.0;
        } else if (v >= 1.0) {
            v = 1.0;
        }

        final double sampleX =
                u * maximumX;

        final double sampleY =
                v * maximumY;

        final int x0 =
                (int) sampleX;

        final int y0 =
                (int) sampleY;

        final int x1 =
                x0 < maximumX
                        ? x0 + 1
                        : x0;

        final int y1 =
                y0 < maximumY
                        ? y0 + 1
                        : y0;

        final int blendX =
                (int) (
                        (
                                sampleX -
                                        x0
                        ) *
                                256.0 +
                                0.5
                );

        final int blendY =
                (int) (
                        (
                                sampleY -
                                        y0
                        ) *
                                256.0 +
                                0.5
                );

        final int row0 =
                y0 * width;

        final int row1 =
                y1 * width;

        return bilerpArgbFixed(
                argb[row0 + x0],
                argb[row0 + x1],
                argb[row1 + x0],
                argb[row1 + x1],
                blendX,
                blendY
        );
    }

    private int sampleBilinearRepeat(
            double u,
            double v
    ) {
        u -= Math.floor(u);
        v -= Math.floor(v);

        final double sampleX =
                u * width;

        final double sampleY =
                v * height;

        final int baseX =
                (int) sampleX;

        final int baseY =
                (int) sampleY;

        final int x0 =
                powerOfTwoWidth
                        ? baseX & widthMask
                        : baseX;

        final int y0 =
                powerOfTwoHeight
                        ? baseY & heightMask
                        : baseY;

        final int x1;

        if (powerOfTwoWidth) {
            x1 =
                    (x0 + 1) &
                            widthMask;
        } else {
            final int nextX =
                    x0 + 1;

            x1 =
                    nextX == width
                            ? 0
                            : nextX;
        }

        final int y1;

        if (powerOfTwoHeight) {
            y1 =
                    (y0 + 1) &
                            heightMask;
        } else {
            final int nextY =
                    y0 + 1;

            y1 =
                    nextY == height
                            ? 0
                            : nextY;
        }

        final int blendX =
                (int) (
                        (
                                sampleX -
                                        baseX
                        ) *
                                256.0 +
                                0.5
                );

        final int blendY =
                (int) (
                        (
                                sampleY -
                                        baseY
                        ) *
                                256.0 +
                                0.5
                );

        final int row0 =
                y0 * width;

        final int row1 =
                y1 * width;

        return bilerpArgbFixed(
                argb[row0 + x0],
                argb[row0 + x1],
                argb[row1 + x0],
                argb[row1 + x1],
                blendX,
                blendY
        );
    }

    private static int repeatIndex(
            double coordinate,
            int size,
            int mask,
            boolean powerOfTwo
    ) {
        final double scaled =
                coordinate * size;

        final int floor =
                fastFloorToInt(
                        scaled
                );

        if (powerOfTwo) {
            return floor & mask;
        }

        final int remainder =
                floor % size;

        return remainder < 0
                ? remainder + size
                : remainder;
    }

    private static int clampIndex(
            double coordinate,
            int size,
            int maximum
    ) {
        if (coordinate <= 0.0) {
            return 0;
        }

        if (coordinate >= 1.0) {
            return maximum;
        }

        return (int) (
                coordinate *
                        size
        );
    }

    private static int fastFloorToInt(
            double value
    ) {
        final int truncated =
                (int) value;

        return value < truncated
                ? truncated - 1
                : truncated;
    }

    private static int bilerpArgbFixed(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            int blendX,
            int blendY
    ) {
        final int inverseX =
                256 - blendX;

        final int inverseY =
                256 - blendY;

        final int topRB =
                (
                        (
                                (
                                        (
                                                topLeft &
                                                        0x00FF00FF
                                        ) *
                                                inverseX
                                ) +
                                        (
                                                (
                                                        topRight &
                                                                0x00FF00FF
                                                ) *
                                                        blendX
                                        )
                        ) >>>
                                8
                ) &
                        0x00FF00FF;

        final int bottomRB =
                (
                        (
                                (
                                        (
                                                bottomLeft &
                                                        0x00FF00FF
                                        ) *
                                                inverseX
                                ) +
                                        (
                                                (
                                                        bottomRight &
                                                                0x00FF00FF
                                                ) *
                                                        blendX
                                        )
                        ) >>>
                                8
                ) &
                        0x00FF00FF;

        final int topAG =
                (
                        (
                                (
                                        (
                                                (
                                                        topLeft >>>
                                                                8
                                                ) &
                                                        0x00FF00FF
                                        ) *
                                                inverseX
                                ) +
                                        (
                                                (
                                                        (
                                                                topRight >>>
                                                                        8
                                                        ) &
                                                                0x00FF00FF
                                                ) *
                                                        blendX
                                        )
                        ) >>>
                                8
                ) &
                        0x00FF00FF;

        final int bottomAG =
                (
                        (
                                (
                                        (
                                                (
                                                        bottomLeft >>>
                                                                8
                                                ) &
                                                        0x00FF00FF
                                        ) *
                                                inverseX
                                ) +
                                        (
                                                (
                                                        (
                                                                bottomRight >>>
                                                                        8
                                                        ) &
                                                                0x00FF00FF
                                                ) *
                                                        blendX
                                        )
                        ) >>>
                                8
                ) &
                        0x00FF00FF;

        final int redBlue =
                (
                        (
                                topRB *
                                        inverseY
                        ) +
                                (
                                        bottomRB *
                                                blendY
                                )
                ) >>>
                        8 &
                        0x00FF00FF;

        final int alphaGreen =
                (
                        (
                                topAG *
                                        inverseY
                        ) +
                                (
                                        bottomAG *
                                                blendY
                                )
                ) >>>
                        8 &
                        0x00FF00FF;

        return redBlue |
                alphaGreen << 8;
    }
}