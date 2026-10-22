
package engine.terrain;

import engine.EngineContext;
import engine.render.Material;
import engine.render.Texture;
import objects.GameObject;
import objects.lighting.LightObject;
import util.Vector3;

import java.awt.Color;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

public final class TerrainWorld {

    public static final long SEED = chooseSeed();
    public static final int CHUNK_SIZE = 48;
    public static final int CELLS_PER_CHUNK = 16;
    public static final int TEXTURE_SIZE = 96;

    private static final int ATLAS_LAYERS = 5;
    private static final double ROAD_TEXTURE_PADDING = 26.0;
    private static final double ROAD_TEXTURE_SPAN =
            CHUNK_SIZE + 2.0 * ROAD_TEXTURE_PADDING;

    private static final double STEP = CHUNK_SIZE / (double) CELLS_PER_CHUNK;
    private static final int N = CELLS_PER_CHUNK + 1;
    private static final double NETWORK_CELL = 192.0;
    private static final int SAFE_RADIUS = 2;
    private static final int MAX_VIEW_RADIUS = 12;
    private static final int EXTRA_CHUNKS_PER_TICK = 2;

    private static final double ROAD_SAMPLE = 3.0;
    private static final double ROAD_LIFT = 0.15;
    private static final double SHOULDER_LIFT = 0.105;
    private static final double MARKING_LIFT = 0.185;
    private static final double JUNCTION_LIFT = 0.235;
    private static final int ROAD_WIDTH_SLICES = 6;

    private static final long BACKBONE_COLUMN =
            (long) Math.floor(hash(0, 0, 613) * 7) - 3;

    private static final long ARTERIAL_ROW =
            (long) Math.floor(hash(0, 0, 619) * 5) - 2;

    private static final int ASPHALT = 0;
    private static final int WHITE = 1;
    private static final int YELLOW = 2;
    private static final int GRAVEL = 3;
    private static final int DIRT = 4;
    private static final int CONCRETE = 5;
    private static final int METAL = 6;
    private static final int LEAF_DARK = 7;
    private static final int LEAF_LIGHT = 8;
    private static final int WOOD = 9;
    private static final int STONE = 10;
    private static final int SAND = 11;
    private static final int SNOW = 12;
    private static final int WATER = 13;
    private static final int WALL = 14;
    private static final int ROOF = 15;

    private static final int[] PALETTE = {
            0xFF32363B, 0xFFE8E9E4, 0xFFF3C24A, 0xFF928B78,
            0xFF806747, 0xFF9A9992, 0xFFADB6BA, 0xFF294A37,
            0xFF47754B, 0xFF695039, 0xFF77776D, 0xFFC4A36F,
            0xFFE7EBE7, 0xFF3E7386, 0xFFB8A88D, 0xFF655B58
    };

    private static final int[][] GRID_FACES = gridFaces();

    private static final ThreadLocal<LinkedHashMap<Key, LocalRoads>> ROAD_CACHE =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, LocalRoads> oldest) {
                    return size() > 64;
                }
            });

    private final HashMap<Key, TerrainMesh> loaded = new HashMap<>();
    private final ArrayDeque<TerrainMesh> reusable = new ArrayDeque<>();
    private final ArrayList<TerrainMesh> retired = new ArrayList<>();
    private final ArrayList<Key> anchors = new ArrayList<>();

    public enum Biome {
        GRASSLAND, WETLAND, TUNDRA, DESERT, BADLANDS, ALPINE, VOLCANIC
    }

    private enum RoadType {
        EXPRESS(7.6, ASPHALT, 4),
        MAIN(4.2, ASPHALT, 2),
        COUNTRY(3.75, ASPHALT, 2),
        GRAVEL(3.1, TerrainWorld.GRAVEL, 2),
        DIRT(2.45, TerrainWorld.DIRT, 1),
        TRACK(1.75, TerrainWorld.DIRT, 1);

        final double halfWidth;
        final int material;
        final int lanes;

        RoadType(double halfWidth, int material, int lanes) {
            this.halfWidth = halfWidth;
            this.material = material;
            this.lanes = lanes;
        }

        boolean paved() {
            return material == ASPHALT;
        }
    }

    public TerrainWorld() {
    }

    public static TerrainWorld populate(EngineContext context, Consumer<GameObject> add) {
        TerrainWorld world = new TerrainWorld();
        context.settings.setRenderDistance(400);
        world.build(add);

        LightObject sun = LightObject.directional(
                new Vector3(-0.48, -1.0, 0.32),
                new Color(255, 241, 217), 0.88, false);
        sun.setName("Procedural world sunlight");
        sun.setTag("light");
        add.accept(sun);

        LightObject fill = LightObject.directional(
                new Vector3(0.48, -0.38, -0.62),
                new Color(177, 207, 237), 0.30, false);
        fill.setName("Procedural world skylight");
        fill.setTag("light");
        add.accept(fill);

        double z = 0.0;
        double x = highwayCenterX(z);
        double y = heightAt(x, z) + 0.22;

        context.camera.x = x;
        context.camera.y = y;
        context.camera.z = z;
        context.camera.yaw = 0;
        context.camera.pitch = 0;
        context.camera.yVelocity = 0;
        context.camera.dx = 0;
        context.camera.dy = 0;
        context.camera.dz = 0;
        context.camera.setFlightMode(false);
        context.camera.onGround = false;
        context.playerBody.transform.position = new Vector3(x, y, z);

        return world;
    }

    public void build(Consumer<GameObject> add) {
        fill(keyAt(highwayCenterX(0), 0), 9, Integer.MAX_VALUE, add);
    }

    public void update(EngineContext context, Consumer<GameObject> add,
                       Consumer<GameObject> remove) {
        reusable.addAll(retired);
        retired.clear();

        Key focus = keyAt(context.camera.x, context.camera.z);
        anchors.clear();
        anchors.add(focus);

        for (GameObject root : context.scene.getRootObjects()) {
            if (root.isActive() && "car".equals(root.getTag())) {
                Key anchor = keyAt(root.transform.position.x, root.transform.position.z);
                if (!anchors.contains(anchor)) anchors.add(anchor);
            }
        }

        int radius = (int) Math.ceil(Math.min(576.0,
                context.settings.getRenderDistance()) / CHUNK_SIZE) + 2;
        radius = Math.max(SAFE_RADIUS, Math.min(MAX_VIEW_RADIUS, radius));

        Iterator<Map.Entry<Key, TerrainMesh>> iterator = loaded.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Key, TerrainMesh> entry = iterator.next();
            boolean keep = within(entry.getKey(), focus, radius + 2);

            for (int i = 1; !keep && i < anchors.size(); i++) {
                keep = within(entry.getKey(), anchors.get(i), SAFE_RADIUS + 1);
            }

            if (!keep) {
                TerrainMesh mesh = entry.getValue();
                remove.accept(mesh);
                retired.add(mesh);
                iterator.remove();
            }
        }

        for (Key anchor : anchors) {
            fill(anchor, SAFE_RADIUS, Integer.MAX_VALUE, add);
        }

        fill(focus, radius, EXTRA_CHUNKS_PER_TICK, add);
    }

    public int getLoadedChunkCount() {
        return loaded.size();
    }

    public static double highwayCenterX(double z) {
        requireFinite(0, z);
        Road r = backboneAt(z);
        double t = crossingParameter(r, z);
        return r.x(t);
    }

    public static double highwayHeight(double z) {
        requireFinite(0, z);
        Road r = backboneAt(z);
        return r.y(crossingParameter(r, z));
    }

    public static Biome biomeAt(double x, double z) {
        requireFinite(x, z);
        return biomeFor(x, z, naturalHeight(x, z));
    }

    private static Biome biomeFor(double x, double z, double h) {
        double wet = wetness(x, z);
        double heat = temperature(x, z) - h * 0.0017;
        double geology = noise(x / 1300.0 + 11, z / 1300.0 - 31);

        if (geology > 0.57 && h > 35) return Biome.VOLCANIC;
        if (h > 125) return Biome.ALPINE;
        if (heat < 0.27) return Biome.TUNDRA;
        if (wet < 0.25 && heat > 0.57) return Biome.DESERT;
        if (wet < 0.34) return Biome.BADLANDS;
        if (wet > 0.72 && h < 48) return Biome.WETLAND;

        return Biome.GRASSLAND;
    }

    private void fill(Key center, int radius, int budget, Consumer<GameObject> add) {
        int built = 0;

        for (int ring = 0; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                    Key key = new Key(center.x + dx, center.z + dz);
                    if (loaded.containsKey(key)) continue;

                    TerrainMesh mesh = reusable.pollFirst();
                    if (mesh == null) mesh = new TerrainMesh();

                    generate(mesh, key);
                    loaded.put(key, mesh);
                    add.accept(mesh);

                    if (++built >= budget) return;
                }
            }
        }
    }

    private static Key keyAt(double x, double z) {
        requireFinite(x, z);

        return new Key((long) Math.floor(x / CHUNK_SIZE),
                (long) Math.floor(z / CHUNK_SIZE));
    }

    private static boolean within(Key a, Key b, int radius) {
        return Math.abs((double) a.x - b.x) <= radius
                && Math.abs((double) a.z - b.z) <= radius;
    }

    private static void requireFinite(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Terrain position must be finite");
        }
    }

    private static void generate(TerrainMesh mesh, Key key) {
        double ox = key.x * (double) CHUNK_SIZE;
        double oz = key.z * (double) CHUNK_SIZE;

        MeshBuilder b = new MeshBuilder(ox, oz);

        int haloSide = N + 2;
        double[] halo = new double[haloSide * haloSide];
        int[] colours = new int[N * N];

        for (int z = -1; z <= N; z++) {
            for (int x = -1; x <= N; x++) {
                double wx = ox + x * STEP;
                double wz = oz + z * STEP;

                halo[(z + 1) * haloSide + x + 1] = field(wx, wz);
            }
        }

        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int h = (z + 1) * haloSide + x + 1;
                double wx = ox + x * STEP;
                double wz = oz + z * STEP;

                double sx = (halo[h + 1] - halo[h - 1]) / (2 * STEP);
                double sz = (halo[h + haloSide] - halo[h - haloSide]) / (2 * STEP);

                colours[z * N + x] = terrainColour(wx, wz, halo[h],
                        Math.hypot(sx, sz));

                double u = x / (double) CELLS_PER_CHUNK;
                double v = (z / (double) CELLS_PER_CHUNK)
                        * (TEXTURE_SIZE - 1.0)
                        / (TEXTURE_SIZE * ATLAS_LAYERS - 1.0);

                b.vertex(wx, halo[h], wz, u, v);
            }
        }

        for (int[] face : GRID_FACES) b.faces.add(face);

        long gx0 = (long) Math.floor(ox / NETWORK_CELL) - 2;
        long gx1 = (long) Math.floor((ox + CHUNK_SIZE) / NETWORK_CELL) + 1;
        long gz0 = (long) Math.floor(oz / NETWORK_CELL) - 2;
        long gz1 = (long) Math.floor((oz + CHUNK_SIZE) / NETWORK_CELL) + 1;

        for (long gz = gz0; gz <= gz1; gz++) {
            for (long gx = gx0; gx <= gx1; gx++) {
                for (int axis = 0; axis < 2; axis++) {
                    Road road = road(gx, gz, axis);
                    if (road != null) addRoad(b, key, road);
                }

                Node node = node(gx, gz);
                if (keyAt(node.x, node.z).equals(key)) addJunction(b, node);
            }
        }

        addScenery(b, key);

        mesh.vertices = b.vertices.toArray(new double[0][]);
        mesh.uvs = b.uvs.toArray(new double[0][]);
        mesh.faces = b.faces.toArray(new int[0][]);

        mesh.setMaterial(Material.textured(texture(colours, key))
                .setTint(Color.WHITE).setWrap(Texture.Wrap.CLAMP)
                .setAmbient(0.42).setDiffuse(0.78));

        mesh.setName("Procedural terrain " + key.x + ", " + key.z);
        mesh.transform.position = new Vector3(ox, 0, oz);
        mesh.transform.invalidate();
    }

    public static double heightAt(double x, double z) {
        requireFinite(x, z);

        double x0 = Math.floor(x / STEP) * STEP;
        double z0 = Math.floor(z / STEP) * STEP;
        double fx = (x - x0) / STEP;
        double fz = (z - z0) / STEP;

        double a = field(x0, z0);
        double b = field(x0 + STEP, z0);
        double c = field(x0, z0 + STEP);
        double d = field(x0 + STEP, z0 + STEP);

        return fx + fz <= 1
                ? a + (b - a) * fx + (c - a) * fz
                : d + (c - d) * (1 - fx) + (b - d) * (1 - fz);
    }

    private static double field(double x, double z) {
        double h = naturalHeight(x, z);
        LocalRoads nearby = neighborhood(x, z);
        RoadHit hit = nearestRoad(x, z, nearby);

        if (hit != null) {
            double w = hit.road.type.halfWidth;
            double blend = 1.0 - smooth(w + 0.7, w + 19.0, hit.distance);
            h = lerp(h, hit.road.y(hit.t), blend);
        }

        for (Node n : nearby.nodes) {
            double d = Math.hypot(x - n.x, z - n.z);

            if (d < 23.0) {
                h = lerp(h, n.y, 1.0 - smooth(9.0, 23.0, d));
            }
        }

        return h;
    }

    private static double naturalHeight(double x, double z) {
        double warpX = x + 62 * noise(x / 460 + 31, z / 460 - 57);
        double warpZ = z + 62 * noise(x / 460 - 19, z / 460 + 71);

        double broad = fbm(warpX / 540, warpZ / 540, 4);
        double small = fbm(warpX / 116 + 23, warpZ / 116 - 19, 3);

        double mountain = smooth(0.04, 0.55,
                noise(warpX / 1010 + 42, warpZ / 1010 - 18));

        double ridge = 1 - Math.abs(fbm(warpX / 205 - 25,
                warpZ / 205 + 76, 3));

        double h = 16 + broad * 29 + small * 12
                + mountain * (29 + 163 * ridge * ridge * ridge);

        double arid = 1 - smooth(0.24, 0.45, wetness(x, z));

        h += arid * fbm(warpX / 74 + 37, warpZ / 74 - 28, 3) * 9;
        h -= riverStrength(x, z) * (3.5 + 4 * wetness(x, z));

        return h;
    }

    private static double wetness(double x, double z) {
        return clamp01(0.50 + 0.72 * noise(x / 1080 + 11, z / 1080 - 39)
                + 0.13 * noise(x / 340 - 62, z / 340 + 14));
    }

    private static double temperature(double x, double z) {
        return clamp01(0.51 + 0.68 * noise(x / 1420 - 67, z / 1420 + 28));
    }

    private static double population(double x, double z) {
        double n = 0.5 + 0.5 * noise(x / 1250 + 84, z / 1250 + 11);
        return smooth(0.28, 0.79, n);
    }

    private static double riverStrength(double x, double z) {
        double warp = noise(x / 390 + 21, z / 390 - 82) * 2.9;
        double channel = Math.abs(Math.sin(x / 150 + z / 225 + warp));
        double climate = smooth(0.26, 0.53, wetness(x, z));

        return (1 - smooth(0.035, 0.14, channel)) * climate;
    }

    private static int terrainColour(double x, double z, double h, double slope) {
        double patch = 0.5 + 0.5 * noise(x / 45 - 10, z / 45 + 26);
        double wet = wetness(x, z);

        int base;

        switch (biomeFor(x, z, h)) {
            case DESERT -> base = mix(0xFFCFB986, 0xFFE0C58C, patch);
            case BADLANDS -> base = mix(0xFFAD7854, 0xFFBE9870, patch);
            case TUNDRA -> base = mix(0xFF8E9B87, 0xFFB3B5A4, patch);
            case ALPINE -> base = mix(0xFF617A60, 0xFF8C9285, patch);
            case VOLCANIC -> base = mix(0xFF444845, 0xFF777067, patch);
            case WETLAND -> base = mix(0xFF385C41, 0xFF658660, patch);
            default -> base = mix(0xFFAAA16A, 0xFF47784B, wet);
        }

        base = mix(base, 0xFF819358, patch * 0.20);

        double rock = Math.max(smooth(0.65, 1.45, slope),
                smooth(105, 205, h) * 0.65);

        base = mix(base, mix(0xFF676F6C, 0xFFAC9C83, patch), rock);

        base = mix(base, 0xFFE8EDF0,
                smooth(132, 205, h) * (1 - smooth(0.8, 1.5, slope)));

        base = mix(base, 0xFF42778A, riverStrength(x, z) * 0.90);

        RoadHit hit = nearestRoad(x, z);

        if (hit != null) {
            double shoulder = 1 - smooth(hit.road.type.halfWidth + 1,
                    hit.road.type.halfWidth + 7, hit.distance);

            base = mix(base, PALETTE[hit.road.type.material], shoulder * 0.60);
        }

        return base;
    }

    private static Node node(long gx, long gz) {
        double x = (gx + 0.5) * NETWORK_CELL
                + 19.0 * noise(gx / 3.8 + 14, gz / 4.1 - 12)
                + 2.2 * (hash(gx, gz, 101) - 0.5);

        double z = (gz + 0.5) * NETWORK_CELL
                + 19.0 * noise(gx / 4.2 - 31, gz / 3.9 + 22)
                + 2.2 * (hash(gx, gz, 103) - 0.5);

        double y = naturalHeight(x, z) * 0.34;

        double d = 48;
        y += (naturalHeight(x - d, z) + naturalHeight(x + d, z)
                + naturalHeight(x, z - d) + naturalHeight(x, z + d)) * 0.12;

        d = 96;
        y += (naturalHeight(x - d, z) + naturalHeight(x + d, z)
                + naturalHeight(x, z - d) + naturalHeight(x, z + d)) * 0.045;

        return new Node(x, z, y);
    }

    private static boolean verticalCorridor(long gx) {
        return Math.floorMod(gx - BACKBONE_COLUMN, 5) == 0;
    }

    private static boolean horizontalCorridor(long gz) {
        return Math.floorMod(gz - ARTERIAL_ROW, 5) == 0;
    }

    private static Road road(long gx, long gz, int axis) {
        long bx = gx + (axis == 0 ? 1 : 0);
        long bz = gz + (axis == 1 ? 1 : 0);

        double midX = (gx + 0.5 + (axis == 0 ? 0.5 : 0)) * NETWORK_CELL;
        double midZ = (gz + 0.5 + (axis == 1 ? 0.5 : 0)) * NETWORK_CELL;

        double pop = population(midX, midZ);

        boolean mainNS = axis == 1 && verticalCorridor(gx);
        boolean mainEW = axis == 0 && horizontalCorridor(gz);
        boolean backbone = axis == 1 && gx == BACKBONE_COLUMN;

        boolean townStreet = pop > 0.65 && (axis == 0
                ? Math.floorMod(gz - ARTERIAL_ROW, 2) == 0
                : Math.floorMod(gx - BACKBONE_COLUMN, 2) == 0);

        boolean touchesCorridor = verticalCorridor(gx) || verticalCorridor(bx)
                || horizontalCorridor(gz) || horizontalCorridor(bz);

        boolean ruralSpur = touchesCorridor
                && hash(gx, gz, 310 + axis) < 0.045 + 0.10 * pop;

        if (!(mainNS || mainEW || townStreet || ruralSpur)) {
            return null;
        }

        Node a = node(gx, gz);
        Node b = node(bx, bz);

        RoadType type;

        if (backbone) {
            type = RoadType.MAIN;
        } else if (mainNS) {
            type = hash(gx, 0, 351) > 0.85
                    ? RoadType.EXPRESS : RoadType.MAIN;
        } else if (mainEW) {
            type = hash(0, gz, 353) > 0.48
                    ? RoadType.MAIN : RoadType.COUNTRY;
        } else if (townStreet) {
            type = pop > 0.80 ? RoadType.COUNTRY : RoadType.GRAVEL;
        } else {
            type = hash(gx, gz, 359 + axis) > 0.54
                    ? RoadType.DIRT : RoadType.TRACK;
        }

        if (!mainNS && !mainEW
                && Math.abs(a.y - b.y) / Math.hypot(b.x - a.x, b.z - a.z)
                > 0.145) {
            return null;
        }

        double bend = (hash(gx, gz, 370 + axis) * 2.0 - 1.0)
                * (mainNS || mainEW ? 3.0 : 6.0);

        return new Road(a, b, bend, type);
    }

    private static Road backboneAt(double z) {
        long row = (long) Math.floor(z / NETWORK_CELL);

        for (long j = row - 2; j <= row + 1; j++) {
            Road r = road(BACKBONE_COLUMN, j, 1);

            if (r != null && z >= r.a.z && z <= r.b.z) {
                return r;
            }
        }

        return road(BACKBONE_COLUMN, row, 1);
    }

    private static double crossingParameter(Road r, double z) {
        double lo = 0, hi = 1;

        for (int i = 0; i < 18; i++) {
            double mid = (lo + hi) * 0.5;

            if (r.z(mid) < z) lo = mid;
            else hi = mid;
        }

        return (lo + hi) * 0.5;
    }

    private static LocalRoads neighborhood(double x, double z) {
        long gx = (long) Math.floor(x / NETWORK_CELL);
        long gz = (long) Math.floor(z / NETWORK_CELL);

        Key key = new Key(gx, gz);
        LinkedHashMap<Key, LocalRoads> cache = ROAD_CACHE.get();

        LocalRoads result = cache.get(key);
        if (result != null) return result;

        ArrayList<Road> edges = new ArrayList<>();
        ArrayList<Node> nodes = new ArrayList<>();

        for (long dz = -2; dz <= 1; dz++) {
            for (long dx = -2; dx <= 1; dx++) {
                long cx = gx + dx;
                long cz = gz + dz;

                for (int axis = 0; axis < 2; axis++) {
                    Road r = road(cx, cz, axis);

                    if (r != null) edges.add(r);
                }

                if (dx >= -1 && dz >= -1 && isJunction(cx, cz)) {
                    nodes.add(node(cx, cz));
                }
            }
        }

        result = new LocalRoads(edges.toArray(new Road[0]),
                nodes.toArray(new Node[0]));

        cache.put(key, result);
        return result;
    }

    private static boolean isJunction(long gx, long gz) {
        return road(gx, gz, 0) != null
                || road(gx - 1, gz, 0) != null
                || road(gx, gz, 1) != null
                || road(gx, gz - 1, 1) != null;
    }

    private static RoadHit nearestRoad(double x, double z) {
        return nearestRoad(x, z, neighborhood(x, z));
    }

    private static RoadHit nearestRoad(double x, double z, LocalRoads nearby) {
        RoadHit best = null;
        double bestClearance = 40;

        for (Road road : nearby.roads) {
            double reach = Math.abs(road.bend) + road.type.halfWidth + 25;

            if (x < Math.min(road.a.x, road.b.x) - reach
                    || x > Math.max(road.a.x, road.b.x) + reach
                    || z < Math.min(road.a.z, road.b.z) - reach
                    || z > Math.max(road.a.z, road.b.z) + reach) {
                continue;
            }

            double[] result = road.closest(x, z);
            double clear = result[0] - road.type.halfWidth;

            if (clear < bestClearance) {
                bestClearance = clear;
                best = new RoadHit(road, result[0], result[1]);
            }
        }

        return best;
    }

    private static void addRoad(MeshBuilder b, Key key, Road r) {
        int sections = Math.max(2, (int) Math.ceil(r.length / ROAD_SAMPLE));
        double w = r.type.halfWidth;

        for (int i = 0; i < sections; i++) {
            double t0 = i / (double) sections;
            double t1 = (i + 1.0) / sections;
            double tm = (t0 + t1) * 0.5;

            if (!keyAt(r.x(tm), r.z(tm)).equals(key)) continue;

            surfaceStrip(b, r, t0, t1, -w - 0.95, w + 0.95,
                    SHOULDER_LIFT, r.type.paved() ? GRAVEL : DIRT, 8);

            surfaceStrip(b, r, t0, t1, -w, w,
                    ROAD_LIFT, r.type.material, ROAD_WIDTH_SLICES);

            double fromStart = tm * r.length;
            double fromEnd = (1 - tm) * r.length;

            boolean approachesJunction = fromStart < 20 || fromEnd < 20;

            if (r.type.paved() && !approachesJunction) {
                surfaceStrip(b, r, t0, t1, -w + 0.30, -w + 0.43,
                        MARKING_LIFT, WHITE, 1);

                surfaceStrip(b, r, t0, t1, w - 0.43, w - 0.30,
                        MARKING_LIFT, WHITE, 1);

                if (r.type == RoadType.EXPRESS) {
                    surfaceStrip(b, r, t0, t1, -0.26, -0.13,
                            MARKING_LIFT, YELLOW, 1);

                    surfaceStrip(b, r, t0, t1, 0.13, 0.26,
                            MARKING_LIFT, YELLOW, 1);

                    if (dashOn(r, tm)) {
                        stripe(b, r, t0, t1, -w * 0.50, WHITE);
                        stripe(b, r, t0, t1, w * 0.50, WHITE);
                    }
                } else if (dashOn(r, tm)) {
                    stripe(b, r, t0, t1, 0, YELLOW);
                }
            }

            if (fromStart < 35 || fromEnd < 35) continue;

            double cx = r.x(tm);
            double cz = r.z(tm);
            double[] n = r.normal(tm);

            for (int side = -1; side <= 1; side += 2) {
                double off = side * (w + 1.65);
                double shoulderX = cx + n[0] * off;
                double shoulderZ = cz + n[1] * off;

                double outsideX = cx + n[0] * (off + side * 12);
                double outsideZ = cz + n[1] * (off + side * 12);

                double drop = heightAt(shoulderX, shoulderZ)
                        - naturalHeight(outsideX, outsideZ);

                if (drop < 5.8 || nearActiveNode(shoulderX, shoulderZ, 34)) {
                    continue;
                }

                if (anotherRoadNear(r, shoulderX, shoulderZ, 13)) continue;

                rail(b, r, t0, t1, off);

                if (i % 3 == 0) {
                    double[] p = surfacePoint(r, t0, off, 0.03);

                    b.box(p[0] - 0.09, p[1] - 0.10, p[2] - 0.09,
                            p[0] + 0.09, p[1] + 1.05, p[2] + 0.09, METAL);
                }
            }
        }
    }

    private static boolean dashOn(Road r, double t) {
        return (r.length * t) % 12.0 < 7.0;
    }

    private static void stripe(MeshBuilder b, Road r, double t0,
                               double t1, double offset, int material) {
        surfaceStrip(b, r, t0, t1, offset - 0.075, offset + 0.075,
                MARKING_LIFT, material, 1);
    }

    private static double[] surfacePoint(Road r, double t,
                                         double offset, double lift) {
        double[] n = r.normal(t);

        double x = r.x(t) + n[0] * offset;
        double z = r.z(t) + n[1] * offset;

        return point(x, heightAt(x, z) + lift, z);
    }

    private static void surfaceStrip(MeshBuilder b, Road r,
                                     double t0, double t1,
                                     double left, double right,
                                     double lift, int material, int slices) {
        double[] prevL = surfacePoint(r, t0, left, lift);
        double[] prevR = null;

        for (int j = 0; j < slices; j++) {
            double off = lerp(left, right, (j + 1.0) / slices);

            double[] nextL = surfacePoint(r, t0, off, lift);
            double[] nextR = surfacePoint(r, t1, off, lift);

            if (j == 0) {
                prevR = surfacePoint(r, t1, left, lift);
            }

            b.surfaceQuad(nextL, nextR, prevR, prevL, material, true);

            prevL = nextL;
            prevR = nextR;
        }
    }

    private static boolean nearActiveNode(double x, double z, double radius) {
        for (Node n : neighborhood(x, z).nodes) {
            if (Math.hypot(x - n.x, z - n.z) < radius) {
                return true;
            }
        }

        return false;
    }

    private static boolean anotherRoadNear(Road except, double x, double z,
                                           double clearance) {
        for (Road road : neighborhood(x, z).roads) {
            if (road.sameEdge(except)) continue;

            double[] closest = road.closest(x, z);

            if (closest[0] < road.type.halfWidth + clearance) {
                return true;
            }
        }

        return false;
    }

    private static void rail(MeshBuilder b, Road r, double t0,
                             double t1, double offset) {
        double[] a = surfacePoint(r, t0, offset, 0.65);
        double[] c = surfacePoint(r, t1, offset, 0.65);
        double[] d = surfacePoint(r, t1, offset, 1.03);
        double[] e = surfacePoint(r, t0, offset, 1.03);

        b.quad(a, c, d, e, METAL, true);
    }

    private static void addJunction(MeshBuilder b, Node n) {
        long gx = (long) Math.floor(n.x / NETWORK_CELL);
        long gz = (long) Math.floor(n.z / NETWORK_CELL);

        Road[] edges = {
                road(gx, gz, 0),
                road(gx - 1, gz, 0),
                road(gx, gz, 1),
                road(gx, gz - 1, 1)
        };

        int material = DIRT;
        int priority = -1;

        ArrayList<double[]> corners = new ArrayList<>();

        for (Road r : edges) {
            if (r == null) continue;

            int rank = RoadType.values().length - r.type.ordinal();

            if (rank > priority) {
                priority = rank;
                material = r.type.material;
            }

            boolean atStart = r.a.x == n.x && r.a.z == n.z;

            double step = Math.min(0.48,
                    (11.0 + r.type.halfWidth) / r.length);

            double t = atStart ? step : 1.0 - step;
            double[] normal = r.normal(t);

            double x = r.x(t);
            double z = r.z(t);
            double width = r.type.halfWidth + 0.15;

            corners.add(point(x - normal[0] * width, 0,
                    z - normal[1] * width));

            corners.add(point(x + normal[0] * width, 0,
                    z + normal[1] * width));
        }

        if (priority < 0) return;

        if (corners.size() == 2) {
            Road r = null;

            for (Road edge : edges) {
                if (edge != null) {
                    r = edge;
                    break;
                }
            }

            double[] endN = r.normal(
                    r.a.x == n.x && r.a.z == n.z ? 0 : 1);

            double w = r.type.halfWidth;

            corners.add(point(n.x - endN[0] * w, 0,
                    n.z - endN[1] * w));

            corners.add(point(n.x + endN[0] * w, 0,
                    n.z + endN[1] * w));
        }

        corners.add(point(n.x, 0, n.z));

        ArrayList<double[]> hull = convexHull(corners);
        double[] center = point(n.x, 0, n.z);

        for (int i = 0; i < hull.size(); i++) {
            double[] a = hull.get(i);
            double[] c = hull.get((i + 1) % hull.size());

            tessellatedJunctionTriangle(b, center, a, c, material, 6);
        }
    }

    private static ArrayList<double[]> convexHull(ArrayList<double[]> points) {
        points.sort((p, q) -> {
            int x = Double.compare(p[0], q[0]);
            return x != 0 ? x : Double.compare(p[2], q[2]);
        });

        ArrayList<double[]> hull = new ArrayList<>();

        for (double[] p : points) {
            while (hull.size() >= 2 && cross(hull.get(hull.size() - 2),
                    hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }

            hull.add(p);
        }

        int lower = hull.size() + 1;

        for (int i = points.size() - 2; i >= 0; i--) {
            double[] p = points.get(i);

            while (hull.size() >= lower && cross(hull.get(hull.size() - 2),
                    hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }

            hull.add(p);
        }

        if (hull.size() > 1) {
            hull.remove(hull.size() - 1);
        }

        return hull;
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[2] - a[2])
                - (b[2] - a[2]) * (c[0] - a[0]);
    }

    private static void tessellatedJunctionTriangle(MeshBuilder b,
                                                    double[] a, double[] c,
                                                    double[] d,
                                                    int material, int count) {
        for (int row = 0; row < count; row++) {
            for (int col = 0; col <= row; col++) {
                double[] p0 = junctionPoint(a, c, d, row, col, count);
                double[] p1 = junctionPoint(a, c, d, row + 1, col, count);
                double[] p2 = junctionPoint(a, c, d, row + 1, col + 1, count);

                b.surfaceTriangle(p0, p1, p2, material, true);

                if (col < row) {
                    double[] p3 = junctionPoint(a, c, d, row, col + 1, count);
                    b.surfaceTriangle(p0, p2, p3, material, true);
                }
            }
        }
    }

    private static double[] junctionPoint(double[] center, double[] left,
                                          double[] right, int row,
                                          int col, int count) {
        double u = (row - col) / (double) count;
        double v = col / (double) count;

        double x = center[0] * (1 - u - v)
                + left[0] * u + right[0] * v;

        double z = center[2] * (1 - u - v)
                + left[2] * u + right[2] * v;

        return point(x, heightAt(x, z) + JUNCTION_LIFT, z);
    }

    private static void addScenery(MeshBuilder b, Key key) {
        Random random = new Random(mixSeed(key.x + 417, key.z - 291, 800));

        for (int i = 0; i < 10; i++) {
            double x = b.originX + 3
                    + random.nextDouble() * (CHUNK_SIZE - 6);

            double z = b.originZ + 3
                    + random.nextDouble() * (CHUNK_SIZE - 6);

            RoadHit near = nearestRoad(x, z);

            double clearance = near == null ? 1000
                    : near.distance - near.road.type.halfWidth;

            if (clearance < 14 || nearActiveNode(x, z, 29)) continue;

            double y = heightAt(x, z);

            double slope = Math.hypot(
                    heightAt(x + 3, z) - heightAt(x - 3, z),
                    heightAt(x, z + 3) - heightAt(x, z - 3)) / 6.0;

            if (slope > 0.47) continue;

            Biome biome = biomeAt(x, z);

            double forest = clamp01(0.5
                    + 0.55 * noise(x / 230 + 54, z / 230 - 19));

            double density = switch (biome) {
                case GRASSLAND -> 0.23 + forest * 0.48;
                case WETLAND -> 0.50;
                case ALPINE, TUNDRA -> 0.18;
                case DESERT, BADLANDS -> 0.07;
                case VOLCANIC -> 0.035;
            };

            boolean canBuild = i < 3
                    && clearance > 21
                    && clearance < 65
                    && slope < 0.15
                    && population(x, z) > 0.55
                    && biome != Biome.ALPINE
                    && biome != Biome.WETLAND;

            if (canBuild && random.nextDouble() < 0.34) {
                building(b, x, y, z, random);
            } else if (random.nextDouble() < density && clearance > 18) {
                if (biome == Biome.DESERT || biome == Biome.BADLANDS) {
                    cactus(b, x, y, z, 2.8 + random.nextDouble() * 3);
                } else {
                    tree(b, x, y, z, 6 + random.nextDouble() * 9,
                            1.7 + random.nextDouble() * 2.0, random,
                            biome == Biome.ALPINE || biome == Biome.TUNDRA);
                }
            } else if (random.nextDouble() < 0.17 && clearance > 17) {
                rock(b, x, y, z, 0.6 + random.nextDouble() * 2.5, random);
            }
        }
    }

    private static void tree(MeshBuilder b, double x, double y, double z,
                             double height, double radius, Random r,
                             boolean conifer) {
        b.box(x - 0.20, y - 0.12, z - 0.20,
                x + 0.20, y + height * 0.72, z + 0.20, WOOD);

        double rotation = r.nextDouble() * 2 * Math.PI;
        int layers = conifer ? 4 : 3;

        for (int layer = 0; layer < layers; layer++) {
            double bottom = y + height * (0.23 + layer * 0.17);
            double top = y + height * (0.68 + layer * 0.105);
            double rad = radius * (1.1 - layer * (conifer ? 0.20 : 0.16));

            for (int side = 0; side < 6; side++) {
                double a = rotation + side * Math.PI / 3;
                double c = a + Math.PI / 3;

                b.triangle(
                        point(x + Math.cos(a) * rad, bottom,
                                z + Math.sin(a) * rad),
                        point(x, top, z),
                        point(x + Math.cos(c) * rad, bottom,
                                z + Math.sin(c) * rad),
                        layer % 2 == 0 ? LEAF_DARK : LEAF_LIGHT,
                        true);
            }
        }
    }

    private static void cactus(MeshBuilder b, double x, double y, double z,
                               double height) {
        b.box(x - 0.28, y, z - 0.28,
                x + 0.28, y + height, z + 0.28, LEAF_DARK);

        b.box(x - 1.0, y + height * 0.45, z - 0.19,
                x + 0.5, y + height * 0.62, z + 0.19, LEAF_DARK);

        b.box(x - 1.05, y + height * 0.45, z - 0.19,
                x - 0.65, y + height * 0.78, z + 0.19, LEAF_DARK);
    }

    private static void rock(MeshBuilder b, double x, double y, double z,
                             double size, Random random) {
        double rotation = random.nextDouble() * Math.PI * 2;

        for (int i = 0; i < 6; i++) {
            double a = rotation + i * Math.PI / 3;
            double c = a + Math.PI / 3;

            double x0 = x + Math.cos(a) * size;
            double z0 = z + Math.sin(a) * size;
            double x1 = x + Math.cos(c) * size;
            double z1 = z + Math.sin(c) * size;

            b.triangle(
                    point(x0, heightAt(x0, z0) - 0.25, z0),
                    point(x + (random.nextDouble() - 0.5) * 0.3,
                            y + size * 0.75, z),
                    point(x1, heightAt(x1, z1) - 0.25, z1),
                    STONE, true);
        }
    }

    private static void building(MeshBuilder b, double x, double y, double z,
                                 Random r) {
        double w = 3.0 + r.nextDouble() * 3.0;
        double depth = 3.0 + r.nextDouble() * 3.0;
        double h = 3.2 + r.nextDouble() * 4.3;

        double h00 = heightAt(x - w, z - depth);
        double h10 = heightAt(x + w, z - depth);
        double h01 = heightAt(x - w, z + depth);
        double h11 = heightAt(x + w, z + depth);

        double floor = Math.max(Math.max(h00, h10), Math.max(h01, h11));
        double base = Math.min(Math.min(h00, h10), Math.min(h01, h11));

        b.box(x - w - 0.15, base - 0.25, z - depth - 0.15,
                x + w + 0.15, floor + 0.12, z + depth + 0.15, CONCRETE);

        y = floor + 0.12;

        b.box(x - w, y, z - depth,
                x + w, y + h, z + depth, WALL);

        double[] a = point(x - w - 0.6, y + h, z - depth - 0.7);
        double[] c = point(x + w + 0.6, y + h, z - depth - 0.7);
        double[] d = point(x - w - 0.6, y + h, z + depth + 0.7);
        double[] e = point(x + w + 0.6, y + h, z + depth + 0.7);

        double[] ridge0 = point(x, y + h + 2.0, z - depth - 0.7);
        double[] ridge1 = point(x, y + h + 2.0, z + depth + 0.7);

        b.quad(a, ridge0, ridge1, d, ROOF, true);
        b.quad(ridge0, c, e, ridge1, ROOF, true);
    }

    // Generate a unique but deterministic texture atlas for each world chunk.
    // Layers 1, 2 and 3 contain smooth, world-aligned road surface textures.
    private static Texture texture(int[] colours, Key key) {
        int s = TEXTURE_SIZE;
        int atlasHeight = s * ATLAS_LAYERS;
        int[] pixels = new int[s * atlasHeight];

        // Terrain layer.
        for (int z = 0; z < s; z++) {
            for (int x = 0; x < s; x++) {
                double gx = x * (double) CELLS_PER_CHUNK / (s - 1);
                double gz = z * (double) CELLS_PER_CHUNK / (s - 1);

                int ix = Math.min(CELLS_PER_CHUNK - 1, (int) gx);
                int iz = Math.min(CELLS_PER_CHUNK - 1, (int) gz);

                int a = mix(colours[iz * N + ix],
                        colours[iz * N + ix + 1], gx - ix);

                int c = mix(colours[(iz + 1) * N + ix],
                        colours[(iz + 1) * N + ix + 1], gx - ix);

                long wx = Math.round(
                        (key.x * (double) CHUNK_SIZE + gx * STEP) * 3);

                long wz = Math.round(
                        (key.z * (double) CHUNK_SIZE + gz * STEP) * 3);

                int grain = (int) (hash(wx, wz, 901) * 10) - 5;

                pixels[z * s + x] = shade(mix(a, c, gz - iz), grain);
            }
        }

        double startX = key.x * (double) CHUNK_SIZE - ROAD_TEXTURE_PADDING;
        double startZ = key.z * (double) CHUNK_SIZE - ROAD_TEXTURE_PADDING;

        for (int z = 0; z < s; z++) {
            double wz = startZ + z * ROAD_TEXTURE_SPAN / (s - 1.0);

            for (int x = 0; x < s; x++) {
                double wx = startX + x * ROAD_TEXTURE_SPAN / (s - 1.0);

                double age = fbm(wx / 66.0 + 13.4, wz / 66.0 - 41.7, 3);
                double patch = fbm(wx / 14.0 - 24.3, wz / 14.0 + 18.5, 3);
                double grit = noise(wx / 2.7 + 62, wz / 2.7 - 19);

                long grainX = (long) Math.floor(wx * 2.7);
                long grainZ = (long) Math.floor(wz * 2.7);

                double fleck = hash(grainX, grainZ, 2401);
                double paleStone = hash(grainX, grainZ, 2419);

                // Asphalt: varied dark aggregate, subtle patches,
                // and sparse fine wear marks. No geometry changes.
                double asphaltTone = clamp01(0.52 + age * 0.22
                        + patch * 0.20 + grit * 0.07);

                int asphalt = mix(0xFF292D31, 0xFF505253, asphaltTone);
                asphalt = shade(asphalt, (int) Math.round((fleck - 0.5) * 14));

                if (paleStone > 0.966) asphalt = shade(asphalt, 11);

                double cracks = Math.abs(noise(wx / 10.5 + 47,
                        wz / 10.5 - 64) - 0.09);

                double crackedArea = smooth(0.04, 0.40,
                        noise(wx / 42.0 - 71, wz / 42.0 + 34));

                asphalt = shade(asphalt, -(int) Math.round(
                        9.0 * (1.0 - smooth(0.012, 0.042, cracks))
                                * crackedArea));

                pixels[(s + z) * s + x] = asphalt;

                // Gravel: fine sandy material and lighter stone speckles.
                int gravel = mix(0xFF756F61, 0xFFAAA18C,
                        clamp01(0.50 + age * 0.20 + patch * 0.32));

                gravel = shade(gravel, (int) Math.round((fleck - 0.5) * 30
                        + grit * 9));

                if (paleStone > 0.89) gravel = shade(gravel, 19);
                if (fleck < 0.09) gravel = shade(gravel, -14);

                pixels[(2 * s + z) * s + x] = gravel;

                // Dirt: natural soil colour variation and scattered grains.
                int dirt = mix(0xFF5D4936, 0xFF9B7D55,
                        clamp01(0.52 + age * 0.25 + patch * 0.29));

                dirt = shade(dirt, (int) Math.round((fleck - 0.5) * 18
                        + grit * 6));

                if (paleStone > 0.96) dirt = shade(dirt, 10);

                pixels[(3 * s + z) * s + x] = dirt;
            }
        }

        // Solid-colour material palette for scenery and lane markings.
        for (int z = 0; z < s; z++) {
            for (int x = 0; x < s; x++) {
                pixels[(4 * s + z) * s + x] =
                        PALETTE[(z / (s / 2)) * 8 + x / (s / 8)];
            }
        }

        return new Texture(s, atlasHeight, pixels);
    }

    private static int roadLayer(int material) {
        return switch (material) {
            case ASPHALT -> 1;
            case GRAVEL -> 2;
            case DIRT -> 3;
            default -> -1;
        };
    }

    private static double roadU(double originX, double x) {
        return clamp01((x - originX + ROAD_TEXTURE_PADDING)
                / ROAD_TEXTURE_SPAN);
    }

    private static double roadV(double originZ, double z, int material) {
        int layer = roadLayer(material);

        double v = clamp01((z - originZ + ROAD_TEXTURE_PADDING)
                / ROAD_TEXTURE_SPAN);

        return (layer * TEXTURE_SIZE + v * (TEXTURE_SIZE - 1.0))
                / (TEXTURE_SIZE * ATLAS_LAYERS - 1.0);
    }

    private static double paletteU(int material) {
        double width = TEXTURE_SIZE / 8.0;

        return ((material % 8) * width + (width - 1) * 0.5)
                / (TEXTURE_SIZE - 1.0);
    }

    private static double paletteV(int material) {
        double height = TEXTURE_SIZE / 2.0;

        return ((ATLAS_LAYERS - 1) * TEXTURE_SIZE
                + (material / 8) * height + (height - 1) * 0.5)
                / (TEXTURE_SIZE * ATLAS_LAYERS - 1.0);
    }

    private static int[][] gridFaces() {
        int[][] faces = new int[CELLS_PER_CHUNK * CELLS_PER_CHUNK * 2][3];
        int index = 0;

        for (int z = 0; z < CELLS_PER_CHUNK; z++) {
            for (int x = 0; x < CELLS_PER_CHUNK; x++) {
                int a = z * N + x;
                int c = a + N;

                faces[index++] = new int[]{a, c, a + 1};
                faces[index++] = new int[]{a + 1, c, c + 1};
            }
        }

        return faces;
    }

    private static double[] point(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    private static double fbm(double x, double z, int octaves) {
        double v = 0, a = 1, total = 0;

        for (int i = 0; i < octaves; i++) {
            v += noise(x, z) * a;
            total += a;

            double nx = x * 1.83 + z * 0.87 + 13.7;
            double nz = z * 1.83 - x * 0.87 - 9.2;

            x = nx;
            z = nz;
            a *= 0.5;
        }

        return v / total;
    }

    private static double noise(double x, double z) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);

        double fx = fade(x - ix);
        double fz = fade(z - iz);

        return lerp(
                lerp(hash(ix, iz, 0), hash(ix + 1, iz, 0), fx),
                lerp(hash(ix, iz + 1, 0), hash(ix + 1, iz + 1, 0), fx),
                fz) * 2 - 1;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static long chooseSeed() {
        String explicit = System.getProperty("terrain.seed");

        if (explicit != null && !explicit.isBlank()) {
            return Long.decode(explicit.trim());
        }

        return new SecureRandom().nextLong();
    }

    private static long mixSeed(long x, long z, long salt) {
        long v = x * 0x632BE59BD9B4E019L
                + z * 0x9E3779B97F4A7C15L + SEED
                + salt * 0xD1B54A32D192ED03L;

        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;

        return v ^ (v >>> 31);
    }

    private static double hash(long x, long z, long salt) {
        return (mixSeed(x, z, salt) >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double smooth(double a, double b, double x) {
        double t = clamp01((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int mix(int a, int b, double t) {
        t = clamp01(t);

        int red = (int) Math.round(
                lerp((a >>> 16) & 255, (b >>> 16) & 255, t));

        int green = (int) Math.round(
                lerp((a >>> 8) & 255, (b >>> 8) & 255, t));

        int blue = (int) Math.round(
                lerp(a & 255, b & 255, t));

        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int shade(int colour, int amount) {
        int red = (int) Math.max(0,
                Math.min(255, ((colour >>> 16) & 255) + amount));

        int green = (int) Math.max(0,
                Math.min(255, ((colour >>> 8) & 255) + amount));

        int blue = (int) Math.max(0,
                Math.min(255, (colour & 255) + amount));

        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private record LocalRoads(Road[] roads, Node[] nodes) {}

    private record Key(long x, long z) {}

    private record Node(double x, double z, double y) {}

    private record RoadHit(Road road, double distance, double t) {}

    private static final class Road {
        final Node a, b;
        final double bend, length, px, pz;
        final RoadType type;

        Road(Node a, Node b, double bend, RoadType type) {
            this.a = a;
            this.b = b;
            this.bend = bend;
            this.type = type;

            length = Math.hypot(b.x - a.x, b.z - a.z);
            px = -(b.z - a.z) / length;
            pz = (b.x - a.x) / length;
        }

        private double offset(double t) {
            double v = Math.sin(Math.PI * t);
            return bend * v * v;
        }

        double x(double t) {
            return lerp(a.x, b.x, t) + px * offset(t);
        }

        double z(double t) {
            return lerp(a.z, b.z, t) + pz * offset(t);
        }

        double y(double t) {
            return lerp(a.y, b.y, t);
        }

        double[] normal(double t) {
            double derivative = bend * Math.PI
                    * Math.sin(2.0 * Math.PI * t);

            double dx = b.x - a.x + px * derivative;
            double dz = b.z - a.z + pz * derivative;
            double len = Math.hypot(dx, dz);

            return new double[]{-dz / len, dx / len};
        }

        boolean sameEdge(Road other) {
            return a.x == other.a.x
                    && a.z == other.a.z
                    && b.x == other.b.x
                    && b.z == other.b.z;
        }

        double[] closest(double qx, double qz) {
            double bestD2 = Double.POSITIVE_INFINITY;
            double bestT = 0;

            double ax = x(0);
            double az = z(0);

            final int n = 16;

            for (int i = 0; i < n; i++) {
                double t0 = i / (double) n;

                double bx = x((i + 1.0) / n);
                double bz = z((i + 1.0) / n);

                double vx = bx - ax;
                double vz = bz - az;

                double f = clamp01(
                        ((qx - ax) * vx + (qz - az) * vz)
                                / Math.max(1e-10, vx * vx + vz * vz));

                double dx = qx - (ax + f * vx);
                double dz = qz - (az + f * vz);
                double d2 = dx * dx + dz * dz;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestT = t0 + f / n;
                }

                ax = bx;
                az = bz;
            }

            return new double[]{Math.sqrt(bestD2), bestT};
        }
    }

    private static final class MeshBuilder {
        final double originX, originZ;

        final ArrayList<double[]> vertices = new ArrayList<>();
        final ArrayList<double[]> uvs = new ArrayList<>();
        final ArrayList<int[]> faces = new ArrayList<>();

        MeshBuilder(double originX, double originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }

        int vertex(double x, double y, double z, double u, double v) {
            int i = vertices.size();

            vertices.add(point(x - originX, y, z - originZ));
            uvs.add(new double[]{u, v});

            return i;
        }

        int vertex(double[] p, int material) {
            return vertex(p[0], p[1], p[2],
                    paletteU(material), paletteV(material));
        }

        void triangle(double[] a, double[] b, double[] c,
                      int material, boolean doubleSided) {
            int ia = vertex(a, material);
            int ib = vertex(b, material);
            int ic = vertex(c, material);

            faces.add(new int[]{ia, ib, ic});

            if (doubleSided) {
                faces.add(new int[]{ic, ib, ia});
            }
        }

        void quad(double[] a, double[] b, double[] c, double[] d,
                  int material, boolean doubleSided) {
            int ia = vertex(a, material);
            int ib = vertex(b, material);
            int ic = vertex(c, material);
            int id = vertex(d, material);

            faces.add(new int[]{ia, ib, ic});
            faces.add(new int[]{ia, ic, id});

            if (doubleSided) {
                faces.add(new int[]{ic, ib, ia});
                faces.add(new int[]{id, ic, ia});
            }
        }

        // Road geometry uses world-aligned UVs rather than a flat material
        // colour, while the positions and heights remain exactly the same.
        private int surfaceVertex(double[] p, int material) {
            if (roadLayer(material) >= 0) {
                return vertex(p[0], p[1], p[2],
                        roadU(originX, p[0]),
                        roadV(originZ, p[2], material));
            }

            return vertex(p, material);
        }

        void surfaceTriangle(double[] a, double[] b, double[] c,
                             int material, boolean doubleSided) {
            int ia = surfaceVertex(a, material);
            int ib = surfaceVertex(b, material);
            int ic = surfaceVertex(c, material);

            faces.add(new int[]{ia, ib, ic});

            if (doubleSided) {
                faces.add(new int[]{ic, ib, ia});
            }
        }

        void surfaceQuad(double[] a, double[] b, double[] c, double[] d,
                         int material, boolean doubleSided) {
            int ia = surfaceVertex(a, material);
            int ib = surfaceVertex(b, material);
            int ic = surfaceVertex(c, material);
            int id = surfaceVertex(d, material);

            faces.add(new int[]{ia, ib, ic});
            faces.add(new int[]{ia, ic, id});

            if (doubleSided) {
                faces.add(new int[]{ic, ib, ia});
                faces.add(new int[]{id, ic, ia});
            }
        }

        void box(double x0, double y0, double z0,
                 double x1, double y1, double z1, int material) {
            double[] a = point(x0, y0, z0);
            double[] b = point(x1, y0, z0);
            double[] c = point(x1, y0, z1);
            double[] d = point(x0, y0, z1);

            double[] e = point(x0, y1, z0);
            double[] f = point(x1, y1, z0);
            double[] g = point(x1, y1, z1);
            double[] h = point(x0, y1, z1);

            quad(a, b, f, e, material, true);
            quad(b, c, g, f, material, true);
            quad(c, d, h, g, material, true);
            quad(d, a, e, h, material, true);
            quad(e, f, g, h, material, true);
            quad(d, c, b, a, material, true);
        }
    }

    private static final class TerrainMesh extends GameObject {
        private double[][] vertices;
        private double[][] uvs;
        private int[][] faces;

        TerrainMesh() {
            setSolid(true);
            setWireframe(false);
            setTag("terrain");
        }

        @Override
        public double[][] getVertices() {
            return vertices;
        }

        @Override
        public double[][] getUVs() {
            return uvs;
        }

        @Override
        public int[][] getFacesArray() {
            return faces;
        }

        @Override
        public void update(double dt) {
        }
    }
}
