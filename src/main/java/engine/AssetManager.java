package engine;

import objects.MeshObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AssetManager {

    public static final String DEFAULT_MODELS_ROOT = "assets/models";

    private final Path modelsRoot;

    private final ConcurrentHashMap<String, MeshData> meshById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> aliasToId = new ConcurrentHashMap<>();
    private volatile List<String> meshIdsSnapshot = List.of();

    public AssetManager() {
        this(Paths.get(DEFAULT_MODELS_ROOT));
    }

    public AssetManager(Path modelsRoot) {
        this.modelsRoot = (modelsRoot == null ? Paths.get(DEFAULT_MODELS_ROOT) : modelsRoot);
    }

    public Path getModelsRoot() { return modelsRoot; }

    /**
     * Scan + load all .obj meshes once.
     * Safe to call multiple times (it clears previous cache).
     */
    public void loadAllMeshes() {
        meshById.clear();
        aliasToId.clear();

        if (!Files.exists(modelsRoot) || !Files.isDirectory(modelsRoot)) {
            System.err.println("[AssetManager] Models root missing: " + modelsRoot.toAbsolutePath());
            meshIdsSnapshot = List.of();
            return;
        }

        List<Path> objFiles = new ArrayList<>(256);

        try {
            try (var walk = Files.walk(modelsRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj"))
                        .forEach(objFiles::add);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan models: " + modelsRoot, e);
        }

        objFiles.sort(Comparator.naturalOrder());

        // Track alias uniqueness
        HashMap<String, Integer> aliasCounts = new HashMap<>();

        int loaded = 0;
        for (Path p : objFiles) {
            String id = toMeshId(modelsRoot, p);

            try {
                MeshData mesh = OBJLoader.load(p, id);
                meshById.put(id, mesh);
                loaded++;

                String alias = stripExt(p.getFileName().toString());
                aliasCounts.merge(alias, 1, Integer::sum);

            } catch (IOException ex) {
                System.err.println("[AssetManager] Failed to load OBJ: " + p + " (" + ex + ")");
            } catch (RuntimeException ex) {
                System.err.println("[AssetManager] Failed to parse OBJ: " + p + " (" + ex + ")");
            }
        }

        // Build alias map only for unique aliases
        for (Path p : objFiles) {
            String alias = stripExt(p.getFileName().toString());
            Integer c = aliasCounts.get(alias);
            if (c != null && c == 1) {
                String id = toMeshId(modelsRoot, p);
                aliasToId.put(alias, id);
            }
        }

        ArrayList<String> ids = new ArrayList<>(meshById.keySet());
        ids.sort(Comparator.naturalOrder());
        meshIdsSnapshot = Collections.unmodifiableList(ids);

        System.out.println("[AssetManager] Loaded meshes: " + loaded + " from " + modelsRoot.toAbsolutePath());
    }

    public List<String> getMeshIds() {
        return meshIdsSnapshot;
    }

    public MeshData getMesh(String idOrName) {
        MeshData m = getMeshOrNull(idOrName);
        if (m != null) return m;

        throw new NoSuchElementException("Mesh not found: '" + idOrName + "'. Available: " + getMeshIds());
    }

    public MeshData getMeshOrNull(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;

        MeshData direct = meshById.get(idOrName);
        if (direct != null) return direct;

        String aliasedId = aliasToId.get(idOrName);
        if (aliasedId != null) return meshById.get(aliasedId);

        return null;
    }

    public MeshData getFirstMeshOrNull() {
        List<String> ids = meshIdsSnapshot;
        if (ids.isEmpty()) return null;
        return meshById.get(ids.get(0));
    }

    private static String toMeshId(Path root, Path file) {
        Path rel = root.relativize(file);
        String s = rel.toString().replace(FileSystems.getDefault().getSeparator(), "/");
        if (s.toLowerCase(Locale.ROOT).endsWith(".obj")) s = s.substring(0, s.length() - 4);
        return s;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
