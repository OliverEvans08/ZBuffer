
package mods.car;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tesla production configurations, primarily 2026 US-market specifications.
 *
 * Range: US EPA or Tesla's US estimate, in miles (not Australian WLTP).
 * Acceleration: 0-60 mph, including Tesla's stated rollout conventions.
 * Dimensions: metres. widthM is BODY WIDTH EXCLUDING MIRRORS so that
 * collision, handling and rendering do not mistake folded mirrors for body.
 * Clearance is unladen/normal or medium suspension height as applicable.
 * Wheels describe one listed factory fitment; other factory wheels can alter
 * range, ride, mass and top-speed availability.
 *
 * The physics engine uses published values wherever available. Its motor
 * curves, tyre models, suspension and aerodynamic estimates are NOT Tesla
 * manufacturer data; neither is its experimental stereo driving controller.
 * Unpublished horsepower is -1, never passed off as an official claim.
 *
 * Data reference pages (US unless indicated):
 * https://www.tesla.com/models
 * https://www.tesla.com/model3
 * https://www.tesla.com/modelx
 * https://www.tesla.com/modely
 * https://www.tesla.com/cybertruck
 * https://www.tesla.com/ownersmanual/model3/en_us/GUID-56562137-FC31-4110-A13C-9A9FC6657BF0.html
 * https://www.tesla.com/ownersmanual/modely/en_us/GUID-1E76B638-7B12-4D9A-8767-94B7F1E92A0E.html
 * https://www.tesla.com/ownersmanual/cybertruck/en_us/GUID-12A976DD-EB60-431B-AFF1-5A37E95006DB.html
 */
public final class TeslaSpec {

    public enum Model {
        S("Model S"), THREE("Model 3"), X("Model X"),
        Y("Model Y"), CYBERTRUCK("Cybertruck");

        public final String label;
        Model(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Type {
        STANDARD("Standard"), LONG_RANGE("Long Range / Premium"),
        PERFORMANCE("Performance / Plaid");

        public final String label;
        Type(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public final Model model;
    public final Type type;
    public final String variant;
    public final String drive;
    public final String source;
    public final String note;
    public final int rangeMiles;
    public final int weightLbs;
    public final int topMph;
    public final int wheelInches;
    public final double zeroToSixty;
    public final double lengthM;
    public final double widthM;
    public final double heightM;
    public final double wheelbaseM;
    public final double trackM;
    public final double clearanceM;
    public final int motorCount;
    public final int powerHp;
    public final int chargeKw;
    public final boolean rolloutSubtracted;
    public final boolean rangeEstimated;

    private TeslaSpec(Model model, Type type, String variant, String drive,
                      int rangeMiles, double zeroToSixty, int topMph,
                      int weightLbs, int powerHp, int motorCount,
                      int wheelInches, int chargeKw,
                      double lengthM, double widthM, double heightM,
                      double wheelbaseM, double trackM, double clearanceM,
                      boolean rolloutSubtracted, boolean rangeEstimated,
                      String note, String source) {
        this.model = model;
        this.type = type;
        this.variant = variant;
        this.drive = drive;
        this.rangeMiles = rangeMiles;
        this.zeroToSixty = zeroToSixty;
        this.topMph = topMph;
        this.weightLbs = weightLbs;
        this.powerHp = powerHp;
        this.motorCount = motorCount;
        this.wheelInches = wheelInches;
        this.chargeKw = chargeKw;
        this.lengthM = lengthM;
        this.widthM = widthM;
        this.heightM = heightM;
        this.wheelbaseM = wheelbaseM;
        this.trackM = trackM;
        this.clearanceM = clearanceM;
        this.rolloutSubtracted = rolloutSubtracted;
        this.rangeEstimated = rangeEstimated;
        this.note = note;
        this.source = source;
        if (rangeMiles <= 0 || zeroToSixty <= 0 || topMph <= 0
                || weightLbs <= 0 || motorCount < 1 || wheelInches < 1
                || chargeKw <= 0 || lengthM <= wheelbaseM
                || widthM <= trackM || heightM <= 0 || clearanceM <= 0) {
            throw new IllegalArgumentException("Invalid Tesla specification: " + variant);
        }
    }

    private static TeslaSpec s(Model model, Type type, String variant,
                               String drive, int range, double acceleration,
                               int top, int weight, int hp, int motors,
                               int wheels, int charge, double length,
                               double width, double height, double wb,
                               double track, double clearance,
                               boolean rollout, boolean estimate,
                               String note, String source) {
        return new TeslaSpec(model, type, variant, drive, range,
                acceleration, top, weight, hp, motors, wheels, charge,
                length, width, height, wb, track, clearance,
                rollout, estimate, note, source);
    }

    private static final String S_URL = "https://www.tesla.com/models";
    private static final String THREE_URL = "https://www.tesla.com/model3";
    private static final String X_URL = "https://www.tesla.com/modelx";
    private static final String Y_URL = "https://www.tesla.com/modely";
    private static final String CYBER_URL = "https://www.tesla.com/cybertruck";

    private static final List<TeslaSpec> ALL;
    public static final TeslaSpec DEFAULT;

    static {
        List<TeslaSpec> a = new ArrayList<>();

        // Discontinued Model S: final listed US production configurations.
        a.add(s(Model.S, Type.LONG_RANGE, "All-Wheel Drive (final production)",
                "Dual Motor AWD", 410, 3.1, 130, 4561, 670, 2, 19, 250,
                5.021, 1.987, 1.431, 2.960, 1.690, .126,
                false, false, "Model S is no longer in production.", S_URL));
        a.add(s(Model.S, Type.PERFORMANCE, "Plaid (final production)",
                "Tri Motor AWD", 368, 1.99, 163, 4802, 1020, 3, 19, 250,
                5.046, 1.987, 1.431, 2.960, 1.690, .126,
                true, false,
                "163 mph as equipped; 200 mph requires Tesla's paid hardware upgrade. Model S is no longer in production.",
                S_URL));

        // Model 3: 2026 US-model-year values, 18-inch unless Performance.
        a.add(s(Model.THREE, Type.STANDARD, "Rear-Wheel Drive",
                "Single Motor RWD", 321, 5.8, 125, 3759, -1, 1, 18, 225,
                4.720, 1.850, 1.440, 2.875, 1.584, .138,
                false, false, "Base Model 3; 18-inch wheels.", THREE_URL));
        a.add(s(Model.THREE, Type.LONG_RANGE,
                "Premium Long Range Rear-Wheel Drive", "Single Motor RWD",
                363, 4.9, 125, 3759, -1, 1, 18, 250,
                4.720, 1.850, 1.440, 2.875, 1.584, .138,
                false, false, "Premium interior; 18-inch wheels.", THREE_URL));
        a.add(s(Model.THREE, Type.LONG_RANGE,
                "Premium Long Range All-Wheel Drive", "Dual Motor AWD",
                346, 4.2, 125, 4030, -1, 2, 18, 250,
                4.720, 1.850, 1.440, 2.875, 1.584, .138,
                false, false, "Premium interior; 18-inch wheels.", THREE_URL));
        a.add(s(Model.THREE, Type.PERFORMANCE, "Performance All-Wheel Drive",
                "Dual Motor AWD", 309, 2.9, 163, 4054, -1, 2, 20, 250,
                4.724, 1.850, 1.431, 2.875, 1.582, .128,
                true, false,
                "20-inch Warp wheels and performance-specific chassis tuning.",
                THREE_URL));

        // Discontinued Model X: final listed US production configurations.
        a.add(s(Model.X, Type.LONG_RANGE, "All-Wheel Drive (final production)",
                "Dual Motor AWD", 352, 3.8, 155, 5152, 670, 2, 20, 250,
                5.057, 1.999, 1.680, 2.965, 1.705, .166,
                false, false,
                "Adjustable air suspension; seating varies by interior. Model X is no longer in production.", X_URL));
        a.add(s(Model.X, Type.PERFORMANCE, "Plaid (final production)",
                "Tri Motor AWD", 335, 2.5, 163, 5434, 1020, 3, 20, 250,
                5.057, 1.999, 1.680, 2.965, 1.705, .166,
                true, false,
                "Six-seat Plaid configuration with adjustable air suspension. Model X is no longer in production.",
                X_URL));

        // Model Y base: 18-inch wheels and standard passenger configuration.
        a.add(s(Model.Y, Type.STANDARD, "Rear-Wheel Drive",
                "Single Motor RWD", 321, 6.8, 125, 4061, -1, 1, 18, 225,
                4.794, 1.920, 1.621, 2.890, 1.655, .164,
                false, false, "Standard five-seat model.", Y_URL));
        a.add(s(Model.Y, Type.STANDARD, "All-Wheel Drive",
                "Dual Motor AWD", 294, 4.3, 125, 4246, -1, 2, 18, 225,
                4.794, 1.920, 1.621, 2.890, 1.655, .164,
                false, false, "Standard five-seat AWD model.", Y_URL));

        // Model Y Premium and Performance.
        a.add(s(Model.Y, Type.LONG_RANGE, "Premium All-Wheel Drive",
                "Dual Motor AWD", 327, 4.3, 125, 4473, -1, 2, 19, 250,
                4.790, 1.920, 1.624, 2.890, 1.636, .167,
                false, false,
                "Five seats standard, up to seven with an optional third row; 19-inch wheels.",
                Y_URL));
        a.add(s(Model.Y, Type.LONG_RANGE, "Model Y L Premium All-Wheel Drive",
                "Dual Motor AWD", 332, 4.4, 125, 4612, -1, 2, 19, 250,
                4.969, 1.920, 1.669, 3.040, 1.636, .170,
                false, false,
                "Long-wheelbase six-seat three-row Model Y L; 19-inch wheels.", Y_URL));
        a.add(s(Model.Y, Type.PERFORMANCE, "Performance All-Wheel Drive",
                "Dual Motor AWD", 306, 3.3, 155, 4466, -1, 2, 21, 250,
                4.796, 1.920, 1.611, 2.890, 1.636, .151,
                true, false,
                "21-inch wheels, adaptive damping, five seats.", Y_URL));

        // Cybertruck: body width excludes mirrors. Air-suspension clearance is
        // NORMAL/MEDIUM, not 16-inch Extract mode (only on equipped trims).
        a.add(s(Model.CYBERTRUCK, Type.STANDARD, "Dual Motor All-Wheel Drive",
                "Dual Motor AWD", 325, 4.1, 112, 6537, -1, 2, 18, 325,
                5.6829, 2.0316, 1.7880, 3.635, 1.772, .248,
                false, false,
                "Fixed-height suspension; 10-inch-class clearance; 7,500 lb towing; 18-inch wheels.",
                CYBER_URL));
        a.add(s(Model.CYBERTRUCK, Type.LONG_RANGE,
                "Premium All-Wheel Drive", "Dual Motor AWD",
                325, 4.1, 112, 6634, -1, 2, 20, 325,
                5.6829, 2.0316, 1.7938, 3.635, 1.772, .257,
                false, false,
                "Adjustable air suspension; up to 16-inch clearance in Extract mode; 11,000 lb towing.",
                CYBER_URL));
        a.add(s(Model.CYBERTRUCK, Type.PERFORMANCE, "Cyberbeast",
                "Tri Motor AWD", 320, 2.6, 130, 6863, 845, 3, 20, 325,
                5.6829, 2.0316, 1.7938, 3.635, 1.772, .257,
                true, true,
                "845 hp Tesla-listed raw power; estimated range, adjustable air suspension and 11,000 lb towing.",
                CYBER_URL));

        ALL = Collections.unmodifiableList(a);
        DEFAULT = a.stream()
                .filter(s -> s.model == Model.THREE && s.type == Type.PERFORMANCE)
                .findFirst().orElseThrow();
    }

    public static List<TeslaSpec> all() { return ALL; }

    public static List<TeslaSpec> options(Model model, Type type) {
        List<TeslaSpec> result = new ArrayList<>();
        for (TeslaSpec s : ALL) {
            if (s.model == model && s.type == type) result.add(s);
        }
        return result;
    }

    /** Maximum offered seating for the listed trim, not an installed-seat count. */
    public int seats() {
        if (model == Model.X) return type == Type.PERFORMANCE ? 6 : 7;
        if (model == Model.Y && variant.startsWith("Model Y L")) return 6;
        if (model == Model.Y && type == Type.LONG_RANGE) return 7;
        return 5;
    }

    public String cargoFeet() {
        return switch (model) {
            case S -> "28";
            case THREE -> "24";
            case X -> "94.5";
            case Y -> variant.startsWith("Model Y L") ? "89"
                    : type == Type.STANDARD
                    ? (drive.startsWith("Single") ? "74" : "74.8") : "76";
            case CYBERTRUCK -> type == Type.STANDARD ? "117.3" : "120.7";
        };
    }

    public String towingLbs() {
        return switch (model) {
            case S, THREE, Y -> "not specified for this US trim";
            case X -> "5,000";
            case CYBERTRUCK -> type == Type.STANDARD ? "7,500" : "11,000";
        };
    }

    public double massKg() { return weightLbs * .45359237; }

    /** Every configured trim has a verified speed; never use a fake fallback. */
    public double topMps() { return topMph * .44704; }

    public double rangeKm() { return rangeMiles * 1.609344; }

    public String fullName() {
        return model == Model.Y && variant.startsWith("Model Y L")
                ? "Tesla " + variant : "Tesla " + model.label + " " + variant;
    }

    public String details() {
        return "<html><b>" + fullName() + "</b>"
                + "<br>Drive: " + drive + " | " + motorCount + " motor(s)"
                + "<br>US " + (rangeEstimated ? "estimated" : "EPA-rated")
                + " range: " + rangeMiles + " mi (" + Math.round(rangeKm()) + " km)"
                + "<br>0-60 mph: " + zeroToSixty + " s"
                + (rolloutSubtracted ? " (rollout subtracted)" : "")
                + "<br>Top speed as configured: " + topMph + " mph"
                + "<br>Curb mass: " + weightLbs + " lb (" + Math.round(massKg()) + " kg)"
                + "<br>Body L x W x H, excluding mirrors: "
                + mm(lengthM) + " x " + mm(widthM) + " x " + mm(heightM) + " mm"
                + "<br>Wheelbase: " + mm(wheelbaseM) + " mm"
                + " | Front track: " + mm(trackM) + " mm"
                + "<br>Normal ground clearance: " + mm(clearanceM) + " mm"
                + " | Listed wheels: " + wheelInches + " in"
                + "<br>Maximum seats offered: " + seats()
                + " | Max cargo: " + cargoFeet() + " cu ft"
                + "<br>Trailer towing: " + towingLbs() + " lb"
                + "<br>Peak DC charging: " + chargeKw + " kW"
                + "<br>Published motor power: "
                + (powerHp < 0 ? "not stated for this trim"
                : powerHp + " hp" + (model == Model.CYBERTRUCK ? " (raw)" : ""))
                + "<br><i>" + note + "</i>"
                + "<br><i>US configuration; other wheels, markets and loads differ."
                + " Physics/visuals remain approximations.</i></html>";
    }

    private static String mm(double m) {
        return String.format(Locale.ROOT, "%.0f", m * 1000);
    }

    @Override public String toString() { return variant; }
}
