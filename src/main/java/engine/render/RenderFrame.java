package engine.render;

import engine.lighting.LightData;
import engine.render.geometry.ProjectionCache;
import engine.render.geometry.RenderMesh;
import engine.render.geometry.RenderMeshChunkLayout;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.MaterialState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import objects.GameObject;
import util.AABB;

public final class RenderFrame {

    public final ArrayList<LightData> lights =
            new ArrayList<>();

    public final ArrayList<GameObject> nearbyRenderables =
            new ArrayList<>(4096);

    public final ArrayList<SceneChunk> sceneChunks =
            new ArrayList<>(256);

    public final IdentityHashMap<GameObject, Boolean> objectSet =
            new IdentityHashMap<>(4096);

    public final TriangleBuffer triangles =
            new TriangleBuffer();

    public LightData[] lightArray =
            new LightData[0];

    public int lightCount;

    public GameObject[] objects =
            new GameObject[0];

    public RenderMesh[] meshes =
            new RenderMesh[0];

    public ProjectionCache[] projections =
            new ProjectionCache[0];

    public RenderMeshChunkLayout[] chunkLayouts =
            new RenderMeshChunkLayout[0];

    public byte[][] visibleMeshChunks =
            new byte[0][];

    public MaterialState[] materials =
            new MaterialState[0];

    public double[][][] worldVertices =
            new double[0][][];

    public AABB[] bounds =
            new AABB[0];

    public double[] nearDistances =
            new double[0];

    public double[] zBiases =
            new double[0];

    public byte[] doubleSided =
            new byte[0];

    public byte[] projectionValid =
            new byte[0];

    public byte[] chunkCullValid =
            new byte[0];

    /*
     * Twelve affine model-view values per item. Only partitioned items write
     * and read their range.
     */
    public double[] modelViewMatrices =
            new double[0];

    public int[] itemChunksTested =
            new int[0];

    public int[] itemChunksVisible =
            new int[0];

    public int[] itemFacesRejected =
            new int[0];

    /*
     * Assigned worker for each render item.
     */
    public int[] itemWorkers =
            new int[0];

    /*
     * Render-item indices grouped contiguously by worker.
     */
    public int[] workerOrderedItems =
            new int[0];

    /*
     * Temporary primitive sorting values used by the face-count partitioner.
     */
    public long[] weightedItems =
            new long[0];

    public int itemCount;

    public int nearbyChunkCount;
    public int visibleChunkCount;

    public int partitionedItemCount;
    public int meshChunksTested;
    public int meshChunksVisible;
    public int meshChunkFacesRejected;

    public double cachedYaw;
    public double cachedPitch;

    public double cosineYaw;
    public double sineYaw;
    public double cosinePitch;
    public double sinePitch;

    public double absoluteCosineYaw;
    public double absoluteSineYaw;
    public double absoluteCosinePitch;
    public double absoluteSinePitch;

    public boolean cameraValuesCached;

    /**
     * Copies the frame light list through ArrayList.toArray().
     *
     * <p>This uses the ArrayList's contiguous backing storage instead of
     * repeatedly calling get(index). The reusable destination array prevents
     * steady-state allocation.</p>
     */
    public void copyLightsToArray() {
        lightCount =
                lights.size();

        if (
                lightArray.length <
                        lightCount
        ) {
            lightArray =
                    new LightData[
                            growCapacity(
                                    lightArray.length,
                                    lightCount,
                                    16
                            )
                            ];
        }

        lights.toArray(
                lightArray
        );
    }

    public void ensureItemCapacity(
            int required
    ) {
        if (
                objects.length >=
                        required
        ) {
            return;
        }

        final int capacity =
                growCapacity(
                        objects.length,
                        required,
                        256
                );

        objects =
                Arrays.copyOf(
                        objects,
                        capacity
                );

        meshes =
                Arrays.copyOf(
                        meshes,
                        capacity
                );

        projections =
                Arrays.copyOf(
                        projections,
                        capacity
                );

        chunkLayouts =
                Arrays.copyOf(
                        chunkLayouts,
                        capacity
                );

        visibleMeshChunks =
                Arrays.copyOf(
                        visibleMeshChunks,
                        capacity
                );

        materials =
                Arrays.copyOf(
                        materials,
                        capacity
                );

        worldVertices =
                Arrays.copyOf(
                        worldVertices,
                        capacity
                );

        bounds =
                Arrays.copyOf(
                        bounds,
                        capacity
                );

        nearDistances =
                Arrays.copyOf(
                        nearDistances,
                        capacity
                );

        zBiases =
                Arrays.copyOf(
                        zBiases,
                        capacity
                );

        doubleSided =
                Arrays.copyOf(
                        doubleSided,
                        capacity
                );

        projectionValid =
                Arrays.copyOf(
                        projectionValid,
                        capacity
                );

        chunkCullValid =
                Arrays.copyOf(
                        chunkCullValid,
                        capacity
                );

        modelViewMatrices =
                Arrays.copyOf(
                        modelViewMatrices,
                        capacity * 12
                );

        itemChunksTested =
                Arrays.copyOf(
                        itemChunksTested,
                        capacity
                );

        itemChunksVisible =
                Arrays.copyOf(
                        itemChunksVisible,
                        capacity
                );

        itemFacesRejected =
                Arrays.copyOf(
                        itemFacesRejected,
                        capacity
                );

        itemWorkers =
                Arrays.copyOf(
                        itemWorkers,
                        capacity
                );

        workerOrderedItems =
                Arrays.copyOf(
                        workerOrderedItems,
                        capacity
                );

        weightedItems =
                Arrays.copyOf(
                        weightedItems,
                        capacity
                );
    }

    public void finishMeshChunkStats() {
        int tested = 0;
        int visible = 0;
        int rejectedFaces = 0;

        for (
                int item = 0;
                item < itemCount;
                item++
        ) {
            tested +=
                    itemChunksTested[item];

            visible +=
                    itemChunksVisible[item];

            rejectedFaces +=
                    itemFacesRejected[item];
        }

        meshChunksTested = tested;
        meshChunksVisible = visible;
        meshChunkFacesRejected = rejectedFaces;
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity =
                Math.max(
                        minimum,
                        current
                );

        while (capacity < required) {
            if (
                    capacity >
                            Integer.MAX_VALUE / 2
            ) {
                return required;
            }

            capacity <<= 1;
        }

        return capacity;
    }
}