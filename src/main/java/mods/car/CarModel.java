package mods.car;

import engine.render.Material;
import objects.GameObject;

import java.awt.Color;
import java.util.ArrayList;

/**
 * Procedural Model 3 Performance-inspired mesh.
 *
 * The passenger compartment is genuinely hollow. Structural nodes never
 * expose a default cube. Windows remain open for renderer compatibility;
 * opaque dark geometry is used only for the panoramic roof.
 */
final class CarModel {
    static final double EYE_X = -0.44;
    static final double EYE_Y = 1.285;
    static final double EYE_Z = -0.20;

    private static final double[][] EMPTY_VERTICES = new double[0][3];
    private static final int[][] EMPTY_FACES = new int[0][3];

    private static final double[][] CUBE = {
            {-.5, -.5, -.5}, {.5, -.5, -.5},
            {.5, .5, -.5}, {-.5, .5, -.5},
            {-.5, -.5, .5}, {.5, -.5, .5},
            {.5, .5, .5}, {-.5, .5, .5}
    };

    private static final int[][] FACES = {
            {0, 2, 1}, {0, 3, 2},
            {4, 5, 6}, {4, 6, 7},
            {0, 4, 7}, {0, 7, 3},
            {1, 2, 6}, {1, 6, 5},
            {3, 7, 6}, {3, 6, 2},
            {0, 1, 5}, {0, 5, 4}
    };

    private final GameObject root;
    private final ArrayList<GameObject> parts = new ArrayList<>();

    private final Node[] wheelPivots = new Node[4];
    private final Node[] wheelRotors = new Node[4];
    private final Node steering;

    private final Part[] sensorDots = new Part[512];
    private final Part[] laneDots = new Part[120];
    private final Part[] pathDots = new Part[32];
    private final Part[][] digits = new Part[3][7];

    private final Part[] screenOutline = new Part[800];

    // Fixed ground grid – markers only rise/fall, never slide
    private static final int GRID_COLS = 24;
    private static final int GRID_ROWS = 21;
    private static final double GRID_X_MIN = -12.0;
    private static final double GRID_X_MAX = 12.0;
    private static final double GRID_Z_MIN = -2.0;
    private static final double GRID_Z_MAX = 36.0;
    private static final double BASE_Y = 0.06;
    private static final double MAX_RAISE = 2.2;

    private final double[] gridX = new double[512];
    private final double[] gridZ = new double[512];
    private final double[] currentRaise = new double[512];
    private final double[] targetRaise = new double[512];
    private long lastMarkerSmooth = System.nanoTime();

    private final Material paint = material(190, 31, 43);
    private final Material paintLight = material(218, 44, 54);
    private final Material black = material(16, 18, 21);
    private final Material trim = material(42, 44, 48);
    private final Material leather = material(37, 38, 41);
    private final Material silver = material(153, 159, 165);
    private final Material glass = material(26, 40, 49);
    private final Material white = material(237, 241, 241);
    private final Material blue = material(58, 148, 220);
    private final Material amber = material(235, 158, 40);
    private final Material red = material(235, 48, 48);

    CarModel(GameObject root) {
        this.root = root;

        buildShell();
        buildCabin();

        Node column = node(root, "Steering column", -.44, .925, .43);
        column.transform.rotation.x = Math.toRadians(-12);

        steering = node(column, "Rotating steering wheel", 0, 0, 0);

        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            Part rim = box(
                    steering, "Steering rim",
                    .176 * Math.cos(a), .166 * Math.sin(a), 0,
                    .032, .029, .034, black
            );
            rim.transform.rotation.z = a + Math.PI / 2;
        }

        box(steering, "Airbag", 0, -.005, -.008,
                .135, .092, .047, trim);

        box(steering, "Left spoke", -.10, -.024, 0,
                .13, .028, .028, trim);

        box(steering, "Right spoke", .10, -.024, 0,
                .13, .028, .028, trim);

        box(steering, "Lower spoke", 0, -.103, 0,
                .030, .108, .029, silver);

        box(steering, "Left scroll wheel", -.11, -.017, -.027,
                .024, .017, .009, silver);

        box(steering, "Right scroll wheel", .11, -.017, -.027,
                .024, .017, .009, silver);

        buildScreen();
        buildWheels();

        for (GameObject part : parts) {
            part.transform.invalidate();
        }

        // Bind each marker to a fixed ground cell
        for (int i = 0; i < sensorDots.length; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            if (row >= GRID_ROWS) {
                gridX[i] = 0;
                gridZ[i] = 0;
                currentRaise[i] = 0;
                targetRaise[i] = 0;
                continue;
            }
            double u = col / (double) (GRID_COLS - 1);
            double v = row / (double) (GRID_ROWS - 1);
            gridX[i] = GRID_X_MIN + u * (GRID_X_MAX - GRID_X_MIN);
            gridZ[i] = GRID_Z_MIN + v * (GRID_Z_MAX - GRID_Z_MIN);
            currentRaise[i] = 0;
            targetRaise[i] = 0;
        }
    }

    private void buildShell() {
        box(root, "Battery floor", 0, .245, -.03,
                1.62, .19, 3.03, black);

        box(root, "Cabin floor", 0, .375, -.26,
                1.51, .055, 2.72, black);

        double[] zs = {-2.36, -2.12, -1.65, -1.10, -.40, .45, 1.15, 1.80, 2.35};
        double[] ws = {.70, .84, .915, .90, .89, .89, .915, .85, .71};
        double[] tops = {.69, .80, .92, .97, .985, .98, .925, .84, .70};

        for (int side : new int[]{-1, 1}) {
            for (int i = 0; i < zs.length - 1; i++) {
                sidePanel(
                        side,
                        zs[i], zs[i + 1],
                        ws[i], ws[i + 1],
                        tops[i], tops[i + 1]
                );
            }

            box(root, "Side skirt", side * .87, .35, -.02,
                    .075, .065, 2.65, black);

            beam(root, "Window belt trim",
                    side * .845, .98, -1.28,
                    side * .845, .98, 1.04, .022, black);

            beam(root, "A pillar",
                    side * .80, .95, 1.17,
                    side * .66, 1.405, .34, .046, trim);

            beam(root, "B pillar",
                    side * .84, .98, -.35,
                    side * .665, 1.43, -.40, .039, black);

            beam(root, "C pillar",
                    side * .80, .95, -1.48,
                    side * .65, 1.37, -1.03, .072, paint);

            beam(root, "Roof rail",
                    side * .66, 1.405, .34,
                    side * .65, 1.37, -1.03, .037, black);

            box(root, "Front flush handle", side * .899, .89, .01,
                    .018, .022, .155, black);

            box(root, "Rear flush handle", side * .899, .89, -.91,
                    .018, .022, .155, black);

            box(root, "Mirror arm", side * .91, 1.01, .80,
                    .14, .037, .065, black);

            box(root, "Mirror shell", side * 1.025, 1.03, .77,
                    .20, .105, .235, paint);

            box(root, "Mirror surface", side * 1.025, 1.03, .648,
                    .16, .071, .008, glass);

            Part lamp = box(root, "Slim front LED", side * .58, .755, 2.326,
                    .37, .031, .025, white);
            lamp.transform.rotation.y = side * -.13;

            box(root, "Front corner intake", side * .69, .48, 2.319,
                    .13, .14, .035, black);

            box(root, "Rear horizontal lamp", side * .58, .79, -2.333,
                    .39, .045, .028, red);

            box(root, "Rear lamp corner", side * .775, .74, -2.27,
                    .032, .12, .10, red);

            box(root, "Side camera", side * .909, .86, 1.09,
                    .023, .047, .10, black);

            for (double axleZ : new double[]{
                    2.875 * .52, -2.875 * .48
            }) {
                for (int i = 0; i < 20; i++) {
                    double a = Math.PI * i / 20;
                    double b = Math.PI * (i + 1) / 20;

                    beam(root, "Wheel arch",
                            side * .915,
                            .335 + Math.sin(a) * .395,
                            axleZ + Math.cos(a) * .395,
                            side * .915,
                            .335 + Math.sin(b) * .395,
                            axleZ + Math.cos(b) * .395,
                            .035, paintLight);
                }
            }
        }

        curvedDeck("Hood", 1.10, 2.35, .89, .71, .925, .70, paint);
        curvedDeck("Rear deck", -2.36, -1.42, .70, .87, .79, .94, paint);

        box(root, "Front bumper", 0, .49, 2.30,
                1.48, .22, .13, paint);

        box(root, "Front lower intake", 0, .405, 2.375,
                1.11, .09, .022, black);

        box(root, "Front splitter", 0, .32, 2.34,
                1.61, .033, .19, black);

        box(root, "Rear bumper", 0, .48, -2.31,
                1.51, .23, .13, paint);

        box(root, "Rear diffuser", 0, .33, -2.36,
                1.38, .10, .16, black);

        Part spoiler = box(root, "Carbon spoiler", 0, .955, -2.13,
                1.48, .026, .14, black);
        spoiler.transform.rotation.x = -.10;

        curvedDeck("Panoramic roof", -1.03, .34,
                .65, .66, 1.37, 1.405, glass);

        beam(root, "Windscreen top",
                -.66, 1.405, .34, .66, 1.405, .34, .031, black);

        beam(root, "Windscreen lower edge",
                -.80, .95, 1.17, .80, .95, 1.17, .028, trim);

        box(root, "Front camera housing", 0, 1.355, .39,
                .12, .055, .095, black);
    }

    private void sidePanel(
            int side,
            double z0, double z1,
            double width0, double width1,
            double top0, double top1
    ) {
        int slices = 6;

        for (int i = 0; i < slices; i++) {
            double t0 = i / (double) slices;
            double t1 = (i + 1) / (double) slices;
            double a = mix(z0, z1, t0);
            double b = mix(z0, z1, t1);

            double bottomA = archBottom(a);
            double bottomB = archBottom(b);

            double xa = side * mix(width0, width1, t0);
            double xb = side * mix(width0, width1, t1);
            double ya = mix(top0, top1, t0);
            double yb = mix(top0, top1, t1);

            double innerA = xa - side * .045;
            double innerB = xb - side * .045;

            mesh(root, "Hollow body side", new double[][]{
                    {xa, bottomA, a},
                    {innerA, bottomA, a},
                    {innerA, ya, a},
                    {xa, ya, a},
                    {xb, bottomB, b},
                    {innerB, bottomB, b},
                    {innerB, yb, b},
                    {xb, yb, b}
            }, FACES, paint);
        }
    }

    private static double archBottom(double z) {
        double bottom = .36;

        for (double axle : new double[]{2.875 * .52, -2.875 * .48}) {
            double d = Math.abs(z - axle);
            if (d < .40) {
                bottom = Math.max(
                        bottom,
                        .335 + Math.sqrt(.40 * .40 - d * d)
                );
            }
        }

        return bottom;
    }

    private void curvedDeck(
            String name,
            double z0, double z1,
            double w0, double w1,
            double y0, double y1,
            Material material
    ) {
        int across = 12;
        int along = 6;

        double[][] v = new double[(across + 1) * (along + 1)][3];
        int[][] f = new int[across * along * 4][3];
        int face = 0;

        for (int j = 0; j <= along; j++) {
            double t = j / (double) along;
            double width = mix(w0, w1, t);

            for (int i = 0; i <= across; i++) {
                double u = i / (double) across * 2 - 1;
                int k = j * (across + 1) + i;

                v[k][0] = width * u;
                v[k][1] = mix(y0, y1, t) + .035 * (1 - u * u);
                v[k][2] = mix(z0, z1, t);
            }
        }

        for (int j = 0; j < along; j++) {
            for (int i = 0; i < across; i++) {
                int a = j * (across + 1) + i;
                int b = a + 1;
                int c = a + across + 2;
                int d = a + across + 1;

                f[face++] = new int[]{a, c, b};
                f[face++] = new int[]{a, d, c};
                f[face++] = new int[]{a, b, c};
                f[face++] = new int[]{a, c, d};
            }
        }

        mesh(root, name, v, f, material);
    }

    private void buildCabin() {
        box(root, "Dashboard", 0, .89, .98,
                1.53, .13, .35, trim);

        box(root, "Dashboard decor", 0, .91, .796,
                1.48, .036, .021, silver);

        box(root, "Dashboard ambient strip", 0, .936, .786,
                1.46, .008, .012, blue);

        box(root, "Lower dashboard", 0, .73, 1.02,
                1.49, .16, .18, black);

        box(root, "Centre console", 0, .60, -.13,
                .30, .30, 1.36, trim);

        box(root, "Armrest", 0, .775, -.48,
                .31, .075, .43, leather);

        Part charger = box(root, "Phone charger", 0, .77, .34,
                .29, .025, .30, black);
        charger.transform.rotation.x = -.18;

        for (double x : new double[]{-.075, .075}) {
            box(root, "Cup recess", x, .76, -.04,
                    .09, .013, .12, black);
        }

        for (double x : new double[]{-.44, .44}) {
            box(root, "Sport seat cushion", x, .55, -.29,
                    .46, .13, .58, leather);

            Part back = box(root, "Sport seat back", x, .91, -.64,
                    .43, .64, .12, leather);
            back.transform.rotation.x = -.12;

            box(root, "Sport headrest", x, 1.255, -.68,
                    .28, .20, .105, leather);

            for (int side : new int[]{-1, 1}) {
                box(root, "Seat back bolster", x + side * .215, .90, -.585,
                        .064, .49, .14, trim);

                box(root, "Cushion bolster", x + side * .215, .605, -.27,
                        .065, .09, .49, trim);
            }

            box(root, "Seat centre insert", x, .92, -.565,
                    .28, .39, .015, trim);
        }

        box(root, "Rear bench", 0, .57, -1.22,
                1.37, .17, .48, leather);

        box(root, "Rear seat back", 0, .86, -1.51,
                1.36, .49, .11, leather);

        for (double x : new double[]{-.46, 0, .46}) {
            box(root, "Rear headrest", x, 1.16, -1.50,
                    .23, .18, .10, leather);
        }

        for (int side : new int[]{-1, 1}) {
            box(root, "Door card", side * .785, .73, -.25,
                    .055, .32, 1.39, trim);

            box(root, "Door armrest", side * .725, .71, -.24,
                    .105, .055, .54, leather);

            box(root, "Door ambient strip", side * .749, .916, -.22,
                    .009, .009, 1.36, blue);
        }

        box(root, "Rear screen bezel", 0, .73, -.75,
                .23, .13, .025, black);
    }

    private void buildScreen() {
        box(root, "Centre display bezel", .20, 1.055, .71,
                .447, .270, .025, black);

        box(root, "Centre display surface", .20, 1.055, .694,
                .426, .248, .006, trim);

        for (int i = 0; i < sensorDots.length; i++) {
            sensorDots[i] = marker("Live sensor return", amber);
        }

        for (int i = 0; i < laneDots.length; i++) {
            laneDots[i] = marker("Real lane marking", white);
        }

        for (int i = 0; i < pathDots.length; i++) {
            pathDots[i] = marker("Predicted tyre path", blue);
        }

        for (int i = 0; i < screenOutline.length; i++) {
            screenOutline[i] = marker("Screen outline", white);
        }

        double[][] segments = {
                {0, .018, .015, .003},
                {.008, .009, .003, .015},
                {.008, -.009, .003, .015},
                {0, -.018, .015, .003},
                {-.008, -.009, .003, .015},
                {-.008, .009, .003, .015},
                {0, 0, .015, .003}
        };

        for (int d = 0; d < digits.length; d++) {
            for (int s = 0; s < 7; s++) {
                double[] a = segments[s];

                digits[d][s] = box(
                        root, "Live speed digit",
                        .028 + d * .025 + a[0], 1.145 + a[1], .674,
                        a[2], a[3], .003, white
                );
            }
        }
    }

    private Part marker(String name, Material material) {
        Part part = box(root, name, .20, 1.02, .681,
                .000001, .000001, .003, material);
        return part;
    }

    private void buildWheels() {
        for (int i = 0; i < 4; i++) {
            wheelPivots[i] = node(root, "Wheel steering/suspension", 0, 0, 0);
            wheelRotors[i] = node(wheelPivots[i], "Wheel rotation", 0, 0, 0);

            double width = i < 2 ? .235 : .275;
            double radius = i < 2 ? .33625 : .3365;

            cylinder(wheelRotors[i], "Tyre", radius, width, black);
            cylinder(wheelRotors[i], "Alloy wheel", .253, width + .004, trim);

            double outside = (i % 2 == 0 ? -1 : 1) * (width * .5 + .012);

            for (int spoke = 0; spoke < 10; spoke++) {
                double a = spoke * Math.PI * 2 / 10;

                Part blade = box(
                        wheelRotors[i], "Performance alloy spoke",
                        outside, .145 * Math.cos(a), .145 * Math.sin(a),
                        .017, .186, .027, silver
                );

                blade.transform.rotation.x = a;
            }

            box(wheelRotors[i], "Wheel centre cap",
                    outside, 0, 0, .024, .09, .09, black);

            box(wheelPivots[i], "Red brake caliper",
                    outside * .90, 0, -.17,
                    .040, .135, .065, red);
        }
    }

    void animate(
            double steeringAngle,
            double[] wheelX,
            double[] wheelZ,
            double[] travel,
            double[] wheelAngles,
            double[] spin
    ) {
        steering.transform.rotation.z = -steeringAngle * 12.0;
        steering.transform.invalidate();

        for (int i = 0; i < 4; i++) {
            Node pivot = wheelPivots[i];

            pivot.transform.position.x = wheelX[i];
            pivot.transform.position.y = .60 - travel[i];
            pivot.transform.position.z = wheelZ[i];
            pivot.transform.rotation.y = wheelAngles[i];
            pivot.transform.invalidate();

            wheelRotors[i].transform.rotation.x = spin[i];
            wheelRotors[i].transform.invalidate();
        }

        smoothWaveHeights();
    }

    void updateDisplay(CarPerception.Snapshot state) {
        // Reset all target heights; points will raise the cells they occupy
        for (int i = 0; i < targetRaise.length; i++) {
            targetRaise[i] = 0;
        }

        int pointCount = state.surfaces.length / 3;
        double xSpan = GRID_X_MAX - GRID_X_MIN;
        double zSpan = GRID_Z_MAX - GRID_Z_MIN;

        for (int p = 0; p < pointCount; p++) {
            int j = p * 3;
            double x = state.surfaces[j];
            double y = state.surfaces[j + 1];
            double z = state.surfaces[j + 2];

            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || y < -0.5 || y > 4.5) {
                continue;
            }

            int col = (int) ((x - GRID_X_MIN) / xSpan * GRID_COLS);
            int row = (int) ((z - GRID_Z_MIN) / zSpan * GRID_ROWS);

            if (col < 0 || col >= GRID_COLS || row < 0 || row >= GRID_ROWS) {
                continue;
            }

            int idx = row * GRID_COLS + col;
            if (idx >= sensorDots.length) {
                continue;
            }

            double raise = Math.max(0, Math.min(MAX_RAISE, y - 0.08));
            if (raise > targetRaise[idx]) {
                targetRaise[idx] = raise;
            }
        }

        int used = 0;

        for (double[] lane : state.lanes) {
            for (int i = 0; i + 3 < lane.length; i += 2) {
                double dx = lane[i + 2] - lane[i];
                double dz = lane[i + 3] - lane[i + 1];
                int steps = Math.min(
                        256, Math.max(1, (int) Math.ceil(Math.hypot(dx, dz)))
                );

                for (int k = 0; k <= steps && used < laneDots.length; k++) {
                    double t = k / (double) steps;
                    double x = lane[i] + dx * t;
                    double z = lane[i + 1] + dz * t;

                    placeProjected(laneDots[used++], x, 0.04, z, .0028);
                }
            }

            if (used >= laneDots.length) {
                break;
            }
        }

        while (used < laneDots.length) {
            hide(laneDots[used++]);
        }

        double direction = state.signedSpeed < -.15 ? -1 : 1;
        double curvature = Math.tan(state.steering) / 2.875;

        for (int i = 0; i < pathDots.length; i++) {
            double distance = (i / 2 + 1) * .85 * direction;
            double angle = distance * curvature;

            double x = Math.abs(curvature) < 1e-7
                    ? 0 : (1 - Math.cos(angle)) / curvature;
            double z = Math.abs(curvature) < 1e-7
                    ? distance : Math.sin(angle) / curvature;

            double side = (i % 2 == 0 ? -1 : 1) * .79;

            placeProjected(
                    pathDots[i],
                    x + side * Math.cos(angle),
                    0.08,
                    z - side * Math.sin(angle),
                    .0026
            );
        }

        drawScreenOutline();

        int speed = Math.max(0, Math.min(999, (int) Math.round(state.speed)));
        int[] values = {speed / 100, speed / 10 % 10, speed % 10};
        int[] masks = {
                0x3F, 0x06, 0x5B, 0x4F, 0x66,
                0x6D, 0x7D, 0x07, 0x7F, 0x6F
        };

        for (int d = 0; d < 3; d++) {
            boolean leading = d == 0 && speed < 100
                    || d == 1 && speed < 10;

            for (int s = 0; s < 7; s++) {
                digits[d][s].setMaterial(
                        !leading && (masks[values[d]] & (1 << s)) != 0
                                ? white : trim
                );
            }
        }
    }

    /**
     * Only heights change. Each marker stays locked to its ground cell
     * and simply rises or falls toward the latest target raise.
     */
    private void smoothWaveHeights() {
        long now = System.nanoTime();
        double dt = (now - lastMarkerSmooth) * 1e-9;
        lastMarkerSmooth = now;

        if (dt <= 0 || dt > 0.25) {
            dt = 0.016;
        }

        double alpha = 1.0 - Math.exp(-6.5 * dt);

        for (int i = 0; i < sensorDots.length; i++) {
            int row = i / GRID_COLS;
            if (row >= GRID_ROWS) {
                hide(sensorDots[i]);
                continue;
            }

            currentRaise[i] += (targetRaise[i] - currentRaise[i]) * alpha;

            double ly = BASE_Y + currentRaise[i];
            double[] projected = projectToScreen(gridX[i], ly, gridZ[i]);

            Part marker = sensorDots[i];
            if (projected == null) {
                hide(marker);
                continue;
            }

            double raise = currentRaise[i];
            marker.setMaterial(raise > 0.55 ? red : raise > 0.18 ? amber : material(80, 140, 160));

            double size = 0.0032 + Math.min(0.004, raise * 0.0018);
            marker.transform.position.x = projected[0];
            marker.transform.position.y = projected[1];
            marker.transform.position.z = 0.681;
            marker.transform.scale.x = size;
            marker.transform.scale.y = size;
            marker.transform.invalidate();
        }
    }

    private void drawScreenOutline() {
        int n = 0;

        double halfW = 1.05;
        double halfL = 2.15;
        double y = 0.55;
        int pointsPerSide = 160;

        // Front
        for (int i = 0; i < pointsPerSide && n < screenOutline.length; i++) {
            double t = i / (double) (pointsPerSide - 1);
            double x = -halfW + t * 2 * halfW;
            screenOutline[n].setMaterial(white);
            placeProjected(screenOutline[n++], x, y, halfL, 0.0009);
        }

        // Right
        for (int i = 0; i < pointsPerSide && n < screenOutline.length; i++) {
            double t = i / (double) (pointsPerSide - 1);
            double z = halfL - t * 2 * halfL;
            screenOutline[n].setMaterial(white);
            placeProjected(screenOutline[n++], halfW, y, z, 0.0009);
        }

        // Rear
        for (int i = 0; i < pointsPerSide && n < screenOutline.length; i++) {
            double t = i / (double) (pointsPerSide - 1);
            double x = halfW - t * 2 * halfW;
            screenOutline[n].setMaterial(white);
            placeProjected(screenOutline[n++], x, y, -halfL, 0.0009);
        }

        // Left
        for (int i = 0; i < pointsPerSide && n < screenOutline.length; i++) {
            double t = i / (double) (pointsPerSide - 1);
            double z = -halfL + t * 2 * halfL;
            screenOutline[n].setMaterial(white);
            placeProjected(screenOutline[n++], -halfW, y, z, 0.0009);
        }

        while (n < screenOutline.length) {
            hide(screenOutline[n++]);
        }
    }

    /**
     * Projects a car-local point onto the centre display.
     * Returns {screenX, screenY} or null if the point is outside the visible area.
     */
    private double[] projectToScreen(double lx, double ly, double lz) {
        double camX = 0;
        double camY = 4.2;
        double camZ = -5.5;

        double dx = lx - camX;
        double dy = ly - camY;
        double dz = lz - camZ;

        double depth = dz;
        if (depth < 0.8) {
            return null;
        }

        double focal = 2.8;
        double px = dx * focal / depth;
        double py = dy * focal / depth;

        double screenX = 0.20 + px * 0.038;
        double screenY = 1.055 + py * 0.022;

        if (screenX < 0.025 || screenX > 0.375 || screenY < 0.945 || screenY > 1.145) {
            return null;
        }

        return new double[]{screenX, screenY};
    }

    private boolean placeProjected(Part marker, double lx, double ly, double lz, double size) {
        double[] projected = projectToScreen(lx, ly, lz);
        if (projected == null) {
            hide(marker);
            return false;
        }

        marker.transform.position.x = projected[0];
        marker.transform.position.y = projected[1];
        marker.transform.position.z = 0.681;
        marker.transform.scale.x = size;
        marker.transform.scale.y = size;
        marker.transform.invalidate();
        return true;
    }

    private static boolean onScreen(double x, double z) {
        return x >= -24 && x <= 24 && z >= -12 && z <= 42;
    }

    private static double screenY(double z) {
        return .942 + (z + 12) / 54.0 * .190;
    }

    private void placeMarker(Part marker, double x, double z, double size) {
        placeProjected(marker, x, 0.3, z, size);
    }

    private static void hide(Part part) {
        part.transform.scale.x = .000001;
        part.transform.scale.y = .000001;
        part.transform.invalidate();
    }

    private Node node(GameObject parent, String name, double x, double y, double z) {
        Node node = new Node();
        node.setName(name);
        node.setSolid(false);
        node.transform.position.x = x;
        node.transform.position.y = y;
        node.transform.position.z = z;
        parent.addChild(node);
        parts.add(node);
        return node;
    }

    private Part box(
            GameObject parent, String name,
            double x, double y, double z,
            double width, double height, double depth,
            Material material
    ) {
        Part part = mesh(parent, name, CUBE, FACES, material);
        part.transform.position.x = x;
        part.transform.position.y = y;
        part.transform.position.z = z;
        part.transform.scale.x = width;
        part.transform.scale.y = height;
        part.transform.scale.z = depth;
        return part;
    }

    private Part mesh(
            GameObject parent,
            String name,
            double[][] vertices,
            int[][] faces,
            Material material
    ) {
        Part part = new Part(vertices, faces, material);
        part.setName(name);
        parent.addChild(part);
        parts.add(part);
        return part;
    }

    private void beam(
            GameObject parent, String name,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double thickness, Material material
    ) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 1e-9) {
            return;
        }

        dx /= length;
        dy /= length;
        dz /= length;

        double ux = dz;
        double uz = -dx;
        double ul = Math.hypot(ux, uz);

        if (ul < 1e-9) {
            ux = 1;
            uz = 0;
        } else {
            ux /= ul;
            uz /= ul;
        }

        double vx = dy * uz;
        double vy = dz * ux - dx * uz;
        double vz = -dy * ux;

        double[][] vertices = new double[8][3];
        double r = thickness * .5;

        for (int end = 0; end < 2; end++) {
            for (int i = 0; i < 4; i++) {
                double a = i == 0 || i == 3 ? -r : r;
                double b = i < 2 ? -r : r;

                vertices[end * 4 + i][0] =
                        (end == 0 ? ax : bx) + ux * a + vx * b;
                vertices[end * 4 + i][1] =
                        (end == 0 ? ay : by) + vy * b;
                vertices[end * 4 + i][2] =
                        (end == 0 ? az : bz) + uz * a + vz * b;
            }
        }

        mesh(parent, name, vertices, FACES, material);
    }

    private void cylinder(
            GameObject parent,
            String name,
            double radius,
            double width,
            Material material
    ) {
        int n = 32;
        double[][] vertices = new double[n * 2 + 2][3];
        int[][] faces = new int[n * 4][3];

        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                vertices[side * n + i][0] = (side == 0 ? -.5 : .5) * width;
                vertices[side * n + i][1] = Math.cos(a) * radius;
                vertices[side * n + i][2] = Math.sin(a) * radius;
            }
        }

        vertices[n * 2][0] = -width * .5;
        vertices[n * 2 + 1][0] = width * .5;

        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            faces[i * 4] = new int[]{i, next, next + n};
            faces[i * 4 + 1] = new int[]{i, next + n, i + n};
            faces[i * 4 + 2] = new int[]{n * 2, next, i};
            faces[i * 4 + 3] = new int[]{n * 2 + 1, i + n, next + n};
        }

        mesh(parent, name, vertices, faces, material);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Material material(int r, int g, int b) {
        return Material.solid(new Color(r, g, b))
                .setAmbient(.42)
                .setDiffuse(.80);
    }

    private static class Node extends GameObject {
        @Override
        public double[][] getVertices() {
            return EMPTY_VERTICES;
        }

        @Override
        public int[][] getFacesArray() {
            return EMPTY_FACES;
        }

        @Override
        public void update(double dt) {
        }
    }

    private static final class Part extends Node {
        private final double[][] vertices;
        private final int[][] faces;

        Part(double[][] vertices, int[][] faces, Material material) {
            this.vertices = vertices;
            this.faces = faces;
            setMaterial(material);
            setSolid(false);
        }

        @Override
        public double[][] getVertices() {
            return vertices;
        }

        @Override
        public int[][] getFacesArray() {
            return faces;
        }
    }
}