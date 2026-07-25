package engine.physics.player;

public final class PlayerCapsule {

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.6;
    public static final double HEAD_HEIGHT = HEIGHT - 0.08;

    public static final double RADIUS = WIDTH * 0.5;
    public static final double RADIUS2 = RADIUS * RADIUS;

    private PlayerCapsule() {
    }
}