package engine.physics.collision;

import util.AABB;

import static engine.physics.collision.TriangleQueries.TRI_EPS;
import static engine.physics.collision.TriangleQueries.TRI_EPS2;
import static engine.physics.collision.TriangleQueries.validTriIndex;

public final class MeshCollider {

    public static final byte TRI_WALL = 1;
    public static final byte TRI_FLOOR = 2;
    public static final byte TRI_CEILING = 4;

    private static final double MAX_WALL_NORMAL_Y = 0.35;
    private static final double MIN_WALKABLE_NORMAL_Y =
            MAX_WALL_NORMAL_Y;

    final double[][] sourceVertices;
    final int[][] sourceFaces;
    final long sourceRevision;
    final long sourceProbe;

    final boolean hasSourceBounds;
    final double sourceMinX;
    final double sourceMaxX;
    final double sourceMinY;
    final double sourceMaxY;
    final double sourceMinZ;
    final double sourceMaxZ;

    public final int triangleCount;
    public final double[] xyz;

    public final double[] minX;
    public final double[] maxX;
    public final double[] minY;
    public final double[] maxY;
    public final double[] minZ;
    public final double[] maxZ;

    public final double[] normalX;
    public final double[] normalY;
    public final double[] normalZ;
    public final double[] planeD;
    public final double[] inverseXzDenominator;
    public final byte[] flags;

    public final MeshBvh allBvh;
    public final MeshBvh wallBvh;
    public final MeshBvh floorBvh;
    public final MeshBvh ceilingBvh;

    private MeshCollider(
            double[][] sourceVertices,
            int[][] sourceFaces,
            AABB sourceBounds,
            long sourceRevision,
            long sourceProbe,
            int triangleCount,
            double[] xyz,
            double[] minX,
            double[] maxX,
            double[] minY,
            double[] maxY,
            double[] minZ,
            double[] maxZ,
            double[] normalX,
            double[] normalY,
            double[] normalZ,
            double[] planeD,
            double[] inverseXzDenominator,
            byte[] flags
    ) {
        this.sourceVertices = sourceVertices;
        this.sourceFaces = sourceFaces;
        this.sourceRevision = sourceRevision;
        this.sourceProbe = sourceProbe;

        this.hasSourceBounds = sourceBounds != null;

        this.sourceMinX =
                sourceBounds == null ? 0.0 : sourceBounds.minX;
        this.sourceMaxX =
                sourceBounds == null ? 0.0 : sourceBounds.maxX;
        this.sourceMinY =
                sourceBounds == null ? 0.0 : sourceBounds.minY;
        this.sourceMaxY =
                sourceBounds == null ? 0.0 : sourceBounds.maxY;
        this.sourceMinZ =
                sourceBounds == null ? 0.0 : sourceBounds.minZ;
        this.sourceMaxZ =
                sourceBounds == null ? 0.0 : sourceBounds.maxZ;

        this.triangleCount = triangleCount;
        this.xyz = xyz;

        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;

        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.planeD = planeD;
        this.inverseXzDenominator = inverseXzDenominator;
        this.flags = flags;

        this.allBvh = MeshBvhBuilder.build(this, (byte) 0);
        this.wallBvh = MeshBvhBuilder.build(this, TRI_WALL);
        this.floorBvh = MeshBvhBuilder.build(this, TRI_FLOOR);
        this.ceilingBvh =
                MeshBvhBuilder.build(this, TRI_CEILING);
    }

    public static MeshCollider build(
            double[][] vertices,
            int[][] faces,
            AABB sourceBounds,
            long revision,
            long probe
    ) {
        int validCount = 0;

        for (int i = 0; i < faces.length; i++) {
            int[] face = faces[i];

            if (
                    face != null
                            && face.length >= 3
                            && validTriIndex(vertices, face[0])
                            && validTriIndex(vertices, face[1])
                            && validTriIndex(vertices, face[2])
                            && !isDegenerate(
                            vertices[face[0]],
                            vertices[face[1]],
                            vertices[face[2]]
                    )
            ) {
                validCount++;
            }
        }

        if (validCount == 0) {
            return null;
        }

        double[] xyz = new double[validCount * 9];

        double[] minX = new double[validCount];
        double[] maxX = new double[validCount];
        double[] minY = new double[validCount];
        double[] maxY = new double[validCount];
        double[] minZ = new double[validCount];
        double[] maxZ = new double[validCount];

        double[] normalX = new double[validCount];
        double[] normalY = new double[validCount];
        double[] normalZ = new double[validCount];
        double[] planeD = new double[validCount];

        double[] inverseXzDenominator =
                new double[validCount];

        byte[] flags = new byte[validCount];

        int triangle = 0;

        for (int i = 0; i < faces.length; i++) {
            int[] face = faces[i];

            if (
                    face == null
                            || face.length < 3
                            || !validTriIndex(vertices, face[0])
                            || !validTriIndex(vertices, face[1])
                            || !validTriIndex(vertices, face[2])
            ) {
                continue;
            }

            double[] a = vertices[face[0]];
            double[] b = vertices[face[1]];
            double[] c = vertices[face[2]];

            double edge1X = b[0] - a[0];
            double edge1Y = b[1] - a[1];
            double edge1Z = b[2] - a[2];

            double edge2X = c[0] - a[0];
            double edge2Y = c[1] - a[1];
            double edge2Z = c[2] - a[2];

            double rawNormalX =
                    edge1Y * edge2Z
                            - edge1Z * edge2Y;

            double rawNormalY =
                    edge1Z * edge2X
                            - edge1X * edge2Z;

            double rawNormalZ =
                    edge1X * edge2Y
                            - edge1Y * edge2X;

            double normalLength2 =
                    rawNormalX * rawNormalX
                            + rawNormalY * rawNormalY
                            + rawNormalZ * rawNormalZ;

            if (normalLength2 <= TRI_EPS2) {
                continue;
            }

            int base = triangle * 9;

            xyz[base] = a[0];
            xyz[base + 1] = a[1];
            xyz[base + 2] = a[2];

            xyz[base + 3] = b[0];
            xyz[base + 4] = b[1];
            xyz[base + 5] = b[2];

            xyz[base + 6] = c[0];
            xyz[base + 7] = c[1];
            xyz[base + 8] = c[2];

            minX[triangle] = Math.min(
                    a[0],
                    Math.min(b[0], c[0])
            );

            maxX[triangle] = Math.max(
                    a[0],
                    Math.max(b[0], c[0])
            );

            minY[triangle] = Math.min(
                    a[1],
                    Math.min(b[1], c[1])
            );

            maxY[triangle] = Math.max(
                    a[1],
                    Math.max(b[1], c[1])
            );

            minZ[triangle] = Math.min(
                    a[2],
                    Math.min(b[2], c[2])
            );

            maxZ[triangle] = Math.max(
                    a[2],
                    Math.max(b[2], c[2])
            );

            double inverseNormalLength =
                    1.0 / Math.sqrt(normalLength2);

            double nx = rawNormalX * inverseNormalLength;
            double ny = rawNormalY * inverseNormalLength;
            double nz = rawNormalZ * inverseNormalLength;

            normalX[triangle] = nx;
            normalY[triangle] = ny;
            normalZ[triangle] = nz;

            planeD[triangle] = -(
                    nx * a[0]
                            + ny * a[1]
                            + nz * a[2]
            );

            double denominator =
                    (b[2] - c[2]) * (a[0] - c[0])
                            + (c[0] - b[0])
                            * (a[2] - c[2]);

            inverseXzDenominator[triangle] =
                    Math.abs(denominator) <= TRI_EPS
                            ? Double.NaN
                            : 1.0 / denominator;

            byte triangleFlags = 0;

            if (Math.abs(ny) <= MAX_WALL_NORMAL_Y) {
                triangleFlags |= TRI_WALL;
            }

            if (ny >= MIN_WALKABLE_NORMAL_Y) {
                triangleFlags |= TRI_FLOOR;
            } else if (ny <= -MIN_WALKABLE_NORMAL_Y) {
                triangleFlags |= TRI_CEILING;
            }

            flags[triangle] = triangleFlags;
            triangle++;
        }

        return new MeshCollider(
                vertices,
                faces,
                sourceBounds,
                revision,
                probe,
                validCount,
                xyz,
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ,
                normalX,
                normalY,
                normalZ,
                planeD,
                inverseXzDenominator,
                flags
        );
    }

    boolean isCurrent(
            double[][] vertices,
            int[][] faces,
            AABB bounds,
            long revision,
            long probe
    ) {
        if (
                sourceVertices != vertices
                        || sourceFaces != faces
                        || sourceRevision != revision
                        || hasSourceBounds != (bounds != null)
        ) {
            return false;
        }

        if (
                bounds != null
                        && (
                        sourceMinX != bounds.minX
                                || sourceMaxX != bounds.maxX
                                || sourceMinY != bounds.minY
                                || sourceMaxY != bounds.maxY
                                || sourceMinZ != bounds.minZ
                                || sourceMaxZ != bounds.maxZ
                )
        ) {
            return false;
        }

        return revision != MeshColliderCache.NO_GEOMETRY_REVISION
                || sourceProbe == probe;
    }

    private static boolean isDegenerate(
            double[] a,
            double[] b,
            double[] c
    ) {
        double edge1X = b[0] - a[0];
        double edge1Y = b[1] - a[1];
        double edge1Z = b[2] - a[2];

        double edge2X = c[0] - a[0];
        double edge2Y = c[1] - a[1];
        double edge2Z = c[2] - a[2];

        double normalX =
                edge1Y * edge2Z
                        - edge1Z * edge2Y;

        double normalY =
                edge1Z * edge2X
                        - edge1X * edge2Z;

        double normalZ =
                edge1X * edge2Y
                        - edge1Y * edge2X;

        return normalX * normalX
                + normalY * normalY
                + normalZ * normalZ
                <= TRI_EPS2;
    }
}