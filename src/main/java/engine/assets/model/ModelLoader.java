package engine.assets.model;

import engine.assets.binary.BinaryMeshReader;
import engine.assets.binary.BinaryMeshWriter;
import engine.assets.mesh.MeshAsset;
import org.lwjgl.assimp.Assimp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Public model-loading facade.
 *
 * <p>Every source model format, including Wavefront OBJ, is imported through
 * {@link AssimpModelLoader}. This includes FBX, glTF/GLB, Collada, STL, PLY,
 * 3DS, Blender, X3D, X, and every other format supported by the installed
 * Assimp version.</p>
 *
 * <p>The engine's compiled binary mesh format remains handled separately by
 * {@link BinaryMeshReader} and {@link BinaryMeshWriter}.</p>
 */
public final class ModelLoader {

    private static final ConcurrentMap<String, Boolean>
            SUPPORTED_EXTENSION_CACHE =
            new ConcurrentHashMap<>();

    private ModelLoader() {
    }

    public static boolean supports(
            Path path
    ) {
        Objects.requireNonNull(path, "path");

        final String extension =
                extensionOf(path);

        if (extension.isEmpty()) {
            return false;
        }

        return SUPPORTED_EXTENSION_CACHE.computeIfAbsent(
                extension,
                ModelLoader::queryAssimpSupport
        );
    }

    public static MeshAsset load(
            Path modelPath,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(modelPath, "modelPath");

        final String extension =
                extensionOf(modelPath);

        if (!supports(modelPath)) {
            throw new IOException(
                    "Unsupported model format '"
                            + extension
                            + "': "
                            + modelPath
            );
        }

        return AssimpModelLoader.load(
                modelPath,
                meshId
        );
    }

    public static void convert(
            Path modelPath,
            Path binaryPath,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(binaryPath, "binaryPath");

        writeBinary(
                load(modelPath, meshId),
                binaryPath
        );
    }

    public static void writeBinary(
            MeshAsset mesh,
            Path binaryPath
    ) throws IOException {
        BinaryMeshWriter.write(
                mesh,
                binaryPath
        );
    }

    public static MeshAsset loadBinary(
            Path binaryPath
    ) throws IOException {
        return BinaryMeshReader.read(binaryPath);
    }

    private static boolean queryAssimpSupport(
            String extension
    ) {
        try {
            return Assimp.aiIsExtensionSupported(extension);
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    "Assimp could not be initialized. Add both the "
                            + "LWJGL Assimp module and the native runtime "
                            + "matching this operating system.",
                    error
            );
        }
    }

    private static String extensionOf(
            Path path
    ) {
        final Path fileNamePath =
                path.getFileName();

        if (fileNamePath == null) {
            return "";
        }

        final String fileName =
                fileNamePath.toString();
        final int dot =
                fileName.lastIndexOf('.');

        if (
                dot < 0
                        || dot == fileName.length() - 1
        ) {
            return "";
        }

        return fileName.substring(dot)
                .toLowerCase(Locale.ROOT);
    }
}