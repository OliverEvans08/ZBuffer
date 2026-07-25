package objects.dynamic;

final class BodyAnimator {

    private final Body body;
    private final BodyPose pose;
    private final BodyMotionState motionState;

    private boolean velInit;
    private double lastX;
    private double lastZ;

    private double vFwdSm;
    private double vStrSm;
    private double vSpdSm;
    private double pitchSm;

    private double t;
    private double gait;
    private double landTimer;

    BodyAnimator(
            Body body,
            BodyPose pose,
            BodyMotionState motionState
    ) {
        this.body = body;
        this.pose = pose;
        this.motionState = motionState;
    }

    void resetVelocity() {
        velInit = false;
    }

    void update(double dt) {
        t += dt;

        final boolean onGround =
                motionState.onGround;

        final boolean flightMode =
                motionState.flightMode;

        final double yVel =
                motionState.yVel;

        final double viewPitch =
                motionState.viewPitch;

        if (
                !motionState.prevOnGround
                        && onGround
        ) {
            landTimer = 0.12;
        }

        motionState.prevOnGround = onGround;

        if (landTimer > 0.0) {
            landTimer =
                    Math.max(0.0, landTimer - dt);
        }

        double fwd =
                motionState.intentForward;

        double str =
                motionState.intentStrafe;

        double spd =
                motionState.intentSpeed;

        final boolean hasDirectionalIntent =
                Math.abs(fwd) + Math.abs(str)
                        > 1.0e-4;

        final double px =
                body.getTransform().position.x;

        final double pz =
                body.getTransform().position.z;

        if (!velInit) {
            velInit = true;
            lastX = px;
            lastZ = pz;
        }

        double vFwd = 0.0;
        double vStr = 0.0;
        double vSpd = 0.0;

        if (dt > 1.0e-6) {
            final double vx =
                    (px - lastX) / dt;

            final double vz =
                    (pz - lastZ) / dt;

            final double yaw =
                    body.getTransform().rotation.y;

            final double sineYaw =
                    Math.sin(yaw);

            final double cosineYaw =
                    Math.cos(yaw);

            vFwd =
                    vx * sineYaw
                            + vz * cosineYaw;

            vStr =
                    vx * cosineYaw
                            - vz * sineYaw;

            vSpd =
                    Math.sqrt(
                            vFwd * vFwd
                                    + vStr * vStr
                    );
        }

        lastX = px;
        lastZ = pz;

        if (!hasDirectionalIntent) {
            fwd = vFwd;
            str = vStr;
            spd = vSpd;
        } else if (spd <= 1.0e-6) {
            spd =
                    Math.sqrt(
                            fwd * fwd
                                    + str * str
                    );
        }

        final double intentA =
                1.0 - Math.exp(-dt * 10.0);

        vFwdSm +=
                (fwd - vFwdSm) * intentA;

        vStrSm +=
                (str - vStrSm) * intentA;

        vSpdSm +=
                (spd - vSpdSm) * intentA;

        final double pitchA =
                1.0 - Math.exp(-dt * 12.0);

        pitchSm +=
                (viewPitch - pitchSm) * pitchA;

        final double interpolation =
                1.0 - Math.exp(-dt * 22.0);

        final double speed01 =
                clamp01(vSpdSm / 5.5);

        final double fwd01 =
                vSpdSm > 1.0e-6
                        ? vFwdSm / vSpdSm
                        : 0.0;

        final double str01 =
                vSpdSm > 1.0e-6
                        ? vStrSm / vSpdSm
                        : 0.0;

        if (
                !flightMode
                        && onGround
                        && speed01 > 0.02
        ) {
            double frequency =
                    vSpdSm / 1.35;

            frequency =
                    clamp(
                            frequency,
                            1.5,
                            3.8
                    );

            gait +=
                    dt
                            * frequency
                            * Math.PI
                            * 2.0;
        } else {
            gait += dt;
        }

        final double sineGait =
                Math.sin(gait);

        final double cosineGait =
                Math.cos(gait);

        final double breath =
                Math.sin(t * 1.4) * 0.035;

        final double idleSway =
                Math.sin(t * 0.9) * 0.025;

        double landK =
                landTimer > 0.0
                        ? landTimer / 0.12
                        : 0.0;

        landK *= landK;

        final double absoluteSine =
                Math.abs(sineGait);

        final double pelvisBob =
                onGround
                        ? absoluteSine
                        * 0.030
                        * speed01
                        : 0.0;

        final double torsoBob =
                onGround
                        ? absoluteSine
                        * 0.060
                        * speed01
                        : 0.0;

        final double fwdComp =
                Math.max(0.0, fwd01);

        final double backComp =
                Math.max(0.0, -fwd01);

        final double forwardLean =
                fwdComp * 0.26 * speed01;

        final double backwardLean =
                backComp * 0.08 * speed01;

        final double torsoLeanF =
                -(forwardLean + backwardLean);

        final double torsoLeanS =
                -str01 * 0.20 * speed01;

        final boolean inAir =
                !flightMode && !onGround;

        final double fall01 =
                clamp01(-yVel / 18.0);

        final double jump01 =
                clamp01(yVel / 12.0);

        final double hipSwing =
                0.30 + 0.55 * speed01;

        final double kneeBend =
                0.35 + 0.95 * speed01;

        final double armSwing =
                0.25 + 0.70 * speed01;

        final double elbowBend =
                0.15 + 0.55 * speed01;

        final double leftSine =
                sineGait;

        final double rightSine =
                -sineGait;

        final double kneeLiftL =
                Math.max(0.0, -leftSine);

        final double kneeLiftR =
                Math.max(0.0, -rightSine);

        double hipLx =
                leftSine * hipSwing;

        double hipRx =
                rightSine * hipSwing;

        double kneeLx =
                kneeLiftL * kneeBend;

        double kneeRx =
                kneeLiftR * kneeBend;

        double ankleLx =
                -Math.max(0.0, leftSine)
                        * 0.40
                        * speed01;

        double ankleRx =
                -Math.max(0.0, rightSine)
                        * 0.40
                        * speed01;

        double armLx =
                -leftSine * armSwing;

        double armRx =
                -rightSine * armSwing;

        double elbowL =
                Math.max(0.0, leftSine)
                        * elbowBend;

        double elbowR =
                Math.max(0.0, rightSine)
                        * elbowBend;

        if (fwd01 < -0.2) {
            hipLx *= 0.75;
            hipRx *= 0.75;
            armLx *= 0.65;
            armRx *= 0.65;
        }

        if (flightMode) {
            hipLx = 0.10;
            hipRx = 0.10;

            kneeLx = 0.25;
            kneeRx = 0.25;

            ankleLx = 0.0;
            ankleRx = 0.0;

            armLx = -0.35;
            armRx = -0.35;

            elbowL = 0.25;
            elbowR = 0.25;
        } else if (inAir) {
            final double tuck =
                    0.55 + 0.35 * fall01;

            hipLx = 0.25;
            hipRx = 0.25;

            kneeLx = tuck;
            kneeRx = tuck;

            ankleLx = 0.10;
            ankleRx = 0.10;

            armLx =
                    -0.55 + 0.20 * jump01;

            armRx =
                    -0.55 + 0.20 * jump01;

            elbowL =
                    0.25 + 0.25 * fall01;

            elbowR =
                    0.25 + 0.25 * fall01;
        }

        pose.pPelvis.posY(
                interpolation,
                pose.pPelvis.by
                        + pelvisBob
                        - landK * 0.05
        );

        pose.pPelvis.rotX(
                interpolation,
                -torsoLeanF * 0.35
                        - landK * 0.10
        );

        pose.pPelvis.rotZ(
                interpolation,
                idleSway * 0.50
                        + torsoLeanS * 0.55
        );

        pose.pTorso.posY(
                interpolation,
                pose.pTorso.by
                        + torsoBob
                        - landK * 0.08
        );

        pose.pTorso.rotX(
                interpolation,
                breath
                        - torsoLeanF
                        - landK * 0.25
        );

        pose.pTorso.rotZ(
                interpolation,
                idleSway
                        + torsoLeanS
                        + (
                        onGround
                                ? cosineGait
                                * 0.06
                                * speed01
                                : 0.0
                )
        );

        pose.pTorso.rotY(
                interpolation,
                -str01 * 0.10 * speed01
        );

        final double pitchFollow =
                clamp(
                        pitchSm * 0.65,
                        -0.65,
                        0.65
                );

        pose.pNeck.rotX(
                interpolation,
                -pitchFollow * 0.35
        );

        pose.pHead.rotX(
                interpolation,
                pitchFollow
                        + (
                        onGround
                                ? absoluteSine
                                * 0.09
                                * speed01
                                : 0.0
                )
        );

        pose.pHead.rotZ(
                interpolation,
                onGround
                        ? cosineGait
                        * 0.06
                        * speed01
                        : 0.0
        );

        pose.pHead.rotY(
                interpolation,
                -str01 * 0.10
        );

        pose.pLUA.rotX(
                interpolation,
                armLx
        );

        pose.pRUA.rotX(
                interpolation,
                armRx
        );

        pose.pLFA.rotX(
                interpolation,
                0.12 + elbowL
        );

        pose.pRFA.rotX(
                interpolation,
                0.12 + elbowR
        );

        pose.pLH.rotX(
                interpolation,
                0.06 + elbowL * 0.15
        );

        pose.pRH.rotX(
                interpolation,
                0.06 + elbowR * 0.15
        );

        pose.pLT.rotX(
                interpolation,
                hipLx
        );

        pose.pRT.rotX(
                interpolation,
                hipRx
        );

        pose.pLS.rotX(
                interpolation,
                kneeLx
        );

        pose.pRS.rotX(
                interpolation,
                kneeRx
        );

        pose.pLF.rotX(
                interpolation,
                ankleLx
        );

        pose.pRF.rotX(
                interpolation,
                ankleRx
        );
    }

    private static double clamp01(
            double value
    ) {
        if (value < 0.0) {
            return 0.0;
        }

        if (value > 1.0) {
            return 1.0;
        }

        return value;
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (value < minimum) {
            return minimum;
        }

        if (value > maximum) {
            return maximum;
        }

        return value;
    }
}