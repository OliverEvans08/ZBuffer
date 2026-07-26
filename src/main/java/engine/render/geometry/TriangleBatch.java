package engine.render.geometry;

import engine.render.lighting.MaterialState;

public final class TriangleBatch {
    private TriangleBuffer buffer;
    private int start;
    private int cursor;
    private int limit;

    public void bind(
            TriangleBuffer buffer,
            int start,
            int limit
    ) {
        this.buffer = buffer;
        this.start = start;
        this.cursor = start;
        this.limit = limit;
    }

    public int size() {
        return cursor - start;
    }

    public void addCachedProjectedTriangle(
            ProjectionCache projected,
            int index0,
            int index1,
            int index2,
            double cameraNormalX,
            double cameraNormalY,
            double cameraNormalZ,
            int width,
            int height,
            MaterialState material,
            int shadeRed,
            int shadeGreen,
            int shadeBlue,
            boolean isDoubleSided,
            boolean isWireframe
    ) {
        addScreenTriangle(
                projected.screenX[index0],
                projected.screenY[index0],
                projected.inverseZ[index0],
                projected.uOverZ[index0],
                projected.vOverZ[index0],
                projected.screenX[index1],
                projected.screenY[index1],
                projected.inverseZ[index1],
                projected.uOverZ[index1],
                projected.vOverZ[index1],
                projected.screenX[index2],
                projected.screenY[index2],
                projected.inverseZ[index2],
                projected.uOverZ[index2],
                projected.vOverZ[index2],
                cameraNormalX,
                cameraNormalY,
                cameraNormalZ,
                width,
                height,
                material,
                shadeRed,
                shadeGreen,
                shadeBlue,
                isDoubleSided
        );
    }

    public void addProjectedTriangle(
            double cameraX0,
            double cameraY0,
            double cameraZ0,
            double u0,
            double v0,
            double cameraX1,
            double cameraY1,
            double cameraZ1,
            double u1,
            double v1,
            double cameraX2,
            double cameraY2,
            double cameraZ2,
            double u2,
            double v2,
            double cameraNormalX,
            double cameraNormalY,
            double cameraNormalZ,
            double projectionScale,
            int width,
            int height,
            double farDistance,
            MaterialState material,
            int shadeRed,
            int shadeGreen,
            int shadeBlue,
            boolean isDoubleSided,
            boolean isWireframe
    ) {
        if (
                cameraZ0 >= farDistance &&
                        cameraZ1 >= farDistance &&
                        cameraZ2 >= farDistance
        ) {
            return;
        }

        final double inverse0 =
                1.0 / cameraZ0;

        final double inverse1 =
                1.0 / cameraZ1;

        final double inverse2 =
                1.0 / cameraZ2;

        final double halfWidth =
                width * 0.5;

        final double halfHeight =
                height * 0.5;

        final double projectedScale0 =
                projectionScale * inverse0;

        final double projectedScale1 =
                projectionScale * inverse1;

        final double projectedScale2 =
                projectionScale * inverse2;

        final double screenX0 =
                cameraX0 * projectedScale0 +
                        halfWidth;

        final double screenY0 =
                -cameraY0 * projectedScale0 +
                        halfHeight;

        final double screenX1 =
                cameraX1 * projectedScale1 +
                        halfWidth;

        final double screenY1 =
                -cameraY1 * projectedScale1 +
                        halfHeight;

        final double screenX2 =
                cameraX2 * projectedScale2 +
                        halfWidth;

        final double screenY2 =
                -cameraY2 * projectedScale2 +
                        halfHeight;

        addScreenTriangle(
                screenX0,
                screenY0,
                (float) inverse0,
                (float) (u0 * inverse0),
                (float) (v0 * inverse0),
                screenX1,
                screenY1,
                (float) inverse1,
                (float) (u1 * inverse1),
                (float) (v1 * inverse1),
                screenX2,
                screenY2,
                (float) inverse2,
                (float) (u2 * inverse2),
                (float) (v2 * inverse2),
                cameraNormalX,
                cameraNormalY,
                cameraNormalZ,
                width,
                height,
                material,
                shadeRed,
                shadeGreen,
                shadeBlue,
                isDoubleSided
        );
    }

    private void addScreenTriangle(
            double screenX0,
            double screenY0,
            float inverse0,
            float uOverZ0,
            float vOverZ0,
            double screenX1,
            double screenY1,
            float inverse1,
            float uOverZ1,
            float vOverZ1,
            double screenX2,
            double screenY2,
            float inverse2,
            float uOverZ2,
            float vOverZ2,
            double cameraNormalX,
            double cameraNormalY,
            double cameraNormalZ,
            int width,
            int height,
            MaterialState material,
            int shadeRed,
            int shadeGreen,
            int shadeBlue,
            boolean isDoubleSided
    ) {
        final double signedArea =
                edgeFunction(
                        screenX0,
                        screenY0,
                        screenX1,
                        screenY1,
                        screenX2,
                        screenY2
                );

        if (
                !(signedArea > 0.0) &&
                        !(signedArea < 0.0)
        ) {
            return;
        }

        if (signedArea < 0.0) {
            if (!isDoubleSided) {
                return;
            }

            double temporary =
                    screenX1;

            screenX1 = screenX2;
            screenX2 = temporary;

            temporary = screenY1;
            screenY1 = screenY2;
            screenY2 = temporary;

            final float temporaryInverse =
                    inverse1;

            inverse1 = inverse2;
            inverse2 = temporaryInverse;

            float temporaryFloat =
                    uOverZ1;

            uOverZ1 = uOverZ2;
            uOverZ2 = temporaryFloat;

            temporaryFloat = vOverZ1;
            vOverZ1 = vOverZ2;
            vOverZ2 = temporaryFloat;

            /*
             * Swapping two vertices reverses the geometric winding. Keep the
             * stored face normal consistent with that new winding.
             */
            cameraNormalX = -cameraNormalX;
            cameraNormalY = -cameraNormalY;
            cameraNormalZ = -cameraNormalZ;
        }

        double minimumScreenX =
                screenX0;

        if (screenX1 < minimumScreenX) {
            minimumScreenX = screenX1;
        }

        if (screenX2 < minimumScreenX) {
            minimumScreenX = screenX2;
        }

        double maximumScreenX =
                screenX0;

        if (screenX1 > maximumScreenX) {
            maximumScreenX = screenX1;
        }

        if (screenX2 > maximumScreenX) {
            maximumScreenX = screenX2;
        }

        double minimumScreenY =
                screenY0;

        if (screenY1 < minimumScreenY) {
            minimumScreenY = screenY1;
        }

        if (screenY2 < minimumScreenY) {
            minimumScreenY = screenY2;
        }

        double maximumScreenY =
                screenY0;

        if (screenY1 > maximumScreenY) {
            maximumScreenY = screenY1;
        }

        if (screenY2 > maximumScreenY) {
            maximumScreenY = screenY2;
        }

        int minX =
                fastFloor(minimumScreenX);

        int maxX =
                fastFloor(maximumScreenX + 0.5);

        int minY =
                fastFloor(minimumScreenY);

        int maxY =
                fastFloor(maximumScreenY + 0.5);

        if (minX < 0) {
            minX = 0;
        }

        if (minY < 0) {
            minY = 0;
        }

        final int lastX =
                width - 1;

        final int lastY =
                height - 1;

        if (maxX > lastX) {
            maxX = lastX;
        }

        if (maxY > lastY) {
            maxY = lastY;
        }

        if (
                minX > maxX ||
                        minY > maxY
        ) {
            return;
        }

        if (cursor >= limit) {
            throw new IllegalStateException(
                    "Triangle worker exceeded its reserved range"
            );
        }

        final TriangleBuffer output =
                buffer;

        final int index =
                cursor++;

        output.x0[index] = screenX0;
        output.y0[index] = screenY0;

        output.x1[index] = screenX1;
        output.y1[index] = screenY1;

        output.x2[index] = screenX2;
        output.y2[index] = screenY2;

        output.inverseZ0[index] = inverse0;
        output.inverseZ1[index] = inverse1;
        output.inverseZ2[index] = inverse2;

        output.uOverZ0[index] = uOverZ0;
        output.vOverZ0[index] = vOverZ0;

        output.uOverZ1[index] = uOverZ1;
        output.vOverZ1[index] = vOverZ1;

        output.uOverZ2[index] = uOverZ2;
        output.vOverZ2[index] = vOverZ2;

        output.normalX[index] = cameraNormalX;
        output.normalY[index] = cameraNormalY;
        output.normalZ[index] = cameraNormalZ;

        output.doubleSided[index] =
                (byte) (
                        isDoubleSided
                                ? 1
                                : 0
                );

        output.materials[index] =
                material;

        output.shades[index] =
                shadeRed << 16 |
                        shadeGreen << 8 |
                        shadeBlue;

        output.minimumX[index] = minX;
        output.maximumX[index] = maxX;
        output.minimumY[index] = minY;
        output.maximumY[index] = maxY;
    }

    private static int fastFloor(
            double value
    ) {
        final int truncated =
                (int) value;

        return value < truncated
                ? truncated - 1
                : truncated;
    }

    private static double edgeFunction(
            double ax,
            double ay,
            double bx,
            double by,
            double px,
            double py
    ) {
        return (py - ay) * (bx - ax) -
                (px - ax) * (by - ay);
    }
}