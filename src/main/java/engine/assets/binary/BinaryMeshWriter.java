package engine.assets.binary;

import engine.assets.mesh.MeshAsset;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class BinaryMeshWriter {

    private BinaryMeshWriter() {
    }

    public static void write(
            MeshAsset mesh,
            Path binaryPath
    ) throws IOException {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(binaryPath, "binaryPath");

        final Path parent =
                binaryPath.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (
                DataOutputStream out =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(binaryPath)
                                )
                        )
        ) {
            out.writeInt(BinaryMeshFormat.MAGIC);
            out.writeInt(BinaryMeshFormat.VERSION);

            BinaryMeshFormat.writeString(
                    out,
                    mesh.meshId
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.vertices
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.uvs
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.vertexNormals
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.indices
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.triangleNormals
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.triangleBounds
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.bvhTriangleOrder
            );
            BinaryMeshFormat.writeFloatArray(
                    out,
                    mesh.bvhBounds
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.bvhLeft
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.bvhRight
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.bvhFirstTriangle
            );
            BinaryMeshFormat.writeIntArray(
                    out,
                    mesh.bvhTriangleCount
            );
        }
    }
}