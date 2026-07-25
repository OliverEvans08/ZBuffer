package engine.render.geometry;

public final class ProjectionCache {
    public final double[] cachedModel = new double[12];
    public final double[] cachedModelView = new double[12];
    public boolean modelValid;
    public boolean modelViewValid;
    public double projectionScale;
    public int projectionWidth = -1;
    public int projectionHeight = -1;
    public double[] worldX = new double[0];
    public double[] worldY = new double[0];
    public double[] worldZ = new double[0];
    public double[] cameraX = new double[0];
    public double[] cameraY = new double[0];
    public double[] cameraZ = new double[0];
    public double[] screenX = new double[0];
    public double[] screenY = new double[0];
    public float[] inverseZ = new float[0];
    public float[] uOverZ = new float[0];
    public float[] vOverZ = new float[0];

    public void invalidate() {
        modelValid = false;
        modelViewValid = false;
        projectionWidth = -1;
        projectionHeight = -1;
    }

    public boolean update(
            RenderMesh mesh,
            double[][] currentVertices,
            double[] model,
            double[] modelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            double cosineYaw,
            double sineYaw,
            double cosinePitch,
            double sinePitch,
            double zBias,
            double projectionScale,
            int width,
            int height
    ) {
        if (
                !RenderMeshCompiler.calculateModelMatrix(
                        mesh,
                        currentVertices,
                        model
                )
        ) {
            return false;
        }

        ensureCapacity(mesh.vertexCount);

        final boolean modelChanged =
                !modelValid ||
                        !matrixEquals(cachedModel, model);

        if (modelChanged) {
            System.arraycopy(
                    model,
                    0,
                    cachedModel,
                    0,
                    12
            );
            modelValid = true;

            final double[] localX = mesh.x;
            final double[] localY = mesh.y;
            final double[] localZ = mesh.z;
            final double m00 = model[0];
            final double m01 = model[1];
            final double m02 = model[2];
            final double m03 = model[3];
            final double m10 = model[4];
            final double m11 = model[5];
            final double m12 = model[6];
            final double m13 = model[7];
            final double m20 = model[8];
            final double m21 = model[9];
            final double m22 = model[10];
            final double m23 = model[11];

            for (
                    int index = 0;
                    index < mesh.vertexCount;
                    index++
            ) {
                final double x = localX[index];
                final double y = localY[index];
                final double z = localZ[index];

                worldX[index] =
                        m00 * x + m01 * y + m02 * z + m03;
                worldY[index] =
                        m10 * x + m11 * y + m12 * z + m13;
                worldZ[index] =
                        m20 * x + m21 * y + m22 * z + m23;
            }
        }

        final double v00 = cosineYaw;
        final double v01 = 0.0;
        final double v02 = sineYaw;
        final double v10 = -sinePitch * sineYaw;
        final double v11 = cosinePitch;
        final double v12 = sinePitch * cosineYaw;
        final double v20 = -cosinePitch * sineYaw;
        final double v21 = -sinePitch;
        final double v22 = cosinePitch * cosineYaw;

        composeModelViewRow(
                model,
                modelView,
                0,
                v00,
                v01,
                v02,
                cameraX,
                cameraY,
                cameraZ,
                0.0
        );
        composeModelViewRow(
                model,
                modelView,
                4,
                v10,
                v11,
                v12,
                cameraX,
                cameraY,
                cameraZ,
                0.0
        );
        composeModelViewRow(
                model,
                modelView,
                8,
                v20,
                v21,
                v22,
                cameraX,
                cameraY,
                cameraZ,
                zBias
        );

        final boolean cameraChanged =
                !modelViewValid ||
                        !matrixEquals(
                                cachedModelView,
                                modelView
                        );

        if (cameraChanged) {
            System.arraycopy(
                    modelView,
                    0,
                    cachedModelView,
                    0,
                    12
            );
            modelViewValid = true;

            final double[] localX = mesh.x;
            final double[] localY = mesh.y;
            final double[] localZ = mesh.z;
            final double m00 = modelView[0];
            final double m01 = modelView[1];
            final double m02 = modelView[2];
            final double m03 = modelView[3];
            final double m10 = modelView[4];
            final double m11 = modelView[5];
            final double m12 = modelView[6];
            final double m13 = modelView[7];
            final double m20 = modelView[8];
            final double m21 = modelView[9];
            final double m22 = modelView[10];
            final double m23 = modelView[11];

            for (
                    int index = 0;
                    index < mesh.vertexCount;
                    index++
            ) {
                final double x = localX[index];
                final double y = localY[index];
                final double z = localZ[index];

                this.cameraX[index] =
                        m00 * x + m01 * y + m02 * z + m03;
                this.cameraY[index] =
                        m10 * x + m11 * y + m12 * z + m13;
                this.cameraZ[index] =
                        m20 * x + m21 * y + m22 * z + m23;
            }
        }

        if (
                cameraChanged ||
                        Double.doubleToLongBits(
                                this.projectionScale
                        ) !=
                                Double.doubleToLongBits(
                                        projectionScale
                                ) ||
                        projectionWidth != width ||
                        projectionHeight != height
        ) {
            this.projectionScale = projectionScale;
            projectionWidth = width;
            projectionHeight = height;

            final double halfWidth = width * 0.5;
            final double halfHeight = height * 0.5;

            for (
                    int index = 0;
                    index < mesh.vertexCount;
                    index++
            ) {
                final double z = this.cameraZ[index];

                if (
                        !Double.isFinite(z) ||
                                Math.abs(z) < 1.0e-15
                ) {
                    screenX[index] = Double.NaN;
                    screenY[index] = Double.NaN;
                    inverseZ[index] = Float.NaN;
                    uOverZ[index] = Float.NaN;
                    vOverZ[index] = Float.NaN;
                    continue;
                }

                final double inverse = 1.0 / z;

                screenX[index] =
                        this.cameraX[index] *
                                projectionScale *
                                inverse +
                                halfWidth;
                screenY[index] =
                        -this.cameraY[index] *
                                projectionScale *
                                inverse +
                                halfHeight;
                inverseZ[index] = (float) inverse;
                uOverZ[index] =
                        (float) (mesh.u[index] * inverse);
                vOverZ[index] =
                        (float) (mesh.v[index] * inverse);
            }
        }

        return true;
    }

    public void ensureCapacity(int required) {
        if (worldX.length >= required) {
            return;
        }

        final int capacity =
                growCapacity(worldX.length, required, 256);

        worldX = new double[capacity];
        worldY = new double[capacity];
        worldZ = new double[capacity];
        cameraX = new double[capacity];
        cameraY = new double[capacity];
        cameraZ = new double[capacity];
        screenX = new double[capacity];
        screenY = new double[capacity];
        inverseZ = new float[capacity];
        uOverZ = new float[capacity];
        vOverZ = new float[capacity];
        invalidate();
    }

    public static void composeModelViewRow(
            double[] model,
            double[] output,
            int offset,
            double v0,
            double v1,
            double v2,
            double cameraX,
            double cameraY,
            double cameraZ,
            double bias
    ) {
        output[offset] =
                v0 * model[0] +
                        v1 * model[4] +
                        v2 * model[8];
        output[offset + 1] =
                v0 * model[1] +
                        v1 * model[5] +
                        v2 * model[9];
        output[offset + 2] =
                v0 * model[2] +
                        v1 * model[6] +
                        v2 * model[10];
        output[offset + 3] =
                v0 * (model[3] - cameraX) +
                        v1 * (model[7] - cameraY) +
                        v2 * (model[11] - cameraZ) +
                        bias;
    }

    public static boolean matrixEquals(
            double[] first,
            double[] second
    ) {
        for (int index = 0; index < 12; index++) {
            if (
                    Double.doubleToLongBits(first[index]) !=
                            Double.doubleToLongBits(
                                    second[index]
                            )
            ) {
                return false;
            }
        }

        return true;
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