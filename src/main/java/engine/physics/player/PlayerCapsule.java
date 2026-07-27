package engine.physics.player;

public final class PlayerCapsule {

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.6;
    public static final double HEAD_HEIGHT = HEIGHT - 0.08;

    public static final double RADIUS = WIDTH * 0.5;
    public static final double RADIUS2 = RADIUS * RADIUS;

    /*
     * Maximum ledge heights that grounded movement may negotiate
     * automatically. Step-down is slightly larger so small seams do not
     * briefly put the player into a falling state.
     */
    public static final double MAX_STEP_UP = 0.45;
    public static final double MAX_STEP_DOWN = 0.55;

    private PlayerCapsule() {
    }
}