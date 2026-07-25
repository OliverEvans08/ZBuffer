package engine.assets.model;

import engine.assets.collections.FloatArrayBuilder;
import engine.assets.collections.IntArrayBuilder;
import engine.assets.mesh.MeshAsset;
import engine.assets.mesh.MeshCompiler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Imports Assimp-supported scene formats and flattens every scene mesh into
 * one engine mesh.
 *
 * <p>Node transforms are baked into vertex positions by Assimp. Materials,
 * skeletons, and animations are intentionally not retained because
 * {@link MeshAsset} currently stores only static triangle geometry and one UV
 * channel.</p>
 */
public final class AssimpModelLoader {

    private static final int IMPORT_FLAGS =
            Assimp.aiProcess_Triangulate
                    | Assimp.aiProcess_JoinIdenticalVertices
                    | Assimp.aiProcess_PreTransformVertices
                    | Assimp.aiProcess_ImproveCacheLocality
                    | Assimp.aiProcess_SortByPType
                    | Assimp.aiProcess_FindInvalidData
                    | Assimp.aiProcess_ValidateDataStructure;

    private static final int INITIAL_FLOAT_CAPACITY =
            8_192;
    private static final int INITIAL_INDEX_CAPACITY =
            8_192;

    private AssimpModelLoader() {
    }

    public static MeshAsset load(
            Path modelPath,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(modelPath, "modelPath");

        final String absolutePath =
                modelPath.toAbsolutePath()
                        .normalize()
                        .toString();

        final AIScene scene =
                Assimp.aiImportFile(
                        absolutePath,
                        IMPORT_FLAGS
                );

        if (scene == null) {
            throw importFailure(
                    modelPath,
                    Assimp.aiGetErrorString()
            );
        }

        try {
            return compileScene(
                    scene,
                    modelPath,
                    meshId
            );
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    private static MeshAsset compileScene(
            AIScene scene,
            Path modelPath,
            String meshId
    ) throws IOException {
        final int meshCount =
                scene.mNumMeshes();
        final PointerBuffer meshPointers =
                scene.mMeshes();

        if (
                meshCount <= 0
                        || meshPointers == null
        ) {
            throw importFailure(
                    modelPath,
                    "the scene contains no meshes"
            );
        }

        final FloatArrayBuilder vertices =
                new FloatArrayBuilder(
                        INITIAL_FLOAT_CAPACITY
                );
        final FloatArrayBuilder uvs =
                new FloatArrayBuilder(
                        INITIAL_FLOAT_CAPACITY
                );
        final IntArrayBuilder indices =
                new IntArrayBuilder(
                        INITIAL_INDEX_CAPACITY
                );

        boolean anyUvs = false;

        for (
                int meshIndex = 0;
                meshIndex < meshCount;
                meshIndex++
        ) {
            final AIMesh mesh =
                    AIMesh.create(
                            meshPointers.get(meshIndex)
                    );

            anyUvs |= appendMesh(
                    mesh,
                    modelPath,
                    vertices,
                    uvs,
                    indices
            );
        }

        if (indices.size() == 0) {
            throw importFailure(
                    modelPath,
                    "the scene contains no triangle geometry"
            );
        }

        return MeshCompiler.compile(
                vertices.toArray(),
                anyUvs
                        ? uvs.toArray()
                        : new float[0],
                indices.toArray(),
                meshId
        );
    }

    private static boolean appendMesh(
            AIMesh mesh,
            Path modelPath,
            FloatArrayBuilder vertices,
            FloatArrayBuilder uvs,
            IntArrayBuilder indices
    ) throws IOException {
        final int vertexCount =
                mesh.mNumVertices();

        if (vertexCount <= 0) {
            return false;
        }

        final AIVector3D.Buffer sourceVertices =
                mesh.mVertices();
        final AIVector3D.Buffer sourceUvs =
                mesh.mTextureCoords(0);
        final boolean hasUvs =
                sourceUvs != null;
        final int baseVertex =
                vertices.size() / 3;

        for (
                int vertexIndex = 0;
                vertexIndex < vertexCount;
                vertexIndex++
        ) {
            final AIVector3D vertex =
                    sourceVertices.get(vertexIndex);

            vertices.add(vertex.x());
            vertices.add(vertex.y());
            vertices.add(vertex.z());

            if (hasUvs) {
                final AIVector3D uv =
                        sourceUvs.get(vertexIndex);

                uvs.add(uv.x());
                uvs.add(uv.y());
            } else {
                uvs.add(0.0f);
                uvs.add(0.0f);
            }
        }

        final int faceCount =
                mesh.mNumFaces();
        final AIFace.Buffer faces =
                mesh.mFaces();

        for (
                int faceIndex = 0;
                faceIndex < faceCount;
                faceIndex++
        ) {
            final AIFace face =
                    faces.get(faceIndex);

            if (face.mNumIndices() != 3) {
                continue;
            }

            final IntBuffer faceIndices =
                    face.mIndices();

            appendIndex(
                    faceIndices.get(0),
                    vertexCount,
                    baseVertex,
                    faceIndex,
                    modelPath,
                    indices
            );
            appendIndex(
                    faceIndices.get(1),
                    vertexCount,
                    baseVertex,
                    faceIndex,
                    modelPath,
                    indices
            );
            appendIndex(
                    faceIndices.get(2),
                    vertexCount,
                    baseVertex,
                    faceIndex,
                    modelPath,
                    indices
            );
        }

        return hasUvs;
    }

    private static void appendIndex(
            int localIndex,
            int vertexCount,
            int baseVertex,
            int faceIndex,
            Path modelPath,
            IntArrayBuilder indices
    ) throws IOException {
        if (
                localIndex < 0
                        || localIndex >= vertexCount
        ) {
            throw importFailure(
                    modelPath,
                    "face "
                            + faceIndex
                            + " contains invalid vertex index "
                            + localIndex
            );
        }

        indices.add(baseVertex + localIndex);
    }

    private static IOException importFailure(
            Path modelPath,
            String reason
    ) {
        final String detail =
                reason == null || reason.isBlank()
                        ? "unknown importer error"
                        : reason;

        return new IOException(
                "Failed to import model "
                        + modelPath
                        + ": "
                        + detail
        );
    }
}