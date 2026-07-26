package engine.render.raster;

import java.util.Arrays;

/**
 * Lightweight, allocation-free-after-resize edge antialiasing pass.
 *
 * <p>Luma is calculated once per pixel and cached in a byte buffer. This
 * prevents repeatedly recalculating the same north, south, east and west
 * neighbour values for every output pixel.</p>
 */
public final class FxaaProcessor {
    private static final int ABSOLUTE_THRESHOLD = 16;

    /*
     * Relative threshold is maximumLuma / 8.
     *
     * A shift replaces integer division in the full-screen inner loop.
     */
    private static final int RELATIVE_THRESHOLD_SHIFT = 3;

    private int[] source =
            new int[0];

    private byte[] luma =
            new byte[0];

    public void apply(
            int[] pixels,
            int width,
            int height
    ) {
        if (
                pixels == null ||
                        width < 3 ||
                        height < 3
        ) {
            return;
        }

        final int pixelCount;

        try {
            pixelCount =
                    Math.multiplyExact(
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

        if (pixels.length < pixelCount) {
            throw new IllegalArgumentException(
                    "Pixel buffer is smaller than width * height"
            );
        }

        ensureCapacity(
                pixelCount
        );

        System.arraycopy(
                pixels,
                0,
                source,
                0,
                pixelCount
        );

        final int[] input =
                source;

        final byte[] cachedLuma =
                luma;

        /*
         * Calculate luma exactly once for each pixel.
         *
         * The old neighbourhood loop recalculated each interior pixel's luma
         * as the centre and again when used by adjacent pixels.
         */
        for (
                int index = 0;
                index < pixelCount;
                index++
        ) {
            cachedLuma[index] =
                    (byte) lumaOf(
                            input[index]
                    );
        }

        final int lastRowStart =
                pixelCount -
                        width;

        System.arraycopy(
                input,
                0,
                pixels,
                0,
                width
        );

        System.arraycopy(
                input,
                lastRowStart,
                pixels,
                lastRowStart,
                width
        );

        int row =
                width;

        final int finalInteriorRow =
                lastRowStart -
                        width;

        while (row <= finalInteriorRow) {
            final int rowEnd =
                    row +
                            width -
                            1;

            pixels[row] =
                    input[row];

            pixels[rowEnd] =
                    input[rowEnd];

            int index =
                    row + 1;

            final int interiorEnd =
                    rowEnd - 1;

            while (index <= interiorEnd) {
                final int center =
                        input[index];

                final int centerLuma =
                        cachedLuma[index] &
                                255;

                final int northLuma =
                        cachedLuma[
                                index -
                                        width
                                ] &
                                255;

                final int southLuma =
                        cachedLuma[
                                index +
                                        width
                                ] &
                                255;

                final int westLuma =
                        cachedLuma[index - 1] &
                                255;

                final int eastLuma =
                        cachedLuma[index + 1] &
                                255;

                /*
                 * Explicit comparisons are cheaper and easier for the JIT to
                 * optimise than deeply nested Math.min/Math.max calls.
                 */
                int minimum =
                        centerLuma;

                int maximum =
                        centerLuma;

                if (northLuma < minimum) {
                    minimum =
                            northLuma;
                }

                if (southLuma < minimum) {
                    minimum =
                            southLuma;
                }

                if (westLuma < minimum) {
                    minimum =
                            westLuma;
                }

                if (eastLuma < minimum) {
                    minimum =
                            eastLuma;
                }

                if (northLuma > maximum) {
                    maximum =
                            northLuma;
                }

                if (southLuma > maximum) {
                    maximum =
                            southLuma;
                }

                if (westLuma > maximum) {
                    maximum =
                            westLuma;
                }

                if (eastLuma > maximum) {
                    maximum =
                            eastLuma;
                }

                final int range =
                        maximum -
                                minimum;

                final int relativeThreshold =
                        maximum >>
                                RELATIVE_THRESHOLD_SHIFT;

                final int threshold =
                        relativeThreshold >
                                ABSOLUTE_THRESHOLD
                                ? relativeThreshold
                                : ABSOLUTE_THRESHOLD;

                if (range < threshold) {
                    pixels[index] =
                            center;

                    index++;
                    continue;
                }

                final int horizontalGradient =
                        abs(
                                westLuma -
                                        eastLuma
                        );

                final int verticalGradient =
                        abs(
                                northLuma -
                                        southLuma
                        );

                /*
                 * A stronger left/right change indicates an edge primarily
                 * running vertically, so blend north/south. Otherwise blend
                 * west/east.
                 */
                pixels[index] =
                        horizontalGradient >=
                                verticalGradient
                                ? blendThree(
                                center,
                                input[
                                        index -
                                                width
                                        ],
                                input[
                                        index +
                                                width
                                        ]
                        )
                                : blendThree(
                                center,
                                input[index - 1],
                                input[index + 1]
                        );

                index++;
            }

            row +=
                    width;
        }
    }

    private void ensureCapacity(
            int required
    ) {
        if (source.length >= required) {
            return;
        }

        int capacity =
                Math.max(
                        1024,
                        source.length
                );

        while (capacity < required) {
            if (
                    capacity >
                            Integer.MAX_VALUE / 2
            ) {
                capacity =
                        required;

                break;
            }

            capacity <<=
                    1;
        }

        source =
                Arrays.copyOf(
                        source,
                        capacity
                );

        luma =
                Arrays.copyOf(
                        luma,
                        capacity
                );
    }

    private static int lumaOf(
            int argb
    ) {
        return (
                (
                        argb >>> 16 &
                                255
                ) *
                        77 +
                        (
                                argb >>> 8 &
                                        255
                        ) *
                                150 +
                        (
                                argb &
                                        255
                        ) *
                                29
        ) >>>
                8;
    }

    private static int blendThree(
            int center,
            int first,
            int second
    ) {
        final int red =
                (
                        (
                                (
                                        center >>> 16 &
                                                255
                                ) <<
                                        1
                        ) +
                                (
                                        first >>> 16 &
                                                255
                                ) +
                                (
                                        second >>> 16 &
                                                255
                                )
                ) >>
                        2;

        final int green =
                (
                        (
                                (
                                        center >>> 8 &
                                                255
                                ) <<
                                        1
                        ) +
                                (
                                        first >>> 8 &
                                                255
                                ) +
                                (
                                        second >>> 8 &
                                                255
                                )
                ) >>
                        2;

        final int blue =
                (
                        (
                                center &
                                        255
                        ) <<
                                1
                ) +
                        (
                                first &
                                        255
                        ) +
                        (
                                second &
                                        255
                        ) >>
                        2;

        return 0xFF000000 |
                red << 16 |
                green << 8 |
                blue;
    }

    private static int abs(
            int value
    ) {
        return value < 0
                ? -value
                : value;
    }
}