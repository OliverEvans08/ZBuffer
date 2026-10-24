
package mods.car;

import engine.render.Material;
import objects.GameObject;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procedural Tesla models.
 *
 * X = left/right
 * Y = up
 * +Z = forward
 *
 * The driver camera is calibrated to each cabin.
 * All windows have completely clear openings.
 * Solid inner wheel housings prevent the tyres
 * from appearing inside the passenger compartment.
 */
final class CarModel {

    // Used by CarMod.positionCamera().
    // Updated when the selected car changes.
    static volatile double EYE_X = -.42;
    static volatile double EYE_Y = 1.27;
    static volatile double EYE_Z = .12;

    private static final double EYE_FORWARD_OF_SEAT = -.10;
    private static final double ROAD_GAP = .012;
    private static final int CIRCLE_STEPS = 64;

    private static final double[][] CUBE = {
            {-.5, -.5, -.5},
            {.5, -.5, -.5},
            {.5, .5, -.5},
            {-.5, .5, -.5},
            {-.5, -.5, .5},
            {.5, -.5, .5},
            {.5, .5, .5},
            {-.5, .5, .5}
    };

    private static final int[][] CUBE_FACES = {
            {0, 2, 1}, {0, 3, 2},
            {4, 5, 6}, {4, 6, 7},
            {0, 4, 7}, {0, 7, 3},
            {1, 2, 6}, {1, 6, 5},
            {3, 7, 6}, {3, 6, 2},
            {0, 1, 5}, {0, 5, 4}
    };

    private static final Material RED = color(170, 29, 40);
    private static final Material SILVER = color(167, 180, 192);
    private static final Material WHITE = color(220, 228, 236);
    private static final Material BLUE = color(41, 60, 90);
    private static final Material STEEL = color(150, 162, 171);

    private static final Material BLACK = color(19, 22, 27);
    private static final Material TRIM = color(48, 54, 62);
    private static final Material CABIN = color(34, 37, 43);
    private static final Material SEAT = color(62, 67, 72);
    private static final Material ALLOY = color(180, 189, 198);
    private static final Material LIGHT = color(233, 245, 252);
    private static final Material TAIL = color(220, 32, 43);

    // No solid GLASS material.
    // Window openings are intentionally left empty.

    private final GameObject root;
    private final Map<TeslaSpec, Body> cache =
            new IdentityHashMap<>();

    private Body active;

    private static final class Body {
        final Node group;
        final Node[] pivots = new Node[4];
        final Node[] rotors = new Node[4];
        final double wheelRadius;

        Node steering;

        Body(Node group, double wheelRadius) {
            this.group = group;
            this.wheelRadius = wheelRadius;
        }
    }

    private static final class Shape {

        final double length;
        final double width;
        final double halfWidth;

        final double front;
        final double rear;
        final double belt;
        final double base;

        final double wheelRadius;
        final double frontAxle;
        final double rearAxle;

        final double frontCabinBase;
        final double rearCabinBase;

        final boolean truck;

        final double roofY;
        final double frontRoofZ;
        final double rearRoofZ;
        final double rearRoofY;

        final double[] z = new double[9];
        final double[] widths;
        final double[] tops;

        Shape(TeslaSpec spec) {

            truck =
                    spec.model == TeslaSpec.Model.CYBERTRUCK;

            boolean suv =
                    spec.model == TeslaSpec.Model.Y
                            || spec.model == TeslaSpec.Model.X;

            length = Math.max(3.4, spec.lengthM);
            width = Math.max(1.5, spec.widthM);
            halfWidth = width * .5;

            front = length * .5;
            rear = -front;

            double wheelbase = Math.min(
                    length - .65,
                    Math.max(2.0, spec.wheelbaseM)
            );

            frontAxle = wheelbase * .5;
            rearAxle = -wheelbase * .5;

            wheelRadius = truck
                    ? .43
                    : suv
                    ? .36
                    : spec.model == TeslaSpec.Model.S
                    ? .355
                    : .335;

            base = clamp(
                    spec.clearanceM + .12,
                    .19,
                    wheelRadius * .96
            );

            belt = truck
                    ? 1.25
                    : spec.model == TeslaSpec.Model.X
                    ? 1.12
                    : spec.model == TeslaSpec.Model.Y
                    ? 1.06
                    : spec.model == TeslaSpec.Model.S
                    ? .98
                    : .94;

            frontCabinBase =
                    front - (truck ? .72 : 1.18);

            rearCabinBase = rear + (
                    truck
                            ? 1.35
                            : spec.model == TeslaSpec.Model.X
                            ? .70
                            : .78
            );

            // Roof geometry is shared with the camera
            // calculations so the driver stays below it.
            roofY = truck
                    ? Math.max(
                    Math.max(1.64, belt + .48),
                    spec.heightM - .055
            )
                    : Math.max(
                    belt + .38,
                    spec.heightM - .055
            );

            frontRoofZ = truck
                    ? front - 2.55
                    : front - (
                    spec.model == TeslaSpec.Model.Y
                            ? 1.89
                            : spec.model == TeslaSpec.Model.X
                            ? 1.72
                            : 1.91
            );

            rearRoofZ = truck
                    ? rear + 1.42
                    : rear + (
                    spec.model == TeslaSpec.Model.X
                            ? 1.56
                            : spec.model == TeslaSpec.Model.Y
                            ? 1.45
                            : 1.50
            );

            rearRoofY = truck ? roofY - .16 : roofY;

            double[] positions = {
                    0, .028, .105, .245, .45,
                    .69, .885, .971, 1
            };

            for (int i = 0; i < z.length; i++) {
                z[i] = rear + length * positions[i];
            }

            if (truck) {
                widths = new double[]{
                        .91, .96, .99,
                        1, 1, 1,
                        .99, .96, .91
                };

                tops = new double[]{
                        1.13, 1.20, 1.26,
                        1.25, 1.25, 1.19,
                        1.12, 1.05, 1.00
                };
            } else {

                double nose =
                        spec.model == TeslaSpec.Model.X
                                ? 1.04
                                : spec.model == TeslaSpec.Model.Y
                                ? 1.00
                                : spec.model == TeslaSpec.Model.S
                                ? .87
                                : .84;

                double deck =
                        spec.model == TeslaSpec.Model.X
                                ? 1.09
                                : spec.model == TeslaSpec.Model.Y
                                ? 1.04
                                : spec.model == TeslaSpec.Model.S
                                ? .90
                                : .85;

                widths = new double[]{
                        .76, .86, .96,
                        .99, 1, .99,
                        .96, .88, .77
                };

                tops = new double[]{
                        deck - .12,
                        deck - .05,
                        deck,
                        belt,
                        belt,
                        belt,
                        nose,
                        nose - .03,
                        nose - .09
                };
            }
        }

        double widthAt(double position) {
            return sample(widths, position) * halfWidth;
        }

        double topAt(double position) {
            return sample(tops, position);
        }

        private double sample(
                double[] values,
                double position
        ) {
            if (position <= z[0]) {
                return values[0];
            }

            for (int i = 0; i < z.length - 1; i++) {
                if (position <= z[i + 1]) {
                    double t =
                            (position - z[i])
                                    / (z[i + 1] - z[i]);

                    t = t * t * (3 - 2 * t);

                    return mix(
                            values[i],
                            values[i + 1],
                            t
                    );
                }
            }

            return values[values.length - 1];
        }

        double archBottom(double zPosition) {

            double axle =
                    Math.abs(zPosition - frontAxle)
                            < Math.abs(zPosition - rearAxle)
                            ? frontAxle
                            : rearAxle;

            double dz = zPosition - axle;
            double r = wheelRadius + .080;

            if (Math.abs(dz) >= r) {
                return base;
            }

            return Math.max(
                    base,
                    wheelRadius + Math.sqrt(
                            Math.max(0, r * r - dz * dz)
                    )
            );
        }
    }

    CarModel(GameObject root) {
        this(root, TeslaSpec.DEFAULT);
    }

    CarModel(GameObject root, TeslaSpec initial) {
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }

        this.root = root;
        setConfiguration(initial);
    }

    void setConfiguration(TeslaSpec selected) {

        if (selected == null) {
            selected = TeslaSpec.DEFAULT;
        }

        Shape cabin = new Shape(selected);

        // Camera is centred over the actual driver seat.
        EYE_X = -cabin.halfWidth * .43;

        // Cabin-relative eye height, not one height
        // shared across every vehicle model.
        EYE_Y = driverEyeY(cabin);

        // Driver sits behind the windshield and
        // slightly forward of the seat backrest.
        EYE_Z = driverEyeZ(cabin);

        Body next = cache.get(selected);

        if (next == null) {
            next = build(selected);
            cache.put(selected, next);
        }

        if (next == active) {
            return;
        }

        if (active != null) {
            setVisibility(active.group, false);
        }

        active = next;
        setVisibility(active.group, true);
    }

    void animate(
            double steeringAngle,
            double[] wheelX,
            double[] wheelZ,
            double[] travel,
            double[] wheelAngles,
            double[] spin
    ) {

        if (active == null) {
            return;
        }

        if (active.steering != null
                && Double.isFinite(steeringAngle)) {

            active.steering.transform.rotation.z =
                    clamp(
                            -steeringAngle * 9.0,
                            -2.35,
                            2.35
                    );

            active.steering.transform.invalidate();
        }

        for (int i = 0; i < 4; i++) {

            Node pivot = active.pivots[i];
            Node rotor = active.rotors[i];

            if (pivot == null || rotor == null) {
                continue;
            }

            if (valid(wheelX, i)) {
                pivot.transform.position.x = wheelX[i];
            }

            if (valid(wheelZ, i)) {
                pivot.transform.position.z = wheelZ[i];
            }

            if (valid(travel, i)) {
                pivot.transform.position.y = Math.max(
                        active.wheelRadius + ROAD_GAP,
                        active.wheelRadius + ROAD_GAP - travel[i]
                );
            }

            if (valid(wheelAngles, i)) {
                pivot.transform.rotation.y = wheelAngles[i];
            }

            pivot.transform.invalidate();

            if (valid(spin, i)) {
                rotor.transform.rotation.x = spin[i];
                rotor.transform.invalidate();
            }
        }
    }

    void updateDisplay(CarPerception.Snapshot ignored) {
        // Sensor overlays are drawn by CarFsdWindow.
    }

    private Body build(TeslaSpec spec) {

        Shape s = new Shape(spec);

        boolean truck =
                spec.model == TeslaSpec.Model.CYBERTRUCK;

        Material paint = truck
                ? STEEL
                : spec.model == TeslaSpec.Model.S
                ? SILVER
                : spec.model == TeslaSpec.Model.Y
                ? WHITE
                : spec.model == TeslaSpec.Model.X
                ? BLUE
                : RED;

        Node group = node(
                root,
                spec.fullName(),
                0, 0, 0
        );

        Body body = new Body(group, s.wheelRadius);

        buildShell(group, s, paint);

        if (truck) {
            buildTruckCabin(group, s);
        } else {
            buildClearCabin(group, spec, s, paint);
        }

        buildLighting(group, spec, s);
        buildExteriorDetails(group, spec, s, paint);
        buildInterior(group, body, s, spec);
        buildWheels(group, body, s, spec);

        // After installing the wheels, seal the inner
        // wheel arches and lower passenger doors.
        buildInnerWheelWells(group, s);

        return body;
    }

    private void buildShell(
            Node group,
            Shape s,
            Material paint
    ) {

        MeshBuilder shell = new MeshBuilder();
        final int steps = 120;

        for (int i = 0; i < steps; i++) {

            double z0 = mix(
                    s.rear, s.front, (double) i / steps
            );

            double z1 = mix(
                    s.rear, s.front, (double) (i + 1) / steps
            );

            double w0 = s.widthAt(z0);
            double w1 = s.widthAt(z1);

            double t0 = s.topAt(z0);
            double t1 = s.topAt(z1);

            double b0 = Math.min(
                    t0 - .075, s.archBottom(z0)
            );

            double b1 = Math.min(
                    t1 - .075, s.archBottom(z1)
            );

            double m0 = mix(b0, t0, .44);
            double m1 = mix(b1, t1, .44);

            double h0 = t0 - .052;
            double h1 = t1 - .052;

            for (int side : new int[]{-1, 1}) {

                shell.panel(
                        side * w0 * .91, b0, z0,
                        side * w1 * .91, b1, z1,
                        side * w1, m1, z1,
                        side * w0, m0, z0
                );

                shell.panel(
                        side * w0, m0, z0,
                        side * w1, m1, z1,
                        side * w1 * .977, h1, z1,
                        side * w0 * .977, h0, z0
                );

                shell.panel(
                        side * w0 * .977, h0, z0,
                        side * w1 * .977, h1, z1,
                        side * w1 * .85, t1, z1,
                        side * w0 * .85, t0, z0
                );
            }

            boolean cabin =
                    (z0 + z1) * .5 > s.rearCabinBase
                            && (z0 + z1) * .5 < s.frontCabinBase;

            if (cabin) {
                for (int side : new int[]{-1, 1}) {

                    shell.panel(
                            side * w0 * .85, t0, z0,
                            side * w1 * .85, t1, z1,
                            side * w1 * .73, t1, z1,
                            side * w0 * .73, t0, z0
                    );
                }
            } else {
                shell.panel(
                        -w0 * .85, t0, z0,
                        w0 * .85, t0, z0,
                        w1 * .85, t1, z1,
                        -w1 * .85, t1, z1
                );
            }
        }

        shell.finish(
                group,
                "Sculpted metal shell with open wheel arches",
                paint
        );

        box(
                group, "Battery protection",
                0, s.base + .016, 0,
                s.width * .69, .065, s.length * .73,
                BLACK
        );

        box(
                group, "Front bumper",
                0, (s.topAt(s.front) + s.base) * .5,
                s.front - .055,
                s.widthAt(s.front) * 1.93,
                s.topAt(s.front) - s.base, .11,
                paint
        );

        box(
                group, "Rear bumper",
                0, (s.topAt(s.rear) + s.base) * .5,
                s.rear + .055,
                s.widthAt(s.rear) * 1.93,
                s.topAt(s.rear) - s.base, .11,
                paint
        );

        box(
                group, "Front black splitter",
                0, s.base + .048, s.front + .004,
                s.widthAt(s.front) * 1.77,
                .073, .08,
                BLACK
        );

        box(
                group, "Rear diffuser",
                0, s.base + .048, s.rear - .004,
                s.widthAt(s.rear) * 1.77,
                .073, .08,
                BLACK
        );

        for (int side : new int[]{-1, 1}) {

            double from =
                    s.rearAxle + s.wheelRadius + .11;

            double to =
                    s.frontAxle - s.wheelRadius - .11;

            for (int j = 0; j < 24; j++) {

                double z0 = mix(from, to, j / 24.0);
                double z1 = mix(
                        from, to, (j + 1) / 24.0
                );

                beam(
                        group, "Integrated sill moulding",
                        side * s.widthAt(z0) * .934,
                        s.base + .048, z0,
                        side * s.widthAt(z1) * .934,
                        s.base + .048, z1,
                        .025, BLACK
                );
            }

            for (double axle : new double[]{
                    s.rearAxle, s.frontAxle
            }) {

                double radius = s.wheelRadius + .082;
                double lastZ = axle + radius;
                double lastY = s.wheelRadius;

                for (int j = 1; j <= 24; j++) {

                    double angle = Math.PI * j / 24;
                    double z =
                            axle + radius * Math.cos(angle);

                    double y =
                            s.wheelRadius
                                    + radius * Math.sin(angle);

                    double x =
                            side * (
                                    s.widthAt(axle) * .970 + .007
                            );

                    beam(
                            group, "Wheel-arch edge",
                            x, lastY, lastZ,
                            x, y, z,
                            .018, TRIM
                    );

                    lastY = y;
                    lastZ = z;
                }
            }
        }
    }

    /**
     * Prevent tyres from being rendered through the
     * cabin's interior, footwell and lower door panels.
     *
     * These are opaque body panels below the windows.
     * The side and rear window openings remain clear.
     */
    private void buildInnerWheelWells(Node group, Shape s) {

        double floor = .24;

        double top = Math.max(
                s.belt - .025,
                s.wheelRadius * 2 + .13
        );

        double cabinZ =
                (s.rearCabinBase + s.frontCabinBase) * .5;

        double cabinDepth =
                s.frontCabinBase - s.rearCabinBase;

        for (int side : new int[]{-1, 1}) {

            // Opaque inner metal door below the window.
            box(
                    group, "Inside lower door and kick panel",
                    side * s.halfWidth * .77,
                    (floor + s.belt - .015) * .5,
                    cabinZ,
                    .075,
                    s.belt - .015 - floor,
                    cabinDepth + .10,
                    CABIN
            );

            for (double axle : new double[]{
                    s.frontAxle, s.rearAxle
            }) {

                double tyreWidth =
                        s.truck ? .32 : .28;

                double wheelX =
                        s.widthAt(axle) - tyreWidth * .47;

                double innerX = side * (
                        wheelX - tyreWidth * .58 - .06
                );

                double span = s.wheelRadius + .17;

                // Vertical housing hides the tyre sidewall.
                box(
                        group, "Closed inner wheel housing",
                        innerX,
                        (floor + top) * .5,
                        axle,
                        .11,
                        top - floor,
                        2 * span,
                        CABIN
                );

                // Upper housing hides the rotating tyre
                // when looking down through the cabin.
                box(
                        group, "Inner wheel housing upper cover",
                        side * (
                                wheelX - tyreWidth * .45
                        ),
                        top - .025,
                        axle,
                        tyreWidth + .17,
                        .065,
                        2 * span,
                        CABIN
                );
            }
        }

        // Separates the passenger footwell from
        // the front wheels and engine compartment.
        box(
                group, "Front cabin firewall",
                0,
                (floor + s.belt) * .5,
                s.frontCabinBase - .055,
                s.width * .75,
                s.belt - floor,
                .12,
                CABIN
        );
    }

    /**
     * Model S, 3, X and Y cabin.
     *
     * Windshield, side windows and rear window:
     * NO dark solid polygons are created.
     *
     * Only the pillars, seals, frame and roof
     * are rendered, allowing visibility outside.
     */
    private void buildClearCabin(
            Node group,
            TeslaSpec spec,
            Shape s,
            Material paint
    ) {

        boolean modelX = spec.model == TeslaSpec.Model.X;
        boolean modelY = spec.model == TeslaSpec.Model.Y;

        double roofY = s.roofY;
        double frontRoofZ = s.frontRoofZ;
        double rearRoofZ = s.rearRoofZ;

        double frontBaseZ = s.frontCabinBase;
        double rearBaseZ = s.rearCabinBase;

        double frontY = s.topAt(frontBaseZ) + .014;
        double rearY = s.topAt(rearBaseZ) + .014;

        double frontX = s.widthAt(frontBaseZ) * .85;
        double rearX = s.widthAt(rearBaseZ) * .85;
        double roofX = s.halfWidth * .725;

        beam(
                group, "Windshield bottom seal",
                -frontX, frontY, frontBaseZ,
                frontX, frontY, frontBaseZ,
                .034, BLACK
        );

        beam(
                group, "Windshield header",
                -roofX, roofY, frontRoofZ,
                roofX, roofY, frontRoofZ,
                .046, BLACK
        );

        beam(
                group, "Rear glass bottom seal",
                -rearX, rearY, rearBaseZ,
                rearX, rearY, rearBaseZ,
                .036, BLACK
        );

        beam(
                group, "Rear glass header",
                -roofX, roofY, rearRoofZ,
                roofX, roofY, rearRoofZ,
                .047, BLACK
        );

        for (int side : new int[]{-1, 1}) {

            beam(
                    group, "Integrated A-pillar",
                    side * frontX, frontY, frontBaseZ,
                    side * roofX, roofY, frontRoofZ,
                    .074, paint
            );

            beam(
                    group, "Integrated C-pillar",
                    side * rearX, rearY, rearBaseZ,
                    side * roofX, roofY, rearRoofZ,
                    .092, paint
            );

            double previousZ = rearRoofZ;
            double previousY = roofY;

            for (int i = 1; i <= 20; i++) {

                double t = i / 20.0;
                double z = mix(
                        rearRoofZ, frontRoofZ, t
                );

                double y =
                        roofY + .018 * Math.sin(Math.PI * t);

                beam(
                        group, "Curved panoramic roof rail",
                        side * roofX, previousY, previousZ,
                        side * roofX, y, z,
                        .048, BLACK
                );

                previousZ = z;
                previousY = y;
            }

            beam(
                    group, "Side window sill",
                    side * rearX, rearY, rearBaseZ,
                    side * frontX, frontY, frontBaseZ,
                    .032, TRIM
            );

            double t = .52;

            double lowerZ = mix(
                    rearBaseZ, frontBaseZ, t
            );

            double upperZ = mix(
                    rearRoofZ, frontRoofZ, t
            );

            double lowerY = mix(rearY, frontY, t);
            double lowerX = mix(rearX, frontX, t);

            beam(
                    group,
                    "B-pillar attached to roof and door",
                    side * lowerX, lowerY, lowerZ,
                    side * roofX, roofY + .018, upperZ,
                    .047, BLACK
            );

            // Intentionally no solid side glass.
            // The outside world remains visible.
        }

        if (modelX || modelY) {

            double crossZ = mix(
                    rearRoofZ, frontRoofZ, .47
            );

            beam(
                    group, "Panoramic roof brace",
                    -roofX, roofY + .018, crossZ,
                    roofX, roofY + .018, crossZ,
                    .037, BLACK
            );
        }

        // No windshield or rear glass mesh.

        box(
                group, "Front roof header",
                0, roofY - .016, frontRoofZ,
                roofX * 2, .05, .07,
                paint
        );

        box(
                group, "Rear roof header",
                0, roofY - .016, rearRoofZ,
                roofX * 2, .05, .07,
                paint
        );
    }

    /**
     * Cybertruck cabin.
     *
     * The actual roof is metal, but every window
     * opening is fully clear.
     */
    private void buildTruckCabin(
            Node group,
            Shape s
    ) {

        double roofY = s.roofY;
        double peakZ = s.frontRoofZ;

        double rearRoofZ = s.rearRoofZ;
        double rearRoofY = s.rearRoofY;

        double frontBaseZ = s.frontCabinBase;
        double rearBaseZ = rearRoofZ - .22;

        double frontY =
                s.topAt(frontBaseZ) + .016;

        double rearY =
                s.topAt(rearBaseZ) + .016;

        double frontX =
                s.widthAt(frontBaseZ) * .85;

        double rearX =
                s.widthAt(rearBaseZ) * .85;

        double roofX = s.halfWidth * .775;

        quad(
                group, "Folded stainless roof",
                -roofX, rearRoofY, rearRoofZ,
                roofX, rearRoofY, rearRoofZ,
                roofX, roofY, peakZ,
                -roofX, roofY, peakZ,
                STEEL
        );

        quad(
                group, "Attached vault tonneau",

                -s.widthAt(s.rear + .15) * .83,
                s.topAt(s.rear + .15) + .014,
                s.rear + .15,

                s.widthAt(s.rear + .15) * .83,
                s.topAt(s.rear + .15) + .014,
                s.rear + .15,

                s.widthAt(rearBaseZ) * .83,
                s.topAt(rearBaseZ) + .014,
                rearBaseZ,

                -s.widthAt(rearBaseZ) * .83,
                s.topAt(rearBaseZ) + .014,
                rearBaseZ,

                STEEL
        );

        beam(
                group, "Truck windshield lower seal",
                -frontX, frontY, frontBaseZ,
                frontX, frontY, frontBaseZ,
                .038, BLACK
        );

        beam(
                group, "Truck windshield upper seal",
                -roofX, roofY, peakZ,
                roofX, roofY, peakZ,
                .044, BLACK
        );

        for (int side : new int[]{-1, 1}) {

            beam(
                    group, "Truck A-pillar",
                    side * frontX, frontY, frontBaseZ,
                    side * roofX, roofY, peakZ,
                    .077, STEEL
            );

            beam(
                    group, "Truck sloping roof rail",
                    side * roofX, roofY, peakZ,
                    side * roofX, rearRoofY, rearRoofZ,
                    .069, STEEL
            );

            beam(
                    group, "Truck C-pillar",
                    side * roofX, rearRoofY, rearRoofZ,
                    side * rearX, rearY, rearBaseZ,
                    .085, STEEL
            );

            beam(
                    group, "Truck window sill",
                    side * frontX, frontY, frontBaseZ,
                    side * rearX, rearY, rearBaseZ,
                    .040, BLACK
            );

            double middleZ = mix(
                    rearBaseZ, frontBaseZ, .52
            );

            double middleY = mix(rearY, frontY, .52);
            double middleX = mix(rearX, frontX, .52);

            beam(
                    group, "Truck B-pillar",
                    side * middleX, middleY, middleZ,
                    side * roofX,
                    mix(rearRoofY, roofY, .52),
                    mix(rearRoofZ, peakZ, .52),
                    .055, BLACK
            );

            // The truck side window remains clear.
        }

        beam(
                group, "Truck rear roof header",
                -roofX, rearRoofY, rearRoofZ,
                roofX, rearRoofY, rearRoofZ,
                .055, STEEL
        );

        // The rear window also remains clear.
    }

    private void buildLighting(
            Node group,
            TeslaSpec spec,
            Shape s
    ) {

        boolean bar =
                spec.model == TeslaSpec.Model.CYBERTRUCK
                        || spec.model == TeslaSpec.Model.Y;

        double frontY = s.topAt(s.front) - .085;
        double rearY = s.topAt(s.rear) - .085;

        double nose = s.widthAt(s.front);
        double tail = s.widthAt(s.rear);

        if (bar) {

            box(
                    group, "Flush front LED bar",
                    0, frontY, s.front + .008,
                    nose * 1.67, .036, .024,
                    LIGHT
            );

            box(
                    group, "Flush rear LED bar",
                    0, rearY, s.rear - .008,
                    tail * 1.67, .045, .024,
                    TAIL
            );

        } else {

            for (int side : new int[]{-1, 1}) {

                box(
                        group, "Recessed headlamp",
                        side * nose * .48,
                        frontY,
                        s.front + .008,
                        nose * .57, .066, .024,
                        LIGHT
                );

                box(
                        group, "Recessed tail lamp",
                        side * tail * .48,
                        rearY,
                        s.rear - .008,
                        tail * .57, .071, .024,
                        TAIL
                );
            }
        }
    }

    private void buildExteriorDetails(
            Node group,
            TeslaSpec spec,
            Shape s,
            Material paint
    ) {

        boolean truck =
                spec.model == TeslaSpec.Model.CYBERTRUCK;

        double frontDoorZ =
                s.frontCabinBase - .62;

        double rearDoorZ =
                s.rearCabinBase + .58;

        double seamZ = mix(
                rearDoorZ, frontDoorZ, .50
        );

        for (int side : new int[]{-1, 1}) {

            for (double z : new double[]{
                    frontDoorZ, rearDoorZ
            }) {

                box(
                        group, "Recessed flush door handle",
                        side * (
                                s.widthAt(z) * .980 + .012
                        ),
                        s.belt - .13,
                        z,
                        .024, .026, .16,
                        truck ? STEEL : TRIM
                );
            }

            beam(
                    group, "Door shut line",
                    side * s.widthAt(seamZ) * .978,
                    s.base + .09, seamZ,
                    side * s.widthAt(seamZ) * .978,
                    s.belt - .048, seamZ,
                    .009, TRIM
            );

            double mirrorZ =
                    s.frontCabinBase - .045;

            double x = s.widthAt(mirrorZ);

            beam(
                    group, "Mirror mounting arm",
                    side * x * .96,
                    s.belt + .035, mirrorZ,
                    side * (x + .165),
                    s.belt + .085, mirrorZ,
                    .038, BLACK
            );

            box(
                    group, "Aerodynamic side mirror",
                    side * (x + .195),
                    s.belt + .105,
                    mirrorZ,
                    .21, .105, .20,
                    truck ? BLACK : paint
            );

            box(
                    group, "Reflective mirror face",
                    side * (x + .195),
                    s.belt + .106,
                    mirrorZ - .101,
                    .175, .075, .008,
                    ALLOY
            );

            double cameraZ = mirrorZ - .30;

            box(
                    group, "Fender camera (flush)",
                    side * (
                            s.widthAt(cameraZ) * .978 + .012
                    ),
                    s.belt - .15,
                    cameraZ,
                    .025, .036, .068,
                    BLACK
            );
        }

        if (spec.type == TeslaSpec.Type.PERFORMANCE
                && !truck) {

            double z = s.rear + .17;

            box(
                    group, "Integrated deck spoiler",
                    0, s.topAt(z) + .025, z,
                    s.widthAt(z) * 1.58,
                    .045, .15,
                    BLACK
            );
        }

        if (truck) {

            box(
                    group, "Truck integrated front bumper",
                    0, s.base + .14, s.front + .011,
                    s.widthAt(s.front) * 1.88,
                    .18, .080,
                    BLACK
            );

            box(
                    group, "Truck rear step bumper",
                    0, s.base + .09, s.rear - .035,
                    s.widthAt(s.rear) * 1.84,
                    .13, .105,
                    BLACK
            );
        }
    }

    private void buildInterior(
            Node group,
            Body body,
            Shape s,
            TeslaSpec spec
    ) {

        boolean truck =
                spec.model == TeslaSpec.Model.CYBERTRUCK;

        double cabinCenter =
                (s.rearCabinBase + s.frontCabinBase) * .5;

        double cabinDepth =
                s.frontCabinBase - s.rearCabinBase;

        // Dashboard position is also used to calculate
        // the driver seat position.
        double dashboardZ = dashboardZ(s);

        box(
                group, "Interior floor pan",
                0, .33, cabinCenter,
                s.width * .73,
                .09,
                cabinDepth + .08,
                CABIN
        );

        double dashY =
                truck ? s.belt - .08 : s.belt - .115;

        box(
                group, "Full-width dashboard",
                0, dashY, dashboardZ,
                s.width * .76,
                truck ? .22 : .19,
                .34,
                CABIN
        );

        beam(
                group, "Dash upper padded edge",
                -s.width * .36,
                s.belt - .014,
                dashboardZ - .16,
                s.width * .36,
                s.belt - .014,
                dashboardZ - .16,
                .043, TRIM
        );

        double frontSeatZ = frontSeatZ(s);

        double seatCushionY = truck ? .72 : .56;
        double seatBackY = truck ? 1.08 : .92;
        double seatHeadY = truck ? 1.38 : 1.23;

        double seatBackZOff = truck ? -.22 : -.24;
        double seatHeadZOff = truck ? -.27 : -.29;

        for (int side : new int[]{-1, 1}) {

            double x = side * s.halfWidth * .43;

            box(
                    group, "Front seat rail",
                    x, seatCushionY - .155, frontSeatZ,
                    .32, .085, .60,
                    TRIM
            );

            box(
                    group, "Contoured front cushion",
                    x, seatCushionY, frontSeatZ,
                    .45, .17, .57,
                    SEAT
            );

            box(
                    group, "Contoured front backrest",
                    x, seatBackY,
                    frontSeatZ + seatBackZOff,
                    .44,
                    truck ? .68 : .60,
                    .16,
                    SEAT
            );

            box(
                    group, "Padded front headrest",
                    x, seatHeadY,
                    frontSeatZ + seatHeadZOff,
                    .29, .20, .15,
                    SEAT
            );
        }

        double backSeatZ =
                s.rearCabinBase + (truck ? .55 : .46);

        double rearCushionY =
                truck ? .68 : .54;

        double rearBackY =
                truck ? .98 : .83;

        box(
                group, "Rear bench supports",
                0, rearCushionY - .13, backSeatZ,
                s.width * .62, .11, .42,
                TRIM
        );

        box(
                group, "Rear bench cushion",
                0, rearCushionY, backSeatZ,
                s.width * .67, .16, .49,
                SEAT
        );

        box(
                group, "Rear bench backrest",
                0, rearBackY, backSeatZ - .19,
                s.width * .67,
                truck ? .58 : .53,
                .15,
                SEAT
        );

        for (int side : new int[]{-1, 0, 1}) {

            box(
                    group, "Attached rear headrest",
                    side * s.width * .21,
                    rearBackY + .29,
                    backSeatZ - .23,
                    .23, .16, .15,
                    TRIM
            );
        }

        double consoleZ = mix(
                frontSeatZ, dashboardZ, .42
        );

        box(
                group, "Connected center console",
                0, truck ? .58 : .49, consoleZ,
                .24, .18,
                Math.max(.44, dashboardZ - frontSeatZ),
                CABIN
        );

        box(
                group, "Armrest",
                0, truck ? .72 : .62,
                frontSeatZ - .14,
                .25, .12, .36,
                TRIM
        );

        // Steering wheel position follows the dashboard
        // and therefore also adjusts for the Cybertruck.
        double steeringX = -s.halfWidth * .43;
        double steeringY = truck ? 1.12 : 1.005;
        double steeringZ = dashboardZ - .28;

        beam(
                group, "Steering column",
                steeringX,
                steeringY - .015,
                steeringZ + .025,
                steeringX,
                dashY - .04,
                dashboardZ + .08,
                .075,
                TRIM
        );

        Node steering = node(
                group,
                "Rotating steering wheel",
                steeringX,
                steeringY,
                steeringZ
        );

        body.steering = steering;

        torus(
                steering,
                "Smooth circular steering rim",
                .168, .020,
                CIRCLE_STEPS, 12,
                BLACK
        );

        for (int side : new int[]{-1, 1}) {

            beam(
                    steering, "Wheel spoke",
                    side * .042, -.022, -.009,
                    side * .152, -.050, 0,
                    .030, TRIM
            );
        }

        beam(
                steering, "Lower wheel spoke",
                0, -.042, -.008,
                0, -.155, 0,
                .026, TRIM
        );

        box(
                steering, "Steering wheel horn pad",
                0, 0, -.013,
                .110, .082, .044,
                CABIN
        );

        double screenZ = dashboardZ - .20;

        box(
                group, "Touchscreen anchored to dash",
                .11, s.belt + .05, screenZ + .032,
                .43, .285, .068,
                BLACK
        );

        box(
                group, "Touchscreen display",
                .11, s.belt + .05, screenZ - .004,
                .401, .255, .007,
                TRIM
        );

        beam(
                group, "Touchscreen support",
                .11, dashY - .05, screenZ + .04,
                .11, s.belt + .03, screenZ + .04,
                .057, CABIN
        );
    }

    private void buildWheels(
            Node group,
            Body body,
            Shape s,
            TeslaSpec spec
    ) {

        double tyreWidth =
                spec.model == TeslaSpec.Model.CYBERTRUCK
                        ? .32
                        : spec.type == TeslaSpec.Type.PERFORMANCE
                        ? .28
                        : .245;

        for (int i = 0; i < 4; i++) {

            int side = i % 2 == 0 ? -1 : 1;
            double z = i < 2 ? s.frontAxle : s.rearAxle;

            double x = side * (
                    s.widthAt(z) - tyreWidth * .47
            );

            Node pivot = node(
                    group,
                    "Suspension and steering pivot " + i,
                    x,
                    s.wheelRadius + ROAD_GAP,
                    z
            );

            Node rotor = node(
                    pivot,
                    "Axial wheel rotor " + i,
                    0, 0, 0
            );

            body.pivots[i] = pivot;
            body.rotors[i] = rotor;

            double r = s.wheelRadius;

            double[][] tireSection = {
                    {-.48 * tyreWidth, .70 * r},
                    {-.52 * tyreWidth, .82 * r},
                    {-.50 * tyreWidth, .92 * r},
                    {-.34 * tyreWidth, .985 * r},
                    {-.24 * tyreWidth, r},
                    {.24 * tyreWidth, r},
                    {.34 * tyreWidth, .985 * r},
                    {.50 * tyreWidth, .92 * r},
                    {.52 * tyreWidth, .82 * r},
                    {.48 * tyreWidth, .70 * r},
                    {.30 * tyreWidth, .69 * r},
                    {-.30 * tyreWidth, .69 * r}
            };

            revolved(
                    rotor,
                    "Rounded rubber tyre",
                    tireSection,
                    BLACK
            );

            cylinder(
                    rotor,
                    "Recessed rim barrel",
                    r * .695,
                    tyreWidth * .91,
                    TRIM
            );

            double outsideX =
                    side * (tyreWidth * .52 + .004);

            annulus(
                    rotor,
                    "Machined outer rim lip",
                    outsideX,
                    r * .57,
                    r * .695,
                    ALLOY
            );

            int spokes =
                    spec.type == TeslaSpec.Type.PERFORMANCE
                            ? 10
                            : 8;

            for (int j = 0; j < spokes; j++) {

                double a = 2 * Math.PI * j / spokes;

                beam(
                        rotor,
                        "Integrated alloy spoke",
                        outsideX,
                        Math.cos(a) * r * .12,
                        Math.sin(a) * r * .12,
                        outsideX,
                        Math.cos(a) * r * .615,
                        Math.sin(a) * r * .615,
                        .034,
                        ALLOY
                );
            }

            cylinder(
                    rotor,
                    "Center hub and cap",
                    r * .135,
                    tyreWidth + .025,
                    ALLOY
            );
        }
    }

    /**
     * Cabin calibration.
     *
     * Non-truck models retain the original dashboard
     * placement. Cybertruck uses the actual windshield
     * roof edge to avoid putting the driver in front of it.
     */
    private static double dashboardZ(Shape s) {
        return Math.min(
                s.frontCabinBase - .26,
                s.frontRoofZ + (s.truck ? .48 : .80)
        );
    }

    private static double frontSeatZ(Shape s) {
        return clamp(
                dashboardZ(s) - .78,
                s.rearCabinBase + .70,
                Math.min(
                        s.frontCabinBase - .40,
                        s.frontRoofZ - .26
                )
        );
    }

    private static double driverEyeZ(Shape s) {
        return frontSeatZ(s) + EYE_FORWARD_OF_SEAT;
    }

    private static double driverEyeY(Shape s) {

        double minimum = s.truck ? 1.48 : 1.26;

        // Eye level adjusts to beltline and roof height
        // while preserving headroom in low-roof vehicles.
        return clamp(
                s.belt + .62 * (s.roofY - s.belt),
                minimum,
                Math.max(minimum, s.roofY - .105)
        );
    }

    private static boolean valid(
            double[] a,
            int index
    ) {
        return a != null
                && index < a.length
                && Double.isFinite(a[index]);
    }

    private static double mix(
            double a,
            double b,
            double t
    ) {
        return a + (b - a) * t;
    }

    private static double clamp(
            double value,
            double lo,
            double hi
    ) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static void setVisibility(
            Node node,
            boolean visible
    ) {

        double scale = visible ? 1 : .000001;

        node.transform.scale.x = scale;
        node.transform.scale.y = scale;
        node.transform.scale.z = scale;

        node.transform.invalidate();
    }

    private static Node node(
            GameObject parent,
            String name,
            double x,
            double y,
            double z
    ) {

        Node result = new Node();

        result.setName(name);
        result.setSolid(false);

        result.transform.position.x = x;
        result.transform.position.y = y;
        result.transform.position.z = z;

        parent.addChild(result);
        result.transform.invalidate();

        return result;
    }

    private static Part box(
            GameObject parent,
            String name,
            double x,
            double y,
            double z,
            double width,
            double height,
            double depth,
            Material material
    ) {

        Part part = new Part(
                CUBE,
                CUBE_FACES,
                material
        );

        part.setName(name);
        parent.addChild(part);

        part.transform.position.x = x;
        part.transform.position.y = y;
        part.transform.position.z = z;

        part.transform.scale.x = width;
        part.transform.scale.y = height;
        part.transform.scale.z = depth;

        part.transform.invalidate();

        return part;
    }

    private static void quad(
            GameObject parent,
            String name,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double cx, double cy, double cz,
            double dx, double dy, double dz,
            Material material
    ) {

        MeshBuilder mesh = new MeshBuilder();

        mesh.panel(
                ax, ay, az,
                bx, by, bz,
                cx, cy, cz,
                dx, dy, dz
        );

        mesh.finish(parent, name, material);
    }

    private static void beam(
            GameObject parent,
            String name,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double thickness,
            Material material
    ) {

        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;

        double length = Math.sqrt(
                dx * dx + dy * dy + dz * dz
        );

        if (length < 1e-9) {
            return;
        }

        double tx = dx / length;
        double ty = dy / length;
        double tz = dz / length;

        double ux;
        double uy;
        double uz;

        if (Math.abs(ty) < .9) {

            ux = -tz;
            uy = 0;
            uz = tx;

        } else {

            ux = 0;
            uy = tz;
            uz = -ty;
        }

        double size = Math.sqrt(
                ux * ux + uy * uy + uz * uz
        );

        ux /= size;
        uy /= size;
        uz /= size;

        double vx = ty * uz - tz * uy;
        double vy = tz * ux - tx * uz;
        double vz = tx * uy - ty * ux;

        final int n = 10;

        double[][] vertices = new double[n * 2 + 2][3];
        int[][] faces = new int[n * 4][3];

        for (int end = 0; end < 2; end++) {

            double cx = end == 0 ? ax : bx;
            double cy = end == 0 ? ay : by;
            double cz = end == 0 ? az : bz;

            for (int j = 0; j < n; j++) {

                double a = 2 * Math.PI * j / n;

                double cs =
                        Math.cos(a) * thickness * .5;

                double sn =
                        Math.sin(a) * thickness * .5;

                vertices[end * n + j] = new double[]{
                        cx + ux * cs + vx * sn,
                        cy + uy * cs + vy * sn,
                        cz + uz * cs + vz * sn
                };
            }

            vertices[n * 2 + end] = new double[]{
                    cx, cy, cz
            };
        }

        for (int j = 0; j < n; j++) {

            int next = (j + 1) % n;

            faces[j * 4] = new int[]{
                    j, next, j + n
            };

            faces[j * 4 + 1] = new int[]{
                    next, next + n, j + n
            };

            faces[j * 4 + 2] = new int[]{
                    n * 2, next, j
            };

            faces[j * 4 + 3] = new int[]{
                    n * 2 + 1, j + n, next + n
            };
        }

        Part part = new Part(
                vertices,
                faces,
                material
        );

        part.setName(name);
        parent.addChild(part);
    }

    private static void torus(
            GameObject parent,
            String name,
            double radius,
            double tube,
            int major,
            int minor,
            Material material
    ) {

        double[][] vertices =
                new double[major * minor][3];

        int[][] faces =
                new int[major * minor * 2][3];

        for (int i = 0; i < major; i++) {

            double a = 2 * Math.PI * i / major;

            for (int j = 0; j < minor; j++) {

                double b = 2 * Math.PI * j / minor;
                double outer = radius + tube * Math.cos(b);

                vertices[i * minor + j] = new double[]{
                        outer * Math.cos(a),
                        outer * Math.sin(a),
                        tube * Math.sin(b)
                };
            }
        }

        for (int i = 0; i < major; i++) {

            int nextI = (i + 1) % major;

            for (int j = 0; j < minor; j++) {

                int nextJ = (j + 1) % minor;

                int a = i * minor + j;
                int b = nextI * minor + j;
                int c = nextI * minor + nextJ;
                int d = i * minor + nextJ;

                int f = (i * minor + j) * 2;

                faces[f] = new int[]{
                        a, b, c
                };

                faces[f + 1] = new int[]{
                        a, c, d
                };
            }
        }

        Part part = new Part(
                vertices,
                faces,
                material
        );

        part.setName(name);
        parent.addChild(part);
    }

    private static void revolved(
            GameObject parent,
            String name,
            double[][] section,
            Material material
    ) {

        int bands = section.length;
        int n = CIRCLE_STEPS;

        double[][] vertices = new double[bands * n][3];
        int[][] faces = new int[bands * n * 2][3];

        for (int b = 0; b < bands; b++) {

            for (int i = 0; i < n; i++) {

                double a = i * 2 * Math.PI / n;

                vertices[b * n + i] = new double[]{
                        section[b][0],
                        section[b][1] * Math.cos(a),
                        section[b][1] * Math.sin(a)
                };
            }
        }

        for (int b = 0; b < bands; b++) {

            int nextB = (b + 1) % bands;

            for (int i = 0; i < n; i++) {

                int next = (i + 1) % n;

                int a = b * n + i;
                int c = nextB * n + i;
                int d = nextB * n + next;
                int e = b * n + next;

                int f = (b * n + i) * 2;

                faces[f] = new int[]{
                        a, e, d
                };

                faces[f + 1] = new int[]{
                        a, d, c
                };
            }
        }

        Part part = new Part(
                vertices,
                faces,
                material
        );

        part.setName(name);
        parent.addChild(part);
    }

    private static void annulus(
            GameObject parent,
            String name,
            double x,
            double inner,
            double outer,
            Material material
    ) {

        MeshBuilder mesh = new MeshBuilder();

        for (int i = 0; i < CIRCLE_STEPS; i++) {

            double a =
                    2 * Math.PI * i / CIRCLE_STEPS;

            double b =
                    2 * Math.PI * (i + 1) / CIRCLE_STEPS;

            mesh.panel(
                    x,
                    inner * Math.cos(a),
                    inner * Math.sin(a),

                    x,
                    outer * Math.cos(a),
                    outer * Math.sin(a),

                    x,
                    outer * Math.cos(b),
                    outer * Math.sin(b),

                    x,
                    inner * Math.cos(b),
                    inner * Math.sin(b)
            );
        }

        mesh.finish(parent, name, material);
    }

    private static void cylinder(
            GameObject parent,
            String name,
            double radius,
            double width,
            Material material
    ) {

        int n = CIRCLE_STEPS;

        double[][] vertices = new double[2 * n + 2][3];
        int[][] faces = new int[4 * n][3];

        for (int side = 0; side < 2; side++) {

            for (int i = 0; i < n; i++) {

                double a = 2 * Math.PI * i / n;

                vertices[side * n + i] = new double[]{
                        (side == 0 ? -.5 : .5) * width,
                        radius * Math.cos(a),
                        radius * Math.sin(a)
                };
            }
        }

        vertices[2 * n] = new double[]{
                -width * .5, 0, 0
        };

        vertices[2 * n + 1] = new double[]{
                width * .5, 0, 0
        };

        for (int i = 0; i < n; i++) {

            int next = (i + 1) % n;

            faces[i * 4] = new int[]{
                    i, next, next + n
            };

            faces[i * 4 + 1] = new int[]{
                    i, next + n, i + n
            };

            faces[i * 4 + 2] = new int[]{
                    2 * n, next, i
            };

            faces[i * 4 + 3] = new int[]{
                    2 * n + 1, i + n, next + n
            };
        }

        Part part = new Part(
                vertices,
                faces,
                material
        );

        part.setName(name);
        parent.addChild(part);
    }

    private static final class MeshBuilder {

        private final List<double[]> vertices =
                new ArrayList<>();

        private final List<int[]> faces =
                new ArrayList<>();

        void panel(
                double ax, double ay, double az,
                double bx, double by, double bz,
                double cx, double cy, double cz,
                double dx, double dy, double dz
        ) {

            double ux = bx - ax;
            double uy = by - ay;
            double uz = bz - az;

            double vx = cx - ax;
            double vy = cy - ay;
            double vz = cz - az;

            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;

            double length = Math.sqrt(
                    nx * nx + ny * ny + nz * nz
            );

            if (length < 1e-10) {
                return;
            }

            nx *= .002 / length;
            ny *= .002 / length;
            nz *= .002 / length;

            double[][] points = {
                    {ax, ay, az},
                    {bx, by, bz},
                    {cx, cy, cz},
                    {dx, dy, dz}
            };

            int start = vertices.size();

            for (int side = 0; side < 2; side++) {

                double sign = side == 0 ? 1 : -1;

                for (double[] v : points) {

                    vertices.add(new double[]{
                            v[0] + nx * sign,
                            v[1] + ny * sign,
                            v[2] + nz * sign
                    });
                }
            }

            faces.add(new int[]{
                    start,
                    start + 1,
                    start + 2
            });

            faces.add(new int[]{
                    start,
                    start + 2,
                    start + 3
            });

            faces.add(new int[]{
                    start + 4,
                    start + 6,
                    start + 5
            });

            faces.add(new int[]{
                    start + 4,
                    start + 7,
                    start + 6
            });
        }

        void finish(
                GameObject parent,
                String name,
                Material material
        ) {

            if (faces.isEmpty()) {
                return;
            }

            Part part = new Part(
                    vertices.toArray(new double[0][]),
                    faces.toArray(new int[0][]),
                    material
            );

            part.setName(name);
            parent.addChild(part);
        }
    }

    private static Material color(
            int r,
            int g,
            int b
    ) {

        return Material.solid(
                        new Color(r, g, b)
                )
                .setAmbient(.42)
                .setDiffuse(.80);
    }

    private static class Node extends GameObject {

        @Override
        public double[][] getVertices() {
            return new double[0][3];
        }

        @Override
        public int[][] getFacesArray() {
            return new int[0][3];
        }

        @Override
        public void update(double dt) {
        }
    }

    private static final class Part extends Node {

        private final double[][] vertices;
        private final int[][] faces;

        Part(
                double[][] vertices,
                int[][] faces,
                Material material
        ) {

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
