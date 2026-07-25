package engine.render.util;

import engine.render.geometry.TriangleBatch;
import java.util.ArrayList;
import java.util.Arrays;
import objects.GameObject;

public final class RenderWorkerContext {
    public final double[] clipInputX = new double[4];
    public final double[] clipInputY = new double[4];
    public final double[] clipInputZ = new double[4];
    public final double[] clipInputU = new double[4];
    public final double[] clipInputV = new double[4];
    public final double[] clipOutputX = new double[4];
    public final double[] clipOutputY = new double[4];
    public final double[] clipOutputZ = new double[4];
    public final double[] clipOutputU = new double[4];
    public final double[] clipOutputV = new double[4];
    public final double[] modelMatrix = new double[12];
    public final double[] modelViewMatrix = new double[12];
    public boolean[] shadowedPerLight = new boolean[0];
    public final ArrayList<GameObject> shadowCandidates =
            new ArrayList<>(128);
    public final double[] lighting = new double[3];
    public final TriangleBatch batch = new TriangleBatch();

    public void ensureShadowCapacity(int required) {
        if (shadowedPerLight.length < required) {
            shadowedPerLight = new boolean[
                    growCapacity(
                            shadowedPerLight.length,
                            required,
                            16
                    )
                    ];
        }

        Arrays.fill(
                shadowedPerLight,
                0,
                required,
                false
        );
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