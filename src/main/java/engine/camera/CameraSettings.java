package engine.camera;

public final class CameraSettings {

    public static final double FP_FORWARD_OFFSET = 0.06;

    public static final double CAM_WALL_PAD = 0.12;
    public static final double CAM_MIN_DIST = 0.20;
    public static final double CAMERA_TINY_MOVE2 = 0.0025 * 0.0025;
    public static final double CAMERA_RECHECK_INTERVAL = 0.050;

    public static final double BOB_FREQ_BASE = 4.5;
    public static final double BOB_FREQ_ADD = 4.0;
    public static final double BOB_Y_MAX = 0.050;
    public static final double BOB_X_MAX = 0.030;

    public static final double TPS_SMOOTH_OUT = 10.0;
    public static final double TPS_SMOOTH_IN = 40.0;

    private CameraSettings() {
    }
}