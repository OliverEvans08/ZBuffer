package objects.dynamic;

final class BodyMotionState {

    boolean movingIntent;
    boolean onGround;
    boolean prevOnGround = true;
    boolean flightMode;

    double yVel;
    double intentForward;
    double intentStrafe;
    double intentSpeed;
    double viewPitch;

    void set(
            boolean movingIntent,
            boolean onGround,
            double yVelocity,
            boolean flightMode,
            double intentForward,
            double intentStrafe,
            double intentSpeed,
            double viewPitch
    ) {
        this.movingIntent = movingIntent;
        this.onGround = onGround;
        this.yVel = yVelocity;
        this.flightMode = flightMode;
        this.intentForward = intentForward;
        this.intentStrafe = intentStrafe;
        this.intentSpeed = intentSpeed;
        this.viewPitch = viewPitch;
    }
}