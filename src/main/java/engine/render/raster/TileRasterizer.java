package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.LightingCalculator;
import engine.render.lighting.MaterialState;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

public final class TileRasterizer {
    private final TriangleBuffer triangles;
    private final TileGrid grid;
    private final FrameBuffer frameBuffer;
    private final DepthBuffer depthBuffer;
    private final SkyRenderer skyRenderer;
    private final LightingCalculator lightingCalculator;

    private final LongAdder pixelsShaded =
            new LongAdder();

    public TileRasterizer(
            TriangleBuffer triangles,
            TileGrid grid,
            FrameBuffer frameBuffer,
            DepthBuffer depthBuffer,
            SkyRenderer skyRenderer,
            LightingCalculator lightingCalculator
    ) {
        this.triangles =
                triangles;

        this.grid =
                grid;

        this.frameBuffer =
                frameBuffer;

        this.depthBuffer =
                depthBuffer;

        this.skyRenderer =
                skyRenderer;

        this.lightingCalculator =
                lightingCalculator;
    }

    public void beginFrame() {
        pixelsShaded.reset();
    }

    public long getPixelsShaded() {
        return pixelsShaded.sum();
    }

    public void renderTile(
            int tile,
            int tileColumns,
            int width,
            int height
    ) {
        final int tileX =
                tile % tileColumns;

        final int tileY =
                tile / tileColumns;

        final int minimumX =
                tileX <<
                        RenderSettings.TILE_SHIFT;

        final int minimumY =
                tileY <<
                        RenderSettings.TILE_SHIFT;

        final int maximumXExclusive =
                Math.min(
                        width,
                        minimumX +
                                RenderSettings.TILE_SIZE
                );

        final int maximumYExclusive =
                Math.min(
                        height,
                        minimumY +
                                RenderSettings.TILE_SIZE
                );

        final int[] framePixels =
                frameBuffer.pixels;

        final int[] background =
                skyRenderer.pixels;

        final float[] frameDepth =
                depthBuffer.values;

        final int tileWidth =
                maximumXExclusive -
                        minimumX;

        /*
         * Clear only the pixels covered by this tile. Each worker exclusively
         * owns its current tile, so these writes require no synchronisation.
         */
        for (
                int y = minimumY;
                y < maximumYExclusive;
                y++
        ) {
            final int start =
                    y * width +
                            minimumX;

            final int end =
                    start +
                            tileWidth;

            Arrays.fill(
                    frameDepth,
                    start,
                    end,
                    Float.NEGATIVE_INFINITY
            );

            System.arraycopy(
                    background,
                    start,
                    framePixels,
                    start,
                    tileWidth
            );
        }

        final int[] triangleIndices =
                grid.triangleIndices;

        final int triangleStart =
                grid.offsets[tile];

        final int triangleEnd =
                grid.offsets[tile + 1];

        final MaterialState[] materials =
                triangles.materials;

        final float[] maximumInverseZ =
                triangles.maximumInverseZ;

        float depthMinimum =
                Float.NEGATIVE_INFINITY;

        for (
                int position = triangleStart;
                position < triangleEnd;
                position++
        ) {
            final int triangle =
                    triangleIndices[position];

            if (materials[triangle].wireframe) {
                LineRasterizer.rasterizeWireframeTriangle(
                        triangles,
                        framePixels,
                        frameDepth,
                        triangle,
                        minimumX,
                        maximumXExclusive,
                        minimumY,
                        maximumYExclusive,
                        width
                );

                continue;
            }

            final float maximumTriangleDepth =
                    maximumInverseZ[triangle];

            /*
             * Triangles are ordered front-to-back. Once a triangle's closest
             * point is behind the tile's fully covered minimum depth, it
             * cannot contribute any visible pixels.
             */
            if (maximumTriangleDepth <= depthMinimum) {
                continue;
            }

            depthMinimum =
                    TriangleRasterizer
                            .rasterizeFilledTriangle(
                                    triangles,
                                    framePixels,
                                    frameDepth,
                                    triangle,
                                    minimumX,
                                    maximumXExclusive,
                                    minimumY,
                                    maximumYExclusive,
                                    width,
                                    depthMinimum,
                                    lightingCalculator
                            );
        }

        grid.depthMinimum[tile] =
                depthMinimum;

        long shaded = 0L;

        /*
         * Any pixel whose depth differs from the clear value received camera
         * geometry during this frame. Counting after tile rendering avoids
         * atomic operations in the inner triangle/pixel raster loops.
         */
        for (
                int y = minimumY;
                y < maximumYExclusive;
                y++
        ) {
            int pixel =
                    y * width +
                            minimumX;

            final int end =
                    pixel +
                            tileWidth;

            while (pixel < end) {
                if (
                        frameDepth[pixel] !=
                                Float.NEGATIVE_INFINITY
                ) {
                    shaded++;
                }

                pixel++;
            }
        }

        pixelsShaded.add(shaded);
    }
}