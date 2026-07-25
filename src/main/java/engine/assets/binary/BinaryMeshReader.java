package engine.assets.binary;

import engine.assets.mesh.MeshAsset;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class BinaryMeshReader {

    private BinaryMeshReader() {
    }

    /**
     * Runtime entry point.
     *
     * <p>This method performs no OBJ parsing, triangulation, deduplication,
     * normal generation, triangle-bound generation, or BVH construction.</p>
     */
    public static MeshAsset read(
            Path binaryPath
    ) throws IOException {
        Objects.requireNonNull(binaryPath, "binaryPath");

        try (
                DataInputStream in =
                        new DataInputStream(
                                new BufferedInputStream(
                                        Files.newInputStream(binaryPath)
                                )
                        )
        ) {
            final int magic = in.readInt();

            if (magic != BinaryMeshFormat.MAGIC) {
                throw new IOException(
                        "Not an OBJLoader binary mesh asset: "
                                + binaryPath
                );
            }

            final int version = in.readInt();

            if (version != BinaryMeshFormat.VERSION) {
                throw new IOException(
                        "Unsupported binary mesh version "
                                + version
                                + "; expected "
                                + BinaryMeshFormat.VERSION
                );
            }

            final String meshId =
                    BinaryMeshFormat.readString(in);
            final float[] vertices =
                    BinaryMeshFormat.readFloatArray(in);
            final float[] uvs =
                    BinaryMeshFormat.readFloatArray(in);
            final float[] vertexNormals =
                    BinaryMeshFormat.readFloatArray(in);
            final int[] indices =
                    BinaryMeshFormat.readIntArray(in);
            final float[] triangleNormals =
                    BinaryMeshFormat.readFloatArray(in);
            final float[] triangleBounds =
                    BinaryMeshFormat.readFloatArray(in);
            final int[] bvhTriangleOrder =
                    BinaryMeshFormat.readIntArray(in);
            final float[] bvhBounds =
                    BinaryMeshFormat.readFloatArray(in);
            final int[] bvhLeft =
                    BinaryMeshFormat.readIntArray(in);
            final int[] bvhRight =
                    BinaryMeshFormat.readIntArray(in);
            final int[] bvhFirstTriangle =
                    BinaryMeshFormat.readIntArray(in);
            final int[] bvhTriangleCount =
                    BinaryMeshFormat.readIntArray(in);

            BinaryMeshValidator.validate(
                    vertices,
                    uvs,
                    vertexNormals,
                    indices,
                    triangleNormals,
                    triangleBounds,
                    bvhTriangleOrder,
                    bvhBounds,
                    bvhLeft,
                    bvhRight,
                    bvhFirstTriangle,
                    bvhTriangleCount
            );

            return new MeshAsset(
                    meshId,
                    vertices,
                    uvs,
                    vertexNormals,
                    indices,
                    triangleNormals,
                    triangleBounds,
                    bvhTriangleOrder,
                    bvhBounds,
                    bvhLeft,
                    bvhRight,
                    bvhFirstTriangle,
                    bvhTriangleCount
            );
        } catch (EOFException exception) {
            throw new IOException(
                    "Truncated binary mesh asset: "
                            + binaryPath,
                    exception
            );
        }
    }
}