package engine.render;

import engine.lighting.LightData;
import engine.render.geometry.ProjectionCache;
import engine.render.geometry.RenderMesh;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.MaterialState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import objects.GameObject;
import util.AABB;

public final class RenderFrame {
    public final ArrayList<LightData> lights = new ArrayList<>();
    public final ArrayList<GameObject> nearbyRenderables =
            new ArrayList<>(4096);
    public final ArrayList<GameObject> renderRoots =
            new ArrayList<>(4096);
    public final IdentityHashMap<GameObject, Boolean> objectSet =
            new IdentityHashMap<>(4096);
    public final TriangleBuffer triangles = new TriangleBuffer();

    public LightData[] lightArray = new LightData[0];
    public int lightCount;

    public GameObject[] objects = new GameObject[0];
    public RenderMesh[] meshes = new RenderMesh[0];
    public ProjectionCache[] projections = new ProjectionCache[0];
    public MaterialState[] materials = new MaterialState[0];
    public double[][][] worldVertices = new double[0][][];
    public AABB[] bounds = new AABB[0];
    public double[] nearDistances = new double[0];
    public double[] zBiases = new double[0];
    public byte[] doubleSided = new byte[0];
    public int[] itemWorkers = new int[0];
    public long[] weightedItems = new long[0];
    public int itemCount;

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

    public void copyLightsToArray() {
        lightCount = lights.size();

        if (lightArray.length < lightCount) {
            lightArray = new LightData[
                    growCapacity(lightArray.length, lightCount, 16)
                    ];
        }

        for (int i = 0; i < lightCount; i++) {
            lightArray[i] = lights.get(i);
        }
    }

    public void ensureItemCapacity(int required) {
        if (objects.length >= required) {
            return;
        }

        final int capacity =
                growCapacity(objects.length, required, 256);

        objects = Arrays.copyOf(objects, capacity);
        meshes = Arrays.copyOf(meshes, capacity);
        projections = Arrays.copyOf(projections, capacity);
        materials = Arrays.copyOf(materials, capacity);
        worldVertices = Arrays.copyOf(worldVertices, capacity);
        bounds = Arrays.copyOf(bounds, capacity);
        nearDistances = Arrays.copyOf(nearDistances, capacity);
        zBiases = Arrays.copyOf(zBiases, capacity);
        doubleSided = Arrays.copyOf(doubleSided, capacity);
        itemWorkers = Arrays.copyOf(itemWorkers, capacity);
        weightedItems = Arrays.copyOf(weightedItems, capacity);
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity = Math.max(minimum, current);

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity <<= 1;
        }

        return capacity;
    }
}