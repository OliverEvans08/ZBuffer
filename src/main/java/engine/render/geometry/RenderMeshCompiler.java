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

    private static final double BASIS_EPSILON =
            1.0e-20;

    private final RenderFrame frame;
    private final LightingCalculator lightingCalculator;
    private final ShadowCalculator shadowCalculator;

    public final IdentityHashMap<GameObject, RenderMesh> meshDataCache =
            new IdentityHashMap<>(4096);

    public final IdentityHashMap<GameObject, ProjectionCache> projectionCache =
            new IdentityHashMap<>(4096);

    public final IdentityHashMap<GameObject, RenderMeshChunkLayout> chunkLayoutCache =
            new IdentityHashMap<>(4096);

    public final IdentityHashMap<GameObject, byte[]> chunkVisibilityCache =
            new IdentityHashMap<>(4096);

    private RenderChunkSettings chunkSettings =
            RenderChunkSettings.fromSystemProperties();

    public RenderMeshCompiler(
            RenderFrame frame,
            LightingCalculator lightingCalculator,
            ShadowCalculator shadowCalculator
    ) {
        this.frame = frame;
        this.lightingCalculator = lightingCalculator;
        this.shadowCalculator = shadowCalculator;
    }

    public RenderChunkSettings getChunkSettings() {
        return chunkSettings;
    }

    /**
     * Changes the policy used by future mesh compilations.
     *
     * <p>Compiled geometry is invalidated because face membership and the
     * benefit decision are both policy-dependent. Projection buffers remain
     * reusable and will resize or invalidate naturally when the replacement
     * RenderMesh is observed.</p>
     */
    public void setChunkSettings(
            RenderChunkSettings settings
    ) {
        if (settings == null) {
            throw new NullPointerException("settings");
        }

        if (settings.equals(chunkSettings)) {
            return;
        }

        chunkSettings = settings;
        meshDataCache.clear();
        chunkLayoutCache.clear();
        chunkVisibilityCache.clear();
    }

    public static RenderMesh compile(
            double[][] vertices,
            int[][] faces,
            double[][] uvs
    ) {
        return compileChunked(
                vertices,
                faces,
                uvs,
                RenderChunkSettings.disabled()
        ).mesh();
    }

    public static RenderMeshCompilation compileChunked(
            double[][] vertices,
            int[][] faces,
            double[][] uvs,
            RenderChunkSettings chunkSettings
    ) {
        if (vertices == null) {
            throw new NullPointerException("vertices");
        }

        if (faces == null) {
            throw new NullPointerException("faces");
        }

        if (chunkSettings == null) {
            throw new NullPointerException("chunkSettings");
        }

        final int vertexCount =
                vertices.length;

        final double[] x =
                new double[vertexCount];

        final double[] y =
                new double[vertexCount];

        final double[] z =
                new double[vertexCount];

        final double[] u =
                new double[vertexCount];

        final double[] v =
                new double[vertexCount];

        final byte[] validVertices =
                new byte[vertexCount];

        for (
                int index = 0;
                index < vertexCount;
                index++
        ) {
            final double[] vertex =
                    vertices[index];

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
                final double textureU =
                        uvs[index][0];

                final double textureV =
                        uvs[index][1];

                u[index] =
                        Double.isFinite(textureU)
                                ? textureU
                                : 0.0;

                v[index] =
                        Double.isFinite(textureV)
                                ? textureV
                                : 0.0;
            }
        }

        int validFaceCount = 0;

        for (
                int faceIndex = 0;
                faceIndex < faces.length;
                faceIndex++
        ) {
            if (
                    isValidFace(
                            faces[faceIndex],
                            vertexCount,
                            validVertices
                    )
            ) {
                validFaceCount++;
            }
        }

        final int[] indices =
                new int[
                        validFaceCount * 3
                        ];

        int output = 0;

        for (
                int faceIndex = 0;
                faceIndex < faces.length;
                faceIndex++
        ) {
            final int[] face =
                    faces[faceIndex];

            if (
                    !isValidFace(
                            face,
                            vertexCount,
                            validVertices
                    )
            ) {
                continue;
            }

            indices[output++] = face[0];
            indices[output++] = face[1];
            indices[output++] = face[2];
        }

        int anchor0 = -1;

        for (
                int index = 0;
                index < vertexCount;
                index++
        ) {
            if (validVertices[index] != 0) {
                anchor0 = index;
                break;
            }
        }

        int anchor1 = anchor0;
        int anchor2 = anchor0;
        int anchor3 = anchor0;
        int basisMode = 0;

        final double[] inverseBasis =
                new double[9];

        if (anchor0 >= 0) {
            double maximumLengthSquared =
                    0.0;

            for (
                    int index = 0;
                    index < vertexCount;
                    index++
            ) {
                if (validVertices[index] == 0) {
                    continue;
                }

                final double deltaX =
                        x[index] -
                                x[anchor0];

                final double deltaY =
                        y[index] -
                                y[anchor0];

                final double deltaZ =
                        z[index] -
                                z[anchor0];

                final double lengthSquared =
                        deltaX * deltaX +
                                deltaY * deltaY +
                                deltaZ * deltaZ;

                if (
                        lengthSquared >
                                maximumLengthSquared
                ) {
                    maximumLengthSquared =
                            lengthSquared;

                    anchor1 = index;
                }
            }

            if (
                    maximumLengthSquared >
                            BASIS_EPSILON
            ) {
                final double direction1X =
                        x[anchor1] -
                                x[anchor0];

                final double direction1Y =
                        y[anchor1] -
                                y[anchor0];

                final double direction1Z =
                        z[anchor1] -
                                z[anchor0];

                double maximumCrossSquared =
                        0.0;

                for (
                        int index = 0;
                        index < vertexCount;
                        index++
                ) {
                    if (validVertices[index] == 0) {
                        continue;
                    }

                    final double deltaX =
                            x[index] -
                                    x[anchor0];

                    final double deltaY =
                            y[index] -
                                    y[anchor0];

                    final double deltaZ =
                            z[index] -
                                    z[anchor0];

                    final double crossX =
                            direction1Y * deltaZ -
                                    direction1Z * deltaY;

                    final double crossY =
                            direction1Z * deltaX -
                                    direction1X * deltaZ;

                    final double crossZ =
                            direction1X * deltaY -
                                    direction1Y * deltaX;

                    final double crossSquared =
                            crossX * crossX +
                                    crossY * crossY +
                                    crossZ * crossZ;

                    if (
                            crossSquared >
                                    maximumCrossSquared
                    ) {
                        maximumCrossSquared =
                                crossSquared;

                        anchor2 = index;
                    }
                }

                if (
                        maximumCrossSquared >
                                BASIS_EPSILON
                ) {
                    final double direction2X =
                            x[anchor2] -
                                    x[anchor0];

                    final double direction2Y =
                            y[anchor2] -
                                    y[anchor0];

                    final double direction2Z =
                            z[anchor2] -
                                    z[anchor0];

                    final double crossX =
                            direction1Y * direction2Z -
                                    direction1Z * direction2Y;

                    final double crossY =
                            direction1Z * direction2X -
                                    direction1X * direction2Z;

                    final double crossZ =
                            direction1X * direction2Y -
                                    direction1Y * direction2X;

                    double maximumDeterminant =
                            0.0;

                    for (
                            int index = 0;
                            index < vertexCount;
                            index++
                    ) {
                        if (validVertices[index] == 0) {
                            continue;
                        }

                        final double direction3X =
                                x[index] -
                                        x[anchor0];

                        final double direction3Y =
                                y[index] -
                                        y[anchor0];

                        final double direction3Z =
                                z[index] -
                                        z[anchor0];

                        final double determinant =
                                Math.abs(
                                        crossX * direction3X +
                                                crossY * direction3Y +
                                                crossZ * direction3Z
                                );

                        if (
                                determinant >
                                        maximumDeterminant
                        ) {
                            maximumDeterminant =
                                    determinant;

                            anchor3 = index;
                        }
                    }

                    final double direction3X;
                    final double direction3Y;
                    final double direction3Z;

                    if (
                            maximumDeterminant >
                                    BASIS_EPSILON
                    ) {
                        basisMode = 3;

                        direction3X =
                                x[anchor3] -
                                        x[anchor0];

                        direction3Y =
                                y[anchor3] -
                                        y[anchor0];

                        direction3Z =
                                z[anchor3] -
                                        z[anchor0];
                    } else {
                        basisMode = 2;

                        final double inverseCrossLength =
                                1.0 /
                                        Math.sqrt(
                                                maximumCrossSquared
                                        );

                        direction3X =
                                crossX *
                                        inverseCrossLength;

                        direction3Y =
                                crossY *
                                        inverseCrossLength;

                        direction3Z =
                                crossZ *
                                        inverseCrossLength;
                    }

                    invertBasis(
                            direction1X,
                            direction1Y,
                            direction1Z,
                            direction2X,
                            direction2Y,
                            direction2Z,
                            direction3X,
                            direction3Y,
                            direction3Z,
                            inverseBasis
                    );
                } else {
                    basisMode = 1;

                    final double absoluteX =
                            Math.abs(
                                    direction1X
                            );

                    final double absoluteY =
                            Math.abs(
                                    direction1Y
                            );

                    final double absoluteZ =
                            Math.abs(
                                    direction1Z
                            );

                    double direction2X;
                    double direction2Y;
                    double direction2Z;

                    if (
                            absoluteX <= absoluteY &&
                                    absoluteX <= absoluteZ
                    ) {
                        direction2X = 0.0;
                        direction2Y = -direction1Z;
                        direction2Z = direction1Y;
                    } else if (
                            absoluteY <= absoluteZ
                    ) {
                        direction2X = -direction1Z;
                        direction2Y = 0.0;
                        direction2Z = direction1X;
                    } else {
                        direction2X = -direction1Y;
                        direction2Y = direction1X;
                        direction2Z = 0.0;
                    }

                    final double inverseLength =
                            1.0 /
                                    Math.sqrt(
                                            direction2X * direction2X +
                                                    direction2Y * direction2Y +
                                                    direction2Z * direction2Z
                                    );

                    direction2X *= inverseLength;
                    direction2Y *= inverseLength;
                    direction2Z *= inverseLength;

                    final double direction3X =
                            direction1Y * direction2Z -
                                    direction1Z * direction2Y;

                    final double direction3Y =
                            direction1Z * direction2X -
                                    direction1X * direction2Z;

                    final double direction3Z =
                            direction1X * direction2Y -
                                    direction1Y * direction2X;

                    invertBasis(
                            direction1X,
                            direction1Y,
                            direction1Z,
                            direction2X,
                            direction2Y,
                            direction2Z,
                            direction3X,
                            direction3Y,
                            direction3Z,
                            inverseBasis
                    );
                }
            } else {
                inverseBasis[0] = 1.0;
                inverseBasis[4] = 1.0;
                inverseBasis[8] = 1.0;
            }
        }

        final RenderMeshChunkLayout chunkLayout =
                RenderMeshPartitioner.partition(
                        x,
                        y,
                        z,
                        indices,
                        chunkSettings
                );

        final RenderMesh mesh =
                new RenderMesh(
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

        return new RenderMeshCompilation(
                mesh,
                chunkLayout
        );
    }

    private static boolean isValidFace(
            int[] face,
            int vertexCount,
            byte[] validVertices
    ) {
        return (
                face != null &&
                        face.length == 3 &&
                        validIndex(
                                face[0],
                                vertexCount
                        ) &&
                        validIndex(
                                face[1],
                                vertexCount
                        ) &&
                        validIndex(
                                face[2],
                                vertexCount
                        ) &&
                        validVertices[face[0]] != 0 &&
                        validVertices[face[1]] != 0 &&
                        validVertices[face[2]] != 0 &&
                        face[0] != face[1] &&
                        face[1] != face[2] &&
                        face[2] != face[0]
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

        double basis1X = 1.0;
        double basis1Y = 0.0;
        double basis1Z = 0.0;

        double basis2X = 0.0;
        double basis2Y = 1.0;
        double basis2Z = 0.0;

        double basis3X = 0.0;
        double basis3Y = 0.0;
        double basis3Z = 1.0;

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

            basis1X = output[3] - originX;
            basis1Y = output[4] - originY;
            basis1Z = output[5] - originZ;
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

            basis2X = output[6] - originX;
            basis2Y = output[7] - originY;
            basis2Z = output[8] - originZ;
        } else if (mesh.basisMode == 1) {
            final double absoluteX =
                    Math.abs(
                            basis1X
                    );

            final double absoluteY =
                    Math.abs(
                            basis1Y
                    );

            final double absoluteZ =
                    Math.abs(
                            basis1Z
                    );

            if (
                    absoluteX <= absoluteY &&
                            absoluteX <= absoluteZ
            ) {
                basis2X = 0.0;
                basis2Y = -basis1Z;
                basis2Z = basis1Y;
            } else if (
                    absoluteY <= absoluteZ
            ) {
                basis2X = -basis1Z;
                basis2Y = 0.0;
                basis2Z = basis1X;
            } else {
                basis2X = -basis1Y;
                basis2Y = basis1X;
                basis2Z = 0.0;
            }

            final double perpendicularLengthSquared =
                    basis2X * basis2X +
                            basis2Y * basis2Y +
                            basis2Z * basis2Z;

            if (
                    perpendicularLengthSquared <=
                            BASIS_EPSILON
            ) {
                return false;
            }

            final double inverseLength =
                    1.0 /
                            Math.sqrt(
                                    perpendicularLengthSquared
                            );

            basis2X *= inverseLength;
            basis2Y *= inverseLength;
            basis2Z *= inverseLength;
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

            basis3X = output[9] - originX;
            basis3Y = output[10] - originY;
            basis3Z = output[11] - originZ;
        } else if (mesh.basisMode >= 1) {
            basis3X =
                    basis1Y * basis2Z -
                            basis1Z * basis2Y;

            basis3Y =
                    basis1Z * basis2X -
                            basis1X * basis2Z;

            basis3Z =
                    basis1X * basis2Y -
                            basis1Y * basis2X;

            final double lengthSquared =
                    basis3X * basis3X +
                            basis3Y * basis3Y +
                            basis3Z * basis3Z;

            if (
                    lengthSquared <=
                            BASIS_EPSILON
            ) {
                return false;
            }

            final double inverseLength =
                    1.0 /
                            Math.sqrt(
                                    lengthSquared
                            );

            basis3X *= inverseLength;
            basis3Y *= inverseLength;
            basis3Z *= inverseLength;
        }

        final double[] inverse =
                mesh.inverseBasis;

        final double matrix00 =
                basis1X * inverse[0] +
                        basis2X * inverse[3] +
                        basis3X * inverse[6];

        final double matrix01 =
                basis1X * inverse[1] +
                        basis2X * inverse[4] +
                        basis3X * inverse[7];

        final double matrix02 =
                basis1X * inverse[2] +
                        basis2X * inverse[5] +
                        basis3X * inverse[8];

        final double matrix10 =
                basis1Y * inverse[0] +
                        basis2Y * inverse[3] +
                        basis3Y * inverse[6];

        final double matrix11 =
                basis1Y * inverse[1] +
                        basis2Y * inverse[4] +
                        basis3Y * inverse[7];

        final double matrix12 =
                basis1Y * inverse[2] +
                        basis2Y * inverse[5] +
                        basis3Y * inverse[8];

        final double matrix20 =
                basis1Z * inverse[0] +
                        basis2Z * inverse[3] +
                        basis3Z * inverse[6];

        final double matrix21 =
                basis1Z * inverse[1] +
                        basis2Z * inverse[4] +
                        basis3Z * inverse[7];

        final double matrix22 =
                basis1Z * inverse[2] +
                        basis2Z * inverse[5] +
                        basis3Z * inverse[8];

        final double localOriginX =
                mesh.x[mesh.anchor0];

        final double localOriginY =
                mesh.y[mesh.anchor0];

        final double localOriginZ =
                mesh.z[mesh.anchor0];

        output[0] = matrix00;
        output[1] = matrix01;
        output[2] = matrix02;

        output[3] =
                originX -
                        matrix00 * localOriginX -
                        matrix01 * localOriginY -
                        matrix02 * localOriginZ;

        output[4] = matrix10;
        output[5] = matrix11;
        output[6] = matrix12;

        output[7] =
                originY -
                        matrix10 * localOriginX -
                        matrix11 * localOriginY -
                        matrix12 * localOriginZ;

        output[8] = matrix20;
        output[9] = matrix21;
        output[10] = matrix22;

        output[11] =
                originZ -
                        matrix20 * localOriginX -
                        matrix21 * localOriginY -
                        matrix22 * localOriginZ;

        return true;
    }

    private static boolean copyPoint(
            double[][] vertices,
            int index,
            double[] output,
            int offset
    ) {
        if (
                !validIndex(
                        index,
                        vertices.length
                )
        ) {
            return false;
        }

        final double[] point =
                vertices[index];

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

    private static void invertBasis(
            double basis1X,
            double basis1Y,
            double basis1Z,
            double basis2X,
            double basis2Y,
            double basis2Z,
            double basis3X,
            double basis3Y,
            double basis3Z,
            double[] output
    ) {
        final double determinant =
                basis1X *
                        (
                                basis2Y * basis3Z -
                                        basis3Y * basis2Z
                        ) -
                        basis2X *
                                (
                                        basis1Y * basis3Z -
                                                basis3Y * basis1Z
                                ) +
                        basis3X *
                                (
                                        basis1Y * basis2Z -
                                                basis2Y * basis1Z
                                );

        final double inverseDeterminant =
                1.0 /
                        determinant;

        output[0] =
                (
                        basis2Y * basis3Z -
                                basis3Y * basis2Z
                ) *
                        inverseDeterminant;

        output[1] =
                (
                        basis3X * basis2Z -
                                basis2X * basis3Z
                ) *
                        inverseDeterminant;

        output[2] =
                (
                        basis2X * basis3Y -
                                basis3X * basis2Y
                ) *
                        inverseDeterminant;

        output[3] =
                (
                        basis3Y * basis1Z -
                                basis1Y * basis3Z
                ) *
                        inverseDeterminant;

        output[4] =
                (
                        basis1X * basis3Z -
                                basis3X * basis1Z
                ) *
                        inverseDeterminant;

        output[5] =
                (
                        basis3X * basis1Y -
                                basis1X * basis3Y
                ) *
                        inverseDeterminant;

        output[6] =
                (
                        basis1Y * basis2Z -
                                basis2Y * basis1Z
                ) *
                        inverseDeterminant;

        output[7] =
                (
                        basis2X * basis1Z -
                                basis1X * basis2Z
                ) *
                        inverseDeterminant;

        output[8] =
                (
                        basis1X * basis2Y -
                                basis2X * basis1Y
                ) *
                        inverseDeterminant;
    }

    private static void writeModelViewMatrix(
            double[] model,
            double[] output,
            int offset,
            double cameraX,
            double cameraY,
            double cameraZ,
            double cosineYaw,
            double sineYaw,
            double cosinePitch,
            double sinePitch,
            double zBias
    ) {
        final double yawZ0 =
                -sineYaw * model[0] +
                        cosineYaw * model[8];

        final double yawZ1 =
                -sineYaw * model[1] +
                        cosineYaw * model[9];

        final double yawZ2 =
                -sineYaw * model[2] +
                        cosineYaw * model[10];

        final double translatedX =
                model[3] - cameraX;

        final double translatedY =
                model[7] - cameraY;

        final double translatedZ =
                model[11] - cameraZ;

        final double yawTranslatedZ =
                -sineYaw * translatedX +
                        cosineYaw * translatedZ;

        output[offset] =
                cosineYaw * model[0] +
                        sineYaw * model[8];

        output[offset + 1] =
                cosineYaw * model[1] +
                        sineYaw * model[9];

        output[offset + 2] =
                cosineYaw * model[2] +
                        sineYaw * model[10];

        output[offset + 3] =
                cosineYaw * translatedX +
                        sineYaw * translatedZ;

        output[offset + 4] =
                cosinePitch * model[4] +
                        sinePitch * yawZ0;

        output[offset + 5] =
                cosinePitch * model[5] +
                        sinePitch * yawZ1;

        output[offset + 6] =
                cosinePitch * model[6] +
                        sinePitch * yawZ2;

        output[offset + 7] =
                cosinePitch * translatedY +
                        sinePitch * yawTranslatedZ;

        output[offset + 8] =
                -sinePitch * model[4] +
                        cosinePitch * yawZ0;

        output[offset + 9] =
                -sinePitch * model[5] +
                        cosinePitch * yawZ1;

        output[offset + 10] =
                -sinePitch * model[6] +
                        cosinePitch * yawZ2;

        output[offset + 11] =
                -sinePitch * translatedY +
                        cosinePitch * yawTranslatedZ +
                        zBias;
    }

    public void buildTrianglesForWorker(
            RenderWorkerContext context,
            TriangleBuildTimings timings,
            int orderedItemStart,
            int orderedItemCount,
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
        final int orderedItemEnd =
                orderedItemStart +
                        orderedItemCount;

        final int[] orderedItems =
                frame.workerOrderedItems;

        final byte[] projectionValid =
                frame.projectionValid;

        final long transformStart =
                System.nanoTime();

        for (
                int orderedPosition = orderedItemStart;
                orderedPosition < orderedItemEnd;
                orderedPosition++
        ) {
            final int item =
                    orderedItems[orderedPosition];

            frame.itemChunksTested[item] = 0;
            frame.itemChunksVisible[item] = 0;
            frame.itemFacesRejected[item] = 0;
            frame.chunkCullValid[item] = 0;

            final RenderMesh mesh =
                    frame.meshes[item];

            final ProjectionCache projected =
                    frame.projections[item];

            final boolean validProjection =
                    projected.update(
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
                    );

            projectionValid[item] =
                    (byte) (
                            validProjection
                                    ? 1
                                    : 0
                    );

            final RenderMeshChunkLayout chunkLayout =
                    frame.chunkLayouts[item];

            if (
                    validProjection &&
                            chunkLayout != null &&
                            chunkLayout.isPartitioned() &&
                            calculateModelMatrix(
                                    mesh,
                                    frame.worldVertices[item],
                                    context.modelMatrix
                            )
            ) {
                writeModelViewMatrix(
                        context.modelMatrix,
                        frame.modelViewMatrices,
                        item * 12,
                        cameraX,
                        cameraY,
                        cameraZ,
                        frame.cosineYaw,
                        frame.sineYaw,
                        frame.cosinePitch,
                        frame.sinePitch,
                        frame.zBiases[item]
                );

                frame.chunkCullValid[item] = 1;
            }
        }

        timings.cameraTransformNanos +=
                System.nanoTime() -
                        transformStart;

        final long clippingBefore =
                timings.clippingNanos;

        final long emissionStart =
                System.nanoTime();

        for (
                int orderedPosition = orderedItemStart;
                orderedPosition < orderedItemEnd;
                orderedPosition++
        ) {
            final int item =
                    orderedItems[orderedPosition];

            if (projectionValid[item] == 0) {
                continue;
            }

            final GameObject object =
                    frame.objects[item];

            final RenderMesh mesh =
                    frame.meshes[item];

            final ProjectionCache projected =
                    frame.projections[item];

            final MaterialState material =
                    frame.materials[item];

            final AABB objectBounds =
                    frame.bounds[item];

            final boolean doubleSided =
                    frame.doubleSided[item] != 0;

            final double nearDistance =
                    frame.nearDistances[item];

            final RenderMeshChunkLayout chunkLayout =
                    frame.chunkLayouts[item];

            final boolean partitioned =
                    chunkLayout != null &&
                            chunkLayout.isPartitioned() &&
                            frame.chunkCullValid[item] != 0;

            final byte[] visibleChunks =
                    frame.visibleMeshChunks[item];

            if (partitioned) {
                final RenderMeshChunk[] chunks =
                        chunkLayout.chunks();

                final int matrixOffset =
                        item * 12;

                int visibleChunkCount = 0;
                int visibleFaceCount = 0;

                for (
                        int chunkIndex = 0;
                        chunkIndex < chunks.length;
                        chunkIndex++
                ) {
                    final RenderMeshChunk chunk =
                            chunks[chunkIndex];

                    final boolean visible =
                            RenderChunkCuller.passesFrustum(
                                    chunk,
                                    frame.modelViewMatrices,
                                    matrixOffset,
                                    nearDistance,
                                    farDistance,
                                    tangentHalfFieldOfViewX,
                                    tangentHalfFieldOfViewY
                            );

                    visibleChunks[chunkIndex] =
                            (byte) (
                                    visible
                                            ? 1
                                            : 0
                            );

                    if (visible) {
                        visibleChunkCount++;
                        visibleFaceCount +=
                                chunk.faceCount;
                    }
                }

                frame.itemChunksTested[item] =
                        chunks.length;

                frame.itemChunksVisible[item] =
                        visibleChunkCount;

                frame.itemFacesRejected[item] =
                        Math.max(
                                0,
                                mesh.faceCount -
                                        visibleFaceCount
                        );

                if (visibleChunkCount == 0) {
                    continue;
                }
            } else {
                frame.itemChunksTested[item] = 0;
                frame.itemChunksVisible[item] = 0;
                frame.itemFacesRejected[item] = 0;
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

            final int[] indices =
                    mesh.indices;

            if (!partitioned) {
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
                            material.wireframe,
                            timings
                    );
                }
            } else {
                final byte[] faceChunks =
                        chunkLayout.faceChunks();

                int face = 0;

                for (
                        int faceOffset = 0;
                        faceOffset < indices.length;
                        faceOffset += 3
                ) {
                    final int chunkIndex =
                            faceChunks[face++] &
                                    0xff;

                    if (
                            visibleChunks[chunkIndex] ==
                                    0
                    ) {
                        continue;
                    }

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
                            material.wireframe,
                            timings
                    );
                }
            }
        }

        final long clippingDuringEmission =
                timings.clippingNanos -
                        clippingBefore;

        final long emissionElapsed =
                System.nanoTime() -
                        emissionStart -
                        clippingDuringEmission;

        if (emissionElapsed > 0L) {
            timings.triangleEmissionNanos +=
                    emissionElapsed;
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
            boolean wireframe,
            TriangleBuildTimings timings
    ) {
        if (
                projected.cameraZ[index0] >= farDistance &&
                        projected.cameraZ[index1] >= farDistance &&
                        projected.cameraZ[index2] >= farDistance
        ) {
            return;
        }

        final double[] worldX =
                projected.worldX;

        final double[] worldY =
                projected.worldY;

        final double[] worldZ =
                projected.worldZ;

        final double point0X = worldX[index0];
        final double point0Y = worldY[index0];
        final double point0Z = worldZ[index0];

        final double point1X = worldX[index1];
        final double point1Y = worldY[index1];
        final double point1Z = worldZ[index1];

        final double point2X = worldX[index2];
        final double point2Y = worldY[index2];
        final double point2Z = worldZ[index2];

        final double edge1X =
                point1X -
                        point0X;

        final double edge1Y =
                point1Y -
                        point0Y;

        final double edge1Z =
                point1Z -
                        point0Z;

        final double edge2X =
                point2X -
                        point0X;

        final double edge2Y =
                point2Y -
                        point0Y;

        final double edge2Z =
                point2Z -
                        point0Z;

        double normalX =
                edge1Y * edge2Z -
                        edge1Z * edge2Y;

        double normalY =
                edge1Z * edge2X -
                        edge1X * edge2Z;

        double normalZ =
                edge1X * edge2Y -
                        edge1Y * edge2X;

        final double normalLengthSquared =
                normalX * normalX +
                        normalY * normalY +
                        normalZ * normalZ;

        if (
                normalLengthSquared <
                        1.0e-24
        ) {
            return;
        }

        final double inverseNormalLength =
                1.0 /
                        Math.sqrt(
                                normalLengthSquared
                        );

        normalX *= inverseNormalLength;
        normalY *= inverseNormalLength;
        normalZ *= inverseNormalLength;

        final double cameraNormalX =
                frame.cosineYaw * normalX +
                        frame.sineYaw * normalZ;

        final double yawNormalZ =
                -frame.sineYaw * normalX +
                        frame.cosineYaw * normalZ;

        final double cameraNormalY =
                frame.cosinePitch * normalY +
                        frame.sinePitch * yawNormalZ;

        final double cameraNormalZ =
                -frame.sinePitch * normalY +
                        frame.cosinePitch * yawNormalZ;

        int shadeRed = 255;
        int shadeGreen = 255;
        int shadeBlue = 255;

        int lightMask = 0;

        float baseLightRed = 0.0f;
        float baseLightGreen = 0.0f;
        float baseLightBlue = 0.0f;

        final boolean frameRequiresPerPixelLighting =
                lightingCalculator.isPerPixelLightingRequired();

        if (
                wireframe ||
                        !frameRequiresPerPixelLighting
        ) {
            final double centerX =
                    (
                            point0X +
                                    point1X +
                                    point2X
                    ) /
                            3.0;

            final double centerY =
                    (
                            point0Y +
                                    point1Y +
                                    point2Y
                    ) /
                            3.0;

            final double centerZ =
                    (
                            point0Z +
                                    point1Z +
                                    point2Z
                    ) /
                            3.0;

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

            shadeRed =
                    to255(
                            clamp01(
                                    material.ambient +
                                            material.diffuse *
                                                    lighting[0]
                            )
                    );

            shadeGreen =
                    to255(
                            clamp01(
                                    material.ambient +
                                            material.diffuse *
                                                    lighting[1]
                            )
                    );

            shadeBlue =
                    to255(
                            clamp01(
                                    material.ambient +
                                            material.diffuse *
                                                    lighting[2]
                            )
                    );
        } else {
            final double[] invariantLighting =
                    lightingCalculator.calculateInvariantLighting(
                            context,
                            normalX,
                            normalY,
                            normalZ,
                            doubleSided
                    );

            baseLightRed =
                    (float) invariantLighting[0];

            baseLightGreen =
                    (float) invariantLighting[1];

            baseLightBlue =
                    (float) invariantLighting[2];

            double minimumX = point0X;
            double maximumX = point0X;

            if (point1X < minimumX) {
                minimumX = point1X;
            } else if (point1X > maximumX) {
                maximumX = point1X;
            }

            if (point2X < minimumX) {
                minimumX = point2X;
            } else if (point2X > maximumX) {
                maximumX = point2X;
            }

            double minimumY = point0Y;
            double maximumY = point0Y;

            if (point1Y < minimumY) {
                minimumY = point1Y;
            } else if (point1Y > maximumY) {
                maximumY = point1Y;
            }

            if (point2Y < minimumY) {
                minimumY = point2Y;
            } else if (point2Y > maximumY) {
                maximumY = point2Y;
            }

            double minimumZ = point0Z;
            double maximumZ = point0Z;

            if (point1Z < minimumZ) {
                minimumZ = point1Z;
            } else if (point1Z > maximumZ) {
                maximumZ = point1Z;
            }

            if (point2Z < minimumZ) {
                minimumZ = point2Z;
            } else if (point2Z > maximumZ) {
                maximumZ = point2Z;
            }

            lightMask =
                    lightingCalculator.buildDynamicLightMask(
                            minimumX,
                            minimumY,
                            minimumZ,
                            maximumX,
                            maximumY,
                            maximumZ
                    );

            if (lightMask == 0) {
                shadeRed =
                        to255(
                                clamp01(
                                        material.ambient +
                                                material.diffuse *
                                                        baseLightRed
                                )
                        );

                shadeGreen =
                        to255(
                                clamp01(
                                        material.ambient +
                                                material.diffuse *
                                                        baseLightGreen
                                )
                        );

                shadeBlue =
                        to255(
                                clamp01(
                                        material.ambient +
                                                material.diffuse *
                                                        baseLightBlue
                                )
                        );
            }
        }

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
                    cameraNormalX,
                    cameraNormalY,
                    cameraNormalZ,
                    width,
                    height,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    lightMask,
                    baseLightRed,
                    baseLightGreen,
                    baseLightBlue,
                    doubleSided,
                    wireframe
            );

            return;
        }

        final long clippingStart =
                System.nanoTime();

        final double[] cameraX =
                projected.cameraX;

        final double[] cameraY =
                projected.cameraY;

        final double[] cameraZ =
                projected.cameraZ;

        final double[] textureU =
                mesh.u;

        final double[] textureV =
                mesh.v;

        context.clipInputX[0] = cameraX[index0];
        context.clipInputY[0] = cameraY[index0];
        context.clipInputZ[0] = cameraZ[index0];
        context.clipInputU[0] = textureU[index0];
        context.clipInputV[0] = textureV[index0];

        context.clipInputX[1] = cameraX[index1];
        context.clipInputY[1] = cameraY[index1];
        context.clipInputZ[1] = cameraZ[index1];
        context.clipInputU[1] = textureU[index1];
        context.clipInputV[1] = textureV[index1];

        context.clipInputX[2] = cameraX[index2];
        context.clipInputY[2] = cameraY[index2];
        context.clipInputZ[2] = cameraZ[index2];
        context.clipInputU[2] = textureU[index2];
        context.clipInputV[2] = textureV[index2];

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

        timings.clippingNanos +=
                System.nanoTime() -
                        clippingStart;

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
                cameraNormalX,
                cameraNormalY,
                cameraNormalZ,
                projectionScale,
                width,
                height,
                farDistance,
                material,
                shadeRed,
                shadeGreen,
                shadeBlue,
                lightMask,
                baseLightRed,
                baseLightGreen,
                baseLightBlue,
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
                    cameraNormalX,
                    cameraNormalY,
                    cameraNormalZ,
                    projectionScale,
                    width,
                    height,
                    farDistance,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    lightMask,
                    baseLightRed,
                    baseLightGreen,
                    baseLightBlue,
                    doubleSided,
                    wireframe
            );
        }
    }

    private static double clamp01(
            double value
    ) {
        if (value <= 0.0) {
            return 0.0;
        }

        if (value >= 1.0) {
            return 1.0;
        }

        return value;
    }

    private static int to255(
            double value
    ) {
        return clamp255(
                (int) (
                        value * 255.0 +
                                0.5
                )
        );
    }

    private static int clamp255(
            int value
    ) {
        if (value <= 0) {
            return 0;
        }

        if (value >= 255) {
            return 255;
        }

        return value;
    }

    private static boolean validIndex(
            int index,
            int length
    ) {
        return (
                index >= 0 &&
                        index < length
        );
    }
}