package engine;

import engine.assets.mesh.MeshAsset;
import engine.assets.model.ModelLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads and publishes immutable mesh snapshots.
 *
 * <p>A reload is built into local collections and then published with one
 * volatile write. Readers see either the previous complete snapshot or the
 * new complete snapshot, never a partially rebuilt asset map.</p>
 */
public final class AssetManager {

    public static final String DEFAULT_MODELS_ROOT =
            "assets/models";

    private static final System.Logger LOGGER =
            System.getLogger(AssetManager.class.getName());

    private static final Snapshot EMPTY_SNAPSHOT =
            new Snapshot(Map.of(), Map.of(), List.of());

    private final Path modelsRoot;
    private volatile Snapshot snapshot = EMPTY_SNAPSHOT;

    public AssetManager() {
        this(Paths.get(DEFAULT_MODELS_ROOT));
    }

    public AssetManager(Path modelsRoot) {
        this.modelsRoot = Objects.requireNonNullElseGet(
                modelsRoot,
                () -> Paths.get(DEFAULT_MODELS_ROOT)
        ).normalize();
    }

    public Path getModelsRoot() {
        return modelsRoot;
    }

    /**
     * Reloads every supported model below the model root and atomically
     * publishes the result. Individual malformed files are logged and skipped.
     */
    public void loadAllMeshes() {
        if (!Files.isDirectory(modelsRoot)) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Models root is missing or is not a directory: "
                            + modelsRoot.toAbsolutePath()
            );
            snapshot = EMPTY_SNAPSHOT;
            return;
        }

        final List<ModelSource> modelSources =
                scanModelSources();
        final Map<String, Integer> aliasCounts =
                countAliases(modelSources);
        final Map<String, MeshData> loadedMeshes =
                loadMeshes(modelSources);
        final Map<String, String> aliases = buildAliases(
                modelSources,
                aliasCounts,
                loadedMeshes
        );

        final ArrayList<String> ids =
                new ArrayList<>(loadedMeshes.keySet());
        ids.sort(Comparator.naturalOrder());

        snapshot = new Snapshot(
                Map.copyOf(loadedMeshes),
                Map.copyOf(aliases),
                List.copyOf(ids)
        );

        LOGGER.log(
                System.Logger.Level.INFO,
                "Loaded "
                        + loadedMeshes.size()
                        + " of "
                        + modelSources.size()
                        + " model files from "
                        + modelsRoot.toAbsolutePath()
        );
    }

    public List<String> getMeshIds() {
        return snapshot.meshIds();
    }

    public MeshData getMesh(String idOrName) {
        final MeshData mesh = getMeshOrNull(idOrName);

        if (mesh != null) {
            return mesh;
        }

        throw new NoSuchElementException(
                "Mesh not found: '"
                        + idOrName
                        + "'. Available: "
                        + getMeshIds()
        );
    }

    public MeshData getMeshOrNull(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return null;
        }

        final Snapshot current = snapshot;
        final MeshData direct =
                current.meshById().get(idOrName);

        if (direct != null) {
            return direct;
        }

        final String aliasedId =
                current.aliasToId().get(idOrName);

        return aliasedId == null
                ? null
                : current.meshById().get(aliasedId);
    }

    public MeshData getFirstMeshOrNull() {
        final Snapshot current = snapshot;

        return current.meshIds().isEmpty()
                ? null
                : current.meshById().get(
                current.meshIds().get(0)
        );
    }

    private List<ModelSource> scanModelSources() {
        final ArrayList<Path> modelFiles =
                new ArrayList<>(256);

        try (var paths = Files.walk(modelsRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(ModelLoader::supports)
                    .forEach(modelFiles::add);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to scan models root: " + modelsRoot,
                    exception
            );
        }

        modelFiles.sort(Comparator.naturalOrder());

        final HashMap<String, Integer> baseIdCounts =
                new HashMap<>(mapCapacity(modelFiles.size()));

        for (Path path : modelFiles) {
            baseIdCounts.merge(
                    toBaseMeshId(modelsRoot, path),
                    1,
                    Integer::sum
            );
        }

        final ArrayList<ModelSource> sources =
                new ArrayList<>(modelFiles.size());

        for (Path path : modelFiles) {
            final String baseId =
                    toBaseMeshId(modelsRoot, path);
            final boolean extensionRequired =
                    baseIdCounts.getOrDefault(baseId, 0) > 1;
            final String id = extensionRequired
                    ? toRelativeId(modelsRoot, path)
                    : baseId;

            sources.add(new ModelSource(path, id));
        }

        return sources;
    }

    private Map<String, MeshData> loadMeshes(
            List<ModelSource> modelSources
    ) {
        if (modelSources.isEmpty()) {
            return Map.of();
        }

        final int processorCount =
                Runtime.getRuntime().availableProcessors();

        final int workerCount = Math.min(
                modelSources.size(),
                Math.max(1, processorCount - 1)
        );

        if (workerCount == 1) {
            final HashMap<String, MeshData> result =
                    new HashMap<>(
                            mapCapacity(modelSources.size())
                    );

            for (ModelSource source : modelSources) {
                acceptLoadResult(
                        loadOne(source),
                        result
                );
            }

            return result;
        }

        final ThreadFactory factory =
                new NamedDaemonThreadFactory("AssetWorker-");

        final ExecutorService executor =
                Executors.newFixedThreadPool(
                        workerCount,
                        factory
                );

        final CompletionService<MeshLoadResult> completion =
                new ExecutorCompletionService<>(executor);

        try {
            for (ModelSource source : modelSources) {
                completion.submit(() -> loadOne(source));
            }

            final HashMap<String, MeshData> result =
                    new HashMap<>(
                            mapCapacity(modelSources.size())
                    );

            for (int i = 0; i < modelSources.size(); i++) {
                try {
                    acceptLoadResult(
                            completion.take().get(),
                            result
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(
                            "Interrupted while loading meshes",
                            exception
                    );
                } catch (ExecutionException exception) {
                    throw new IllegalStateException(
                            "Unexpected failure in mesh loading worker",
                            exception.getCause()
                    );
                }
            }

            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private MeshLoadResult loadOne(
            ModelSource source
    ) {
        try {
            final MeshAsset asset =
                    ModelLoader.load(
                            source.path(),
                            source.id()
                    );

            return MeshLoadResult.success(
                    source.id(),
                    toMeshData(asset)
            );
        } catch (IOException | RuntimeException exception) {
            return MeshLoadResult.failure(
                    source.path(),
                    exception
            );
        }
    }

    /**
     * Converts the flat, binary-ready mesh representation into the
     * engine-native MeshData representation used by GameObject and MeshObject.
     */
    private static MeshData toMeshData(
            MeshAsset asset
    ) {
        Objects.requireNonNull(asset, "asset");

        if (asset.vertices.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Mesh vertex array length must be divisible by 3: "
                            + asset.meshId
            );
        }

        if (asset.indices.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Mesh index array length must be divisible by 3: "
                            + asset.meshId
            );
        }

        final int vertexCount =
                asset.vertices.length / 3;
        final int faceCount =
                asset.indices.length / 3;

        final double[][] vertices =
                new double[vertexCount][3];

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            final int sourceIndex = vertex * 3;

            vertices[vertex][0] =
                    asset.vertices[sourceIndex];
            vertices[vertex][1] =
                    asset.vertices[sourceIndex + 1];
            vertices[vertex][2] =
                    asset.vertices[sourceIndex + 2];
        }

        final int[][] faces =
                new int[faceCount][3];

        for (int face = 0; face < faceCount; face++) {
            final int sourceIndex = face * 3;

            final int indexA =
                    asset.indices[sourceIndex];
            final int indexB =
                    asset.indices[sourceIndex + 1];
            final int indexC =
                    asset.indices[sourceIndex + 2];

            validateVertexIndex(
                    asset.meshId,
                    indexA,
                    vertexCount
            );
            validateVertexIndex(
                    asset.meshId,
                    indexB,
                    vertexCount
            );
            validateVertexIndex(
                    asset.meshId,
                    indexC,
                    vertexCount
            );

            faces[face][0] = indexA;
            faces[face][1] = indexB;
            faces[face][2] = indexC;
        }

        final double[][] uvs;

        if (asset.uvs.length == 0) {
            uvs = null;
        } else {
            final int expectedUvValues =
                    vertexCount * 2;

            if (asset.uvs.length != expectedUvValues) {
                throw new IllegalArgumentException(
                        "Mesh UV array length must be "
                                + expectedUvValues
                                + " for "
                                + vertexCount
                                + " vertices, but was "
                                + asset.uvs.length
                                + ": "
                                + asset.meshId
                );
            }

            uvs = new double[vertexCount][2];

            for (int vertex = 0; vertex < vertexCount; vertex++) {
                final int sourceIndex = vertex * 2;

                uvs[vertex][0] =
                        asset.uvs[sourceIndex];
                uvs[vertex][1] =
                        asset.uvs[sourceIndex + 1];
            }
        }

        return new MeshData(
                asset.meshId,
                vertices,
                faces,
                uvs
        );
    }

    private static void validateVertexIndex(
            String meshId,
            int index,
            int vertexCount
    ) {
        if (index < 0 || index >= vertexCount) {
            throw new IllegalArgumentException(
                    "Mesh contains invalid vertex index "
                            + index
                            + " for "
                            + vertexCount
                            + " vertices: "
                            + meshId
            );
        }
    }

    private static void acceptLoadResult(
            MeshLoadResult result,
            Map<String, MeshData> destination
    ) {
        if (result.error() == null) {
            destination.put(
                    result.id(),
                    result.mesh()
            );
            return;
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Failed to load model " + result.path(),
                result.error()
        );
    }

    private static Map<String, Integer> countAliases(
            List<ModelSource> modelSources
    ) {
        final HashMap<String, Integer> counts =
                new HashMap<>(
                        mapCapacity(modelSources.size())
                );

        for (ModelSource source : modelSources) {
            counts.merge(
                    stripExtension(
                            source.path()
                                    .getFileName()
                                    .toString()
                    ),
                    1,
                    Integer::sum
            );
        }

        return counts;
    }

    private static Map<String, String> buildAliases(
            List<ModelSource> modelSources,
            Map<String, Integer> aliasCounts,
            Map<String, MeshData> loadedMeshes
    ) {
        final HashMap<String, String> aliases =
                new HashMap<>(
                        mapCapacity(aliasCounts.size())
                );

        for (ModelSource source : modelSources) {
            final String alias = stripExtension(
                    source.path()
                            .getFileName()
                            .toString()
            );

            if (aliasCounts.getOrDefault(alias, 0) != 1) {
                continue;
            }

            if (loadedMeshes.containsKey(source.id())) {
                aliases.put(alias, source.id());
            }
        }

        return aliases;
    }

    private static String toBaseMeshId(
            Path root,
            Path file
    ) {
        return stripExtension(toRelativeId(root, file));
    }

    private static String toRelativeId(
            Path root,
            Path file
    ) {
        return root.relativize(file)
                .toString()
                .replace(
                        FileSystems.getDefault().getSeparator(),
                        "/"
                );
    }

    private static String stripExtension(String name) {
        final int slash = name.lastIndexOf('/');
        final int dot = name.lastIndexOf('.');

        return dot > slash + 1
                ? name.substring(0, dot)
                : name;
    }

    private static int mapCapacity(int expectedSize) {
        if (expectedSize <= 0) {
            return 16;
        }

        return Math.min(
                1 << 20,
                Math.max(
                        16,
                        (int) Math.ceil(expectedSize / 0.75d)
                )
        );
    }

    private record Snapshot(
            Map<String, MeshData> meshById,
            Map<String, String> aliasToId,
            List<String> meshIds
    ) {
    }

    private record ModelSource(
            Path path,
            String id
    ) {
    }

    private record MeshLoadResult(
            String id,
            MeshData mesh,
            Path path,
            Throwable error
    ) {
        private static MeshLoadResult success(
                String id,
                MeshData mesh
        ) {
            return new MeshLoadResult(
                    id,
                    mesh,
                    null,
                    null
            );
        }

        private static MeshLoadResult failure(
                Path path,
                Throwable error
        ) {
            return new MeshLoadResult(
                    null,
                    null,
                    path,
                    error
            );
        }
    }

    private static final class NamedDaemonThreadFactory
            implements ThreadFactory {

        private final ThreadFactory delegate =
                Executors.defaultThreadFactory();
        private final AtomicInteger nextId =
                new AtomicInteger(1);
        private final String prefix;

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            final Thread thread =
                    delegate.newThread(task);

            thread.setName(
                    prefix + nextId.getAndIncrement()
            );
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);

            return thread;
        }
    }
}