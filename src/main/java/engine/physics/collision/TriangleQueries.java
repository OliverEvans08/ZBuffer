package engine.physics.collision;

public final class TriangleQueries {

    public static final double TRI_EPS = 1e-8;
    public static final double TRI_EPS2 = TRI_EPS * TRI_EPS;

    private TriangleQueries() {
    }

    public static boolean pointInTriangleXZ(
            MeshCollider collider,
            int triangle,
            double pointX,
            double pointZ
    ) {
        double inverseDenominator =
                collider.inverseXzDenominator[triangle];

        if (!Double.isFinite(inverseDenominator)) {
            return false;
        }

        int base = triangle * 9;

        double ax = collider.xyz[base];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cz = collider.xyz[base + 8];

        double relativeX = pointX - cx;
        double relativeZ = pointZ - cz;

        double weightA = (
                (bz - cz) * relativeX
                        + (cx - bx) * relativeZ
        ) * inverseDenominator;

        if (
                weightA < -TRI_EPS
                        || weightA > 1.0 + TRI_EPS
        ) {
            return false;
        }

        double weightB = (
                (cz - az) * relativeX
                        + (ax - cx) * relativeZ
        ) * inverseDenominator;

        return weightB >= -TRI_EPS
                && weightA + weightB <= 1.0 + TRI_EPS;
    }

    public static void closestPointOnTriangleXZ(
            MeshCollider collider,
            int triangle,
            double pointX,
            double pointZ,
            double[] result
    ) {
        if (
                pointInTriangleXZ(
                        collider,
                        triangle,
                        pointX,
                        pointZ
                )
        ) {
            result[0] = pointX;
            result[1] = pointZ;
            return;
        }

        int base = triangle * 9;

        double ax = collider.xyz[base];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cz = collider.xyz[base + 8];

        double bestX = ax;
        double bestZ = az;
        double bestDistance2 = Double.POSITIVE_INFINITY;

        double edgeX = bx - ax;
        double edgeZ = bz - az;
        double edgeLength2 =
                edgeX * edgeX + edgeZ * edgeZ;

        double edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - ax) * edgeX
                        + (pointZ - az) * edgeZ)
                        / edgeLength2
        );

        double candidateX = ax + edgeX * edgeT;
        double candidateZ = az + edgeZ * edgeT;
        double offsetX = pointX - candidateX;
        double offsetZ = pointZ - candidateZ;
        double distance2 =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestDistance2 = distance2;
            bestX = candidateX;
            bestZ = candidateZ;
        }

        edgeX = cx - bx;
        edgeZ = cz - bz;
        edgeLength2 = edgeX * edgeX + edgeZ * edgeZ;

        edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - bx) * edgeX
                        + (pointZ - bz) * edgeZ)
                        / edgeLength2
        );

        candidateX = bx + edgeX * edgeT;
        candidateZ = bz + edgeZ * edgeT;
        offsetX = pointX - candidateX;
        offsetZ = pointZ - candidateZ;
        distance2 = offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestDistance2 = distance2;
            bestX = candidateX;
            bestZ = candidateZ;
        }

        edgeX = ax - cx;
        edgeZ = az - cz;
        edgeLength2 = edgeX * edgeX + edgeZ * edgeZ;

        edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - cx) * edgeX
                        + (pointZ - cz) * edgeZ)
                        / edgeLength2
        );

        candidateX = cx + edgeX * edgeT;
        candidateZ = cz + edgeZ * edgeT;
        offsetX = pointX - candidateX;
        offsetZ = pointZ - candidateZ;
        distance2 = offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestX = candidateX;
            bestZ = candidateZ;
        }

        result[0] = bestX;
        result[1] = bestZ;
    }

    public static boolean hasValidDetailedCollider(
            double[][] vertices,
            int[][] faces
    ) {
        return vertices != null
                && vertices.length > 0
                && faces != null
                && faces.length > 0;
    }

    public static boolean validTriIndex(
            double[][] vertices,
            int index
    ) {
        return index >= 0
                && index < vertices.length
                && vertices[index] != null
                && vertices[index].length >= 3;
    }

    public static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return value < minimum
                ? minimum
                : value > maximum
                ? maximum
                : value;
    }

    public static double clamp01(double value) {
        return value <= 0.0
                ? 0.0
                : value >= 1.0
                ? 1.0
                : value;
    }
}