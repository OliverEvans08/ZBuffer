package engine.render;

public final class RenderSettings {
    public static final double NEAR = 0.08;
    public static final double BODY_NEAR_PADDING = 0.12;
    public static final double BODY_Z_BIAS = 0.04;
    public static final double NEAR_CLIP_EPSILON = 1.0e-6;

    /*
     * Aggressive performance defaults.
     * Directional only; point/spot shadow maps (6 faces) are disabled in
     * ShadowCalculator – they dominate CPU cost.
     */
    public static final int SHADOW_MAP_SIZE = 192;
    public static final int POINT_SHADOW_MAP_SIZE = 64; // unused while point shadows disabled

    /*
     * Radius 0 = fast 2×2 bilinear PCF (no loop). Removes crawling hard edges
     * without the cost of a larger kernel.
     */
    public static final int SHADOW_PCF_RADIUS = 0;

    public static final double SHADOW_NEAR = 0.02;
    public static final double SHADOW_DEPTH_BIAS = 0.0025;
    public static final double SHADOW_NORMAL_BIAS = 0.006;

    // Larger tiles → fewer tiles → less binning + scheduling overhead
    public static final int TILE_SHIFT = 7;
    public static final int TILE_SIZE = 1 << TILE_SHIFT; // 128

    public static final int PIXELS_PER_RENDER_WORKER = 262_144;
    public static final int TILE_WORK_CHUNK = 4;
    public static final int TRIANGLE_BUILD_PARALLEL_THRESHOLD = 6_144;

    public static final int SUBPIXEL_BITS = 8;
    public static final int SUBPIXEL_SCALE = 1 << SUBPIXEL_BITS;
    public static final int SUBPIXEL_HALF = SUBPIXEL_SCALE >> 1;

    public static final int SKY_TOP = 0xFF0A0F1A;
    public static final int SKY_BOTTOM = 0xFF020306;

    private double renderScale = 0.40; // lower default for immediate FPS relief
    private boolean fxaaEnabled;

    public double getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(double renderScale) {
        if (!Double.isFinite(renderScale)) {
            return;
        }

        this.renderScale = Math.max(
                0.25,
                Math.min(1.0, renderScale)
        );
    }

    public boolean isFxaaEnabled() {
        return fxaaEnabled;
    }

    public void setFxaaEnabled(boolean enabled) {
        fxaaEnabled = enabled;
    }
}