package engine.render;

public final class RenderSettings {
    public static final double NEAR = 0.08;
    public static final double BODY_NEAR_PADDING = 0.12;
    public static final double BODY_Z_BIAS = 0.04;
    public static final double NEAR_CLIP_EPSILON = 1.0e-6;
    public static final double SHADOW_BIAS = 0.002;
    public static final double RAY_EPSILON = 1.0e-9;
    public static final double SHADOW_RECEIVER_MAXIMUM_DISTANCE = 70.0;
    public static final double SHADOW_DIRECTIONAL_MAXIMUM_DISTANCE = 80.0;
    public static final int TILE_SHIFT = 5;
    public static final int TILE_SIZE = 1 << TILE_SHIFT;
    public static final int PIXELS_PER_RENDER_WORKER = 65_536;
    public static final int TRIANGLE_BUILD_PARALLEL_THRESHOLD = 2_048;
    public static final int SUBPIXEL_BITS = 8;
    public static final int SUBPIXEL_SCALE = 1 << SUBPIXEL_BITS;
    public static final int SUBPIXEL_HALF = SUBPIXEL_SCALE >> 1;
    public static final int SKY_TOP = 0xFF0A0F1A;
    public static final int SKY_BOTTOM = 0xFF020306;

    private double renderScale = 0.50;
    private boolean fxaaEnabled;

    public double getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(double renderScale) {
        if (!Double.isFinite(renderScale)) {
            return;
        }
        this.renderScale = Math.max(0.25, Math.min(1.0, renderScale));
    }

    public boolean isFxaaEnabled() {
        return fxaaEnabled;
    }

    public void setFxaaEnabled(boolean enabled) {
        fxaaEnabled = enabled;
    }
}