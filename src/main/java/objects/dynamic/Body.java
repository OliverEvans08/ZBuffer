package objects.dynamic;

import objects.GameObject;

public class Body extends GameObject {

    static final double[][] EMPTY_VERTICES =
            new double[0][0];

    private final BodyParts parts;
    private final BodyPose pose;
    private final BodyMotionState motionState;
    private final BodyAnimator animator;

    private boolean modelVisible = true;

    public Body(double width, double height) {
        setFull(false);

        parts =
                BodyBuilder.build(
                        this,
                        new BodyDimensions(
                                width,
                                height
                        )
                );

        pose = new BodyPose(parts);
        motionState = new BodyMotionState();

        animator =
                new BodyAnimator(
                        this,
                        pose,
                        motionState
                );
    }

    public void setMotionState(
            boolean movingIntent,
            boolean onGround,
            double yVelocity,
            boolean flightMode,
            double intentForward,
            double intentStrafe,
            double intentSpeed,
            double viewPitch
    ) {
        motionState.set(
                movingIntent,
                onGround,
                yVelocity,
                flightMode,
                intentForward,
                intentStrafe,
                intentSpeed,
                viewPitch
        );
    }

    public void setMotionState(
            boolean movingIntent,
            boolean onGround,
            double yVelocity,
            boolean flightMode
    ) {
        setMotionState(
                movingIntent,
                onGround,
                yVelocity,
                flightMode,
                0.0,
                0.0,
                0.0,
                0.0
        );
    }

    public void setModelVisible(
            boolean visible
    ) {
        if (modelVisible == visible) {
            return;
        }

        modelVisible = visible;

        setVisible(visible);

        parts.setVisible(visible);

        if (!visible) {
            animator.resetVelocity();
        }
    }

    @Override
    public double[][] getVertices() {
        return EMPTY_VERTICES;
    }

    @Override
    public int[][] getFacesArray() {
        return null;
    }

    @Override
    public void update(double dt) {
        if (!modelVisible) {
            return;
        }

        animator.update(dt);
    }
}