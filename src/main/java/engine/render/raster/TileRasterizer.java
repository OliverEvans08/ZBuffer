package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.MaterialState;
import engine.render.util.IntList;
import java.util.Arrays;

public final class TileRasterizer {
    private final TriangleBuffer triangles;
    private final TileGrid grid;
    private final FrameBuffer frameBuffer;
    private final DepthBuffer depthBuffer;
    private final SkyRenderer skyRenderer;

    public TileRasterizer(
            TriangleBuffer triangles,
            TileGrid grid,
            FrameBuffer frameBuffer,
            DepthBuffer depthBuffer,
            SkyRenderer skyRenderer
    ) {
        this.triangles = triangles;
        this.grid = grid;
        this.frameBuffer = frameBuffer;
        this.depthBuffer = depthBuffer;
        this.skyRenderer = skyRenderer;
    }

    public void renderTile(
            int tile,
            int tileColumns,
            int width,
            int height
    ) {
        final int tileX = tile % tileColumns;
        final int tileY = tile / tileColumns;
        final int minimumX =
                tileX << RenderSettings.TILE_SHIFT;
        final int minimumY =
                tileY << RenderSettings.TILE_SHIFT;
        final int maximumXExclusive =
                Math.min(
                        width,
                        minimumX + RenderSettings.TILE_SIZE
                );
        final int maximumYExclusive =
                Math.min(
                        height,
                        minimumY + RenderSettings.TILE_SIZE
                );
        final int[] framePixels = frameBuffer.pixels;
        final int[] background = skyRenderer.pixels;
        final float[] frameDepth = depthBuffer.values;
        final int tileWidth =
                maximumXExclusive - minimumX;

        for (
                int y = minimumY;
                y < maximumYExclusive;
                y++
        ) {
            final int start = y * width + minimumX;
            final int end = start + tileWidth;

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

        final IntList bin = grid.bins[tile];
        final int[] triangleIndices = bin.values;
        final int trianglesInTile = bin.size;
        final MaterialState[] materials =
                triangles.materials;
        final float[] inverseZ0 = triangles.inverseZ0;
        final float[] inverseZ1 = triangles.inverseZ1;
        final float[] inverseZ2 = triangles.inverseZ2;
        float depthMinimum = Float.NEGATIVE_INFINITY;

        for (
                int position = 0;
                position < trianglesInTile;
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

            final float first = inverseZ0[triangle];
            final float second = inverseZ1[triangle];
            final float third = inverseZ2[triangle];
            final float maximumTriangleDepth =
                    Math.max(
                            first,
                            Math.max(second, third)
                    );

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
                                    depthMinimum
                            );
        }

        grid.depthMinimum[tile] = depthMinimum;
    }
}