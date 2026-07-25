package engine.render.geometry;

import engine.render.RenderFrame;
import engine.render.RenderSettings;
import engine.render.lighting.LightingCalculator;
import engine.render.lighting.MaterialState;
import engine.render.lighting.ShadowCalculator;
import engine.render.util.RenderWorkerContext;
import java.util.IdentityHashMap;
import objects.GameObject;
import util.AABB;

public final class RenderMeshCompiler {
    private static final double BASIS_EPSILON = 1.0e-20;

    private final RenderFrame frame;
    private final LightingCalculator lightingCalculator;
    private final ShadowCalculator shadowCalculator;

    public final IdentityHashMap<GameObject, RenderMesh> meshDataCache =
            new IdentityHashMap<>(4096);
    public final IdentityHashMap<GameObject, ProjectionCache> projectionCache =
            new IdentityHashMap<>(4096);

    public RenderMeshCompiler(
            RenderFrame frame,
            LightingCalculator lightingCalculator,
            ShadowCalculator shadowCalculator
    ) {
        this.frame = frame;
        this.lightingCalculator = lightingCalculator;
        this.shadowCalculator = shadowCalculator;
    }

    public static RenderMesh compile(
            double[][] vertices,
            int[][] faces,
            double[][] uvs
    ) {
        final int vertexCount = vertices.length;
        final double[] x = new double[vertexCount];
        final double[] y = new double[vertexCount];
        final double[] z = new double[vertexCount];
        final double[] u = new double[vertexCount];
        final double[] v = new double[vertexCount];
        final byte[] validVertices = new byte[vertexCount];

        for (int index = 0; index < vertexCount; index++) {
            final double[] vertex = vertices[index];

            if (
                    vertex != null &&
                            vertex.length >= 3 &&
                            Double.isFinite(vertex[0]) &&
                            Double.isFinite(vertex[1]) &&
                            Double.isFinite(vertex[2])
            ) {
                x[index] = vertex[0];
                y[index] = vertex[1];
                z[index] = vertex[2];
                validVertices[index] = 1;
            }

            if (
                    uvs != null &&
                            index < uvs.length &&
                            uvs[index] != null &&
                            uvs[index].length >= 2
            ) {
                u[index] = uvs[index][0];
                v[index] = uvs[index][1];
            }
        }

        int validFaceCount = 0;

        for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
            final int[] face = faces[faceIndex];

            if (
                    face != null &&
                            face.length == 3 &&
                            validIndex(face[0], vertexCount) &&
                            validIndex(face[1], vertexCount) &&
                            validIndex(face[2], vertexCount) &&
                            validVertices[face[0]] != 0 &&
                            validVertices[face[1]] != 0 &&
                            validVertices[face[2]] != 0 &&
                            face[0] != face[1] &&
                            face[1] != face[2] &&
                            face[2] != face[0]
            ) {
                validFaceCount++;
            }
        }

        final int[] indices = new int[validFaceCount * 3];
        int output = 0;

        for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
            final int[] face = faces[faceIndex];

            if (
                    face == null ||
                            face.length != 3 ||
                            !validIndex(face[0], vertexCount) ||
                            !validIndex(face[1], vertexCount) ||
                            !validIndex(face[2], vertexCount) ||
                            validVertices[face[0]] == 0 ||
                            validVertices[face[1]] == 0 ||
                            validVertices[face[2]] == 0 ||
                            face[0] == face[1] ||
                            face[1] == face[2] ||
                            face[2] == face[0]
            ) {
                continue;
            }

            indices[output++] = face[0];
            indices[output++] = face[1];
            indices[output++] = face[2];
        }

        int anchor0 = -1;

        for (int index = 0; index < vertexCount; index++) {
            if (validVertices[index] != 0) {
                anchor0 = index;
                break;
            }
        }

        int anchor1 = anchor0;
        int anchor2 = anchor0;
        int anchor3 = anchor0;
        int basisMode = 0;
        final double[] inverseBasis = new double[9];

        if (anchor0 >= 0) {
            double maximumLengthSquared = 0.0;

            for (int index = 0; index < vertexCount; index++) {
                if (validVertices[index] == 0) {
                    continue;
                }

                final double dx = x[index] - x[anchor0];
                final double dy = y[index] - y[anchor0];
                final double dz = z[index] - z[anchor0];
                final double lengthSquared =
                        dx * dx + dy * dy + dz * dz;

                if (lengthSquared > maximumLengthSquared) {
                    maximumLengthSquared = lengthSquared;
                    anchor1 = index;
                }
            }

            if (maximumLengthSquared > BASIS_EPSILON) {
                final double d1x = x[anchor1] - x[anchor0];
                final double d1y = y[anchor1] - y[anchor0];
                final double d1z = z[anchor1] - z[anchor0];
                double maximumCrossSquared = 0.0;

                for (int index = 0; index < vertexCount; index++) {
                    if (validVertices[index] == 0) {
                        continue;
                    }

                    final double dx = x[index] - x[anchor0];
                    final double dy = y[index] - y[anchor0];
                    final double dz = z[index] - z[anchor0];
                    final double cx = d1y * dz - d1z * dy;
                    final double cy = d1z * dx - d1x * dz;
                    final double cz = d1x * dy - d1y * dx;
                    final double crossSquared =
                            cx * cx + cy * cy + cz * cz;

                    if (crossSquared > maximumCrossSquared) {
                        maximumCrossSquared = crossSquared;
                        anchor2 = index;
                    }
                }

                if (maximumCrossSquared > BASIS_EPSILON) {
                    final double d2x = x[anchor2] - x[anchor0];
                    final double d2y = y[anchor2] - y[anchor0];
                    final double d2z = z[anchor2] - z[anchor0];
                    final double crossX =
                            d1y * d2z - d1z * d2y;
                    final double crossY =
                            d1z * d2x - d1x * d2z;
                    final double crossZ =
                            d1x * d2y - d1y * d2x;
                    double maximumDeterminant = 0.0;

                    for (
                            int index = 0;
                            index < vertexCount;
                            index++
                    ) {
                        if (validVertices[index] == 0) {
                            continue;
                        }

                        final double d3x =
                                x[index] - x[anchor0];
                        final double d3y =
                                y[index] - y[anchor0];
                        final double d3z =
                                z[index] - z[anchor0];
                        final double determinant = Math.abs(
                                crossX * d3x +
                                        crossY * d3y +
                                        crossZ * d3z
                        );

                        if (determinant > maximumDeterminant) {
                            maximumDeterminant = determinant;
                            anchor3 = index;
                        }
                    }

                    final double d3x;
                    final double d3y;
                    final double d3z;

                    if (maximumDeterminant > BASIS_EPSILON) {
                        basisMode = 3;
                        d3x = x[anchor3] - x[anchor0];
                        d3y = y[anchor3] - y[anchor0];
                        d3z = z[anchor3] - z[anchor0];
                    } else {
                        basisMode = 2;
                        final double inverseCrossLength =
                                1.0 /
                                        Math.sqrt(
                                                maximumCrossSquared
                                        );
                        d3x = crossX * inverseCrossLength;
                        d3y = crossY * inverseCrossLength;
                        d3z = crossZ * inverseCrossLength;
                    }

                    invertBasis(
                            d1x,
                            d1y,
                            d1z,
                            d2x,
                            d2y,
                            d2z,
                            d3x,
                            d3y,
                            d3z,
                            inverseBasis
                    );
                } else {
                    basisMode = 1;
                    final double[] perpendicular =
                            new double[3];

                    makePerpendicular(
                            d1x,
                            d1y,
                            d1z,
                            perpendicular
                    );

                    final double d2x = perpendicular[0];
                    final double d2y = perpendicular[1];
                    final double d2z = perpendicular[2];
                    final double d3x =
                            d1y * d2z - d1z * d2y;
                    final double d3y =
                            d1z * d2x - d1x * d2z;
                    final double d3z =
                            d1x * d2y - d1y * d2x;

                    invertBasis(
                            d1x,
                            d1y,
                            d1z,
                            d2x,
                            d2y,
                            d2z,
                            d3x,
                            d3y,
                            d3z,
                            inverseBasis
                    );
                }
            } else {
                inverseBasis[0] = 1.0;
                inverseBasis[4] = 1.0;
                inverseBasis[8] = 1.0;
            }
        }

        return new RenderMesh(
                vertexCount,
                faces,
                x,
                y,
                z,
                u,
                v,
                indices,
                anchor0,
                anchor1,
                anchor2,
                anchor3,
                basisMode,
                inverseBasis
        );
    }

    public static boolean calculateModelMatrix(
            RenderMesh mesh,
            double[][] currentVertices,
            double[] output
    ) {
        if (
                mesh.anchor0 < 0 ||
                        currentVertices == null ||
                        currentVertices.length != mesh.vertexCount ||
                        !copyPoint(
                                currentVertices,
                                mesh.anchor0,
                                output,
                                0
                        )
        ) {
            return false;
        }

        final double originX = output[0];
        final double originY = output[1];
        final double originZ = output[2];
        double b1x = 1.0;
        double b1y = 0.0;
        double b1z = 0.0;
        double b2x = 0.0;
        double b2y = 1.0;
        double b2z = 0.0;
        double b3x = 0.0;
        double b3y = 0.0;
        double b3z = 1.0;

        if (mesh.basisMode >= 1) {
            if (
                    !copyPoint(
                            currentVertices,
                            mesh.anchor1,
                            output,
                            3
                    )
            ) {
                return false;
            }

            b1x = output[3] - originX;
            b1y = output[4] - originY;
            b1z = output[5] - originZ;
        }

        if (mesh.basisMode >= 2) {
            if (
                    !copyPoint(
                            currentVertices,
                            mesh.anchor2,
                            output,
                            6
                    )
            ) {
                return false;
            }

            b2x = output[6] - originX;
            b2y = output[7] - originY;
            b2z = output[8] - originZ;
        } else if (mesh.basisMode == 1) {
            final double[] perpendicular = new double[3];
            makePerpendicular(
                    b1x,
                    b1y,
                    b1z,
                    perpendicular
            );
            b2x = perpendicular[0];
            b2y = perpendicular[1];
            b2z = perpendicular[2];
        }

        if (mesh.basisMode == 3) {
            if (
                    !copyPoint(
                            currentVertices,
                            mesh.anchor3,
                            output,
                            9
                    )
            ) {
                return false;
            }

            b3x = output[9] - originX;
            b3y = output[10] - originY;
            b3z = output[11] - originZ;
        } else if (mesh.basisMode >= 1) {
            b3x = b1y * b2z - b1z * b2y;
            b3y = b1z * b2x - b1x * b2z;
            b3z = b1x * b2y - b1y * b2x;

            final double lengthSquared =
                    b3x * b3x + b3y * b3y + b3z * b3z;

            if (lengthSquared <= BASIS_EPSILON) {
                return false;
            }

            final double inverseLength =
                    1.0 / Math.sqrt(lengthSquared);
            b3x *= inverseLength;
            b3y *= inverseLength;
            b3z *= inverseLength;
        }

        final double[] inverse = mesh.inverseBasis;
        final double m00 =
                b1x * inverse[0] +
                        b2x * inverse[3] +
                        b3x * inverse[6];
        final double m01 =
                b1x * inverse[1] +
                        b2x * inverse[4] +
                        b3x * inverse[7];
        final double m02 =
                b1x * inverse[2] +
                        b2x * inverse[5] +
                        b3x * inverse[8];
        final double m10 =
                b1y * inverse[0] +
                        b2y * inverse[3] +
                        b3y * inverse[6];
        final double m11 =
                b1y * inverse[1] +
                        b2y * inverse[4] +
                        b3y * inverse[7];
        final double m12 =
                b1y * inverse[2] +
                        b2y * inverse[5] +
                        b3y * inverse[8];
        final double m20 =
                b1z * inverse[0] +
                        b2z * inverse[3] +
                        b3z * inverse[6];
        final double m21 =
                b1z * inverse[1] +
                        b2z * inverse[4] +
                        b3z * inverse[7];
        final double m22 =
                b1z * inverse[2] +
                        b2z * inverse[5] +
                        b3z * inverse[8];
        final double localOriginX =
                mesh.x[mesh.anchor0];
        final double localOriginY =
                mesh.y[mesh.anchor0];
        final double localOriginZ =
                mesh.z[mesh.anchor0];

        output[0] = m00;
        output[1] = m01;
        output[2] = m02;
        output[3] =
                originX -
                        m00 * localOriginX -
                        m01 * localOriginY -
                        m02 * localOriginZ;
        output[4] = m10;
        output[5] = m11;
        output[6] = m12;
        output[7] =
                originY -
                        m10 * localOriginX -
                        m11 * localOriginY -
                        m12 * localOriginZ;
        output[8] = m20;
        output[9] = m21;
        output[10] = m22;
        output[11] =
                originZ -
                        m20 * localOriginX -
                        m21 * localOriginY -
                        m22 * localOriginZ;

        return true;
    }

    private static boolean copyPoint(
            double[][] vertices,
            int index,
            double[] output,
            int offset
    ) {
        if (!validIndex(index, vertices.length)) {
            return false;
        }

        final double[] point = vertices[index];

        if (
                point == null ||
                        point.length < 3 ||
                        !Double.isFinite(point[0]) ||
                        !Double.isFinite(point[1]) ||
                        !Double.isFinite(point[2])
        ) {
            return false;
        }

        output[offset] = point[0];
        output[offset + 1] = point[1];
        output[offset + 2] = point[2];
        return true;
    }

    private static void makePerpendicular(
            double x,
            double y,
            double z,
            double[] output
    ) {
        final double px;
        final double py;
        final double pz;

        if (
                Math.abs(x) <= Math.abs(y) &&
                        Math.abs(x) <= Math.abs(z)
        ) {
            px = 0.0;
            py = -z;
            pz = y;
        } else if (Math.abs(y) <= Math.abs(z)) {
            px = -z;
            py = 0.0;
            pz = x;
        } else {
            px = -y;
            py = x;
            pz = 0.0;
        }

        final double inverseLength =
                1.0 / Math.sqrt(px * px + py * py + pz * pz);

        output[0] = px * inverseLength;
        output[1] = py * inverseLength;
        output[2] = pz * inverseLength;
    }

    private static void invertBasis(
            double b1x,
            double b1y,
            double b1z,
            double b2x,
            double b2y,
            double b2z,
            double b3x,
            double b3y,
            double b3z,
            double[] output
    ) {
        final double determinant =
                b1x * (b2y * b3z - b3y * b2z) -
                        b2x * (b1y * b3z - b3y * b1z) +
                        b3x * (b1y * b2z - b2y * b1z);
        final double inverseDeterminant =
                1.0 / determinant;

        output[0] =
                (b2y * b3z - b3y * b2z) *
                        inverseDeterminant;
        output[1] =
                (b3x * b2z - b2x * b3z) *
                        inverseDeterminant;
        output[2] =
                (b2x * b3y - b3x * b2y) *
                        inverseDeterminant;
        output[3] =
                (b3y * b1z - b1y * b3z) *
                        inverseDeterminant;
        output[4] =
                (b1x * b3z - b3x * b1z) *
                        inverseDeterminant;
        output[5] =
                (b3x * b1y - b1x * b3y) *
                        inverseDeterminant;
        output[6] =
                (b1y * b2z - b2y * b1z) *
                        inverseDeterminant;
        output[7] =
                (b2x * b1z - b1x * b2z) *
                        inverseDeterminant;
        output[8] =
                (b1x * b2y - b2x * b1y) *
                        inverseDeterminant;
    }

    public void buildTrianglesForWorker(
            RenderWorkerContext context,
            int workerIndex,
            int width,
            int height,
            double projectionScale,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY,
            double cameraX,
            double cameraY,
            double cameraZ,
            GameObject playerBody
    ) {
        for (int item = 0; item < frame.itemCount; item++) {
            if (frame.itemWorkers[item] != workerIndex) {
                continue;
            }

            final GameObject object = frame.objects[item];
            final RenderMesh mesh = frame.meshes[item];
            final ProjectionCache projected =
                    frame.projections[item];
            final MaterialState material =
                    frame.materials[item];
            final AABB objectBounds = frame.bounds[item];
            final boolean doubleSided =
                    frame.doubleSided[item] != 0;
            final double nearDistance =
                    frame.nearDistances[item];

            if (
                    !projected.update(
                            mesh,
                            frame.worldVertices[item],
                            context.modelMatrix,
                            context.modelViewMatrix,
                            cameraX,
                            cameraY,
                            cameraZ,
                            frame.cosineYaw,
                            frame.sineYaw,
                            frame.cosinePitch,
                            frame.sinePitch,
                            frame.zBiases[item],
                            projectionScale,
                            width,
                            height
                    )
            ) {
                continue;
            }

            shadowCalculator.calculateObjectShadowFlags(
                    context,
                    object,
                    objectBounds,
                    cameraX,
                    cameraY,
                    cameraZ,
                    farDistance
            );

            final int[] indices = mesh.indices;

            for (
                    int faceOffset = 0;
                    faceOffset < indices.length;
                    faceOffset += 3
            ) {
                buildFaceTriangle(
                        context,
                        mesh,
                        projected,
                        indices[faceOffset],
                        indices[faceOffset + 1],
                        indices[faceOffset + 2],
                        nearDistance,
                        farDistance,
                        projectionScale,
                        width,
                        height,
                        material,
                        doubleSided,
                        material.wireframe
                );
            }
        }
    }

    private void buildFaceTriangle(
            RenderWorkerContext context,
            RenderMesh mesh,
            ProjectionCache projected,
            int index0,
            int index1,
            int index2,
            double nearDistance,
            double farDistance,
            double projectionScale,
            int width,
            int height,
            MaterialState material,
            boolean doubleSided,
            boolean wireframe
    ) {
        if (
                projected.cameraZ[index0] >= farDistance &&
                        projected.cameraZ[index1] >= farDistance &&
                        projected.cameraZ[index2] >= farDistance
        ) {
            return;
        }

        final double[] worldX = projected.worldX;
        final double[] worldY = projected.worldY;
        final double[] worldZ = projected.worldZ;
        final double point0X = worldX[index0];
        final double point0Y = worldY[index0];
        final double point0Z = worldZ[index0];
        final double point1X = worldX[index1];
        final double point1Y = worldY[index1];
        final double point1Z = worldZ[index1];
        final double point2X = worldX[index2];
        final double point2Y = worldY[index2];
        final double point2Z = worldZ[index2];
        final double edge1X = point1X - point0X;
        final double edge1Y = point1Y - point0Y;
        final double edge1Z = point1Z - point0Z;
        final double edge2X = point2X - point0X;
        final double edge2Y = point2Y - point0Y;
        final double edge2Z = point2Z - point0Z;

        double normalX =
                edge1Y * edge2Z - edge1Z * edge2Y;
        double normalY =
                edge1Z * edge2X - edge1X * edge2Z;
        double normalZ =
                edge1X * edge2Y - edge1Y * edge2X;

        final double normalLength = Math.sqrt(
                normalX * normalX +
                        normalY * normalY +
                        normalZ * normalZ
        );

        if (normalLength < 1.0e-12) {
            return;
        }

        final double inverseNormalLength =
                1.0 / normalLength;
        normalX *= inverseNormalLength;
        normalY *= inverseNormalLength;
        normalZ *= inverseNormalLength;

        final double centerX =
                (point0X + point1X + point2X) / 3.0;
        final double centerY =
                (point0Y + point1Y + point2Y) / 3.0;
        final double centerZ =
                (point0Z + point1Z + point2Z) / 3.0;

        final double[] lighting =
                lightingCalculator.calculateLighting(
                        context,
                        normalX,
                        normalY,
                        normalZ,
                        centerX,
                        centerY,
                        centerZ,
                        doubleSided
                );

        final int shadeRed = to255(
                clamp01(
                        material.ambient +
                                material.diffuse * lighting[0]
                )
        );
        final int shadeGreen = to255(
                clamp01(
                        material.ambient +
                                material.diffuse * lighting[1]
                )
        );
        final int shadeBlue = to255(
                clamp01(
                        material.ambient +
                                material.diffuse * lighting[2]
                )
        );
        final double clipDistance =
                nearDistance +
                        RenderSettings.NEAR_CLIP_EPSILON;

        if (
                projected.cameraZ[index0] > clipDistance &&
                        projected.cameraZ[index1] > clipDistance &&
                        projected.cameraZ[index2] > clipDistance
        ) {
            context.batch.addCachedProjectedTriangle(
                    projected,
                    index0,
                    index1,
                    index2,
                    width,
                    height,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    doubleSided,
                    wireframe
            );
            return;
        }

        final double[] cameraX = projected.cameraX;
        final double[] cameraY = projected.cameraY;
        final double[] cameraZ = projected.cameraZ;
        final double[] u = mesh.u;
        final double[] v = mesh.v;

        context.clipInputX[0] = cameraX[index0];
        context.clipInputY[0] = cameraY[index0];
        context.clipInputZ[0] = cameraZ[index0];
        context.clipInputU[0] = u[index0];
        context.clipInputV[0] = v[index0];
        context.clipInputX[1] = cameraX[index1];
        context.clipInputY[1] = cameraY[index1];
        context.clipInputZ[1] = cameraZ[index1];
        context.clipInputU[1] = u[index1];
        context.clipInputV[1] = v[index1];
        context.clipInputX[2] = cameraX[index2];
        context.clipInputY[2] = cameraY[index2];
        context.clipInputZ[2] = cameraZ[index2];
        context.clipInputU[2] = u[index2];
        context.clipInputV[2] = v[index2];

        final int clippedCount =
                NearPlaneClipper.clipPolygonNear(
                        clipDistance,
                        context.clipInputX,
                        context.clipInputY,
                        context.clipInputZ,
                        context.clipInputU,
                        context.clipInputV,
                        3,
                        context.clipOutputX,
                        context.clipOutputY,
                        context.clipOutputZ,
                        context.clipOutputU,
                        context.clipOutputV
                );

        if (clippedCount < 3) {
            return;
        }

        context.batch.addProjectedTriangle(
                context.clipOutputX[0],
                context.clipOutputY[0],
                context.clipOutputZ[0],
                context.clipOutputU[0],
                context.clipOutputV[0],
                context.clipOutputX[1],
                context.clipOutputY[1],
                context.clipOutputZ[1],
                context.clipOutputU[1],
                context.clipOutputV[1],
                context.clipOutputX[2],
                context.clipOutputY[2],
                context.clipOutputZ[2],
                context.clipOutputU[2],
                context.clipOutputV[2],
                projectionScale,
                width,
                height,
                farDistance,
                material,
                shadeRed,
                shadeGreen,
                shadeBlue,
                doubleSided,
                wireframe
        );

        if (clippedCount == 4) {
            context.batch.addProjectedTriangle(
                    context.clipOutputX[0],
                    context.clipOutputY[0],
                    context.clipOutputZ[0],
                    context.clipOutputU[0],
                    context.clipOutputV[0],
                    context.clipOutputX[2],
                    context.clipOutputY[2],
                    context.clipOutputZ[2],
                    context.clipOutputU[2],
                    context.clipOutputV[2],
                    context.clipOutputX[3],
                    context.clipOutputY[3],
                    context.clipOutputZ[3],
                    context.clipOutputU[3],
                    context.clipOutputV[3],
                    projectionScale,
                    width,
                    height,
                    farDistance,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    doubleSided,
                    wireframe
            );
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int to255(double value) {
        return clamp255((int) (value * 255.0 + 0.5));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static boolean validIndex(int index, int length) {
        return index >= 0 && index < length;
    }
}