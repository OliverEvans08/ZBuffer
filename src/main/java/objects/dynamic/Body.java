package objects.dynamic;

import engine.render.Material;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public class Body extends GameObject {

    private static final double[][] EMPTY_VERTICES =
            new double[0][0];

    private final BoxPart pelvis;
    private final CapsulePart torso;
    private final CapsulePart neck;
    private final SpherePart head;

    private final CapsulePart lUpperArm;
    private final CapsulePart lForeArm;
    private final SpherePart lHand;

    private final CapsulePart rUpperArm;
    private final CapsulePart rForeArm;
    private final SpherePart rHand;

    private final CapsulePart lThigh;
    private final CapsulePart lShin;
    private final BoxPart lFoot;

    private final CapsulePart rThigh;
    private final CapsulePart rShin;
    private final BoxPart rFoot;

    private final GameObject rightHandSocket;

    private final PoseBase pPelvis;
    private final PoseBase pTorso;
    private final PoseBase pNeck;
    private final PoseBase pHead;

    private final PoseBase pLUA;
    private final PoseBase pLFA;
    private final PoseBase pLH;
    private final PoseBase pRUA;
    private final PoseBase pRFA;
    private final PoseBase pRH;

    private final PoseBase pLT;
    private final PoseBase pLS;
    private final PoseBase pLF;
    private final PoseBase pRT;
    private final PoseBase pRS;
    private final PoseBase pRF;

    private boolean movingIntent;
    private boolean onGround;
    private boolean prevOnGround = true;
    private boolean flightMode;
    private boolean modelVisible = true;

    private double yVel;
    private double intentForward;
    private double intentStrafe;
    private double intentSpeed;
    private double viewPitch;

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

    public Body(double width, double height) {
        setFull(false);

        final double h =
                Math.max(1.2, height);

        final double w =
                Math.max(0.35, width);

        final double legH = h * 0.52;
        final double pelvisH = h * 0.10;
        final double torsoH = h * 0.28;
        final double neckH = h * 0.05;
        final double headR = h * 0.10;

        final double thighL = legH * 0.48;
        final double shinL = legH * 0.40;

        final double footH =
                Math.max(
                        0.06,
                        legH - thighL - shinL
                );

        final double armH = torsoH * 0.92;
        final double uArmL = armH * 0.52;
        final double fArmL = armH * 0.48;

        final double handR = w * 0.12;
        final double legR = w * 0.12;
        final double armR = w * 0.10;
        final double torsoR = w * 0.22;
        final double neckR = w * 0.11;

        final Material skin =
                Material.solid(
                                new Color(255, 196, 160)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material shirt =
                Material.solid(
                                new Color(70, 170, 255)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material pants =
                Material.solid(
                                new Color(50, 70, 95)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material shoes =
                Material.solid(
                                new Color(35, 35, 35)
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.95);

        final double legTotal =
                thighL + shinL + footH;

        pelvis = new BoxPart(
                w * 0.55,
                pelvisH,
                w * 0.30,
                BoxPart.Anchor.BOTTOM
        );

        pelvis.setMaterial(pants);
        pelvis.getTransform().position =
                new Vector3(0.0, legTotal, 0.0);

        torso = new CapsulePart(
                torsoR,
                torsoH,
                12,
                4,
                2,
                CapsulePart.Anchor.BOTTOM
        );

        torso.setMaterial(shirt);
        torso.getTransform().position =
                new Vector3(
                        0.0,
                        pelvisH * 0.98,
                        0.0
                );

        neck = new CapsulePart(
                neckR,
                neckH,
                12,
                3,
                1,
                CapsulePart.Anchor.BOTTOM
        );

        neck.setMaterial(skin);
        neck.getTransform().position =
                new Vector3(
                        0.0,
                        torsoH * 0.985,
                        0.0
                );

        head = new SpherePart(
                headR,
                16,
                10,
                SpherePart.Anchor.BOTTOM
        );

        head.setMaterial(skin);
        head.getTransform().position =
                new Vector3(
                        0.0,
                        neckH * 0.98,
                        0.0
                );

        final double shoulderY =
                torsoH * 0.82;

        final double shoulderX =
                torsoR + armR * 1.75;

        final double shoulderZ =
                torsoR * 0.16;

        lUpperArm = new CapsulePart(
                armR,
                uArmL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        lUpperArm.setMaterial(skin);
        lUpperArm.getTransform().position =
                new Vector3(
                        -shoulderX,
                        shoulderY,
                        shoulderZ
                );

        lForeArm = new CapsulePart(
                armR * 0.92,
                fArmL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        lForeArm.setMaterial(skin);
        lForeArm.getTransform().position =
                new Vector3(
                        0.0,
                        -uArmL,
                        0.0
                );

        lHand = new SpherePart(
                handR,
                14,
                8,
                SpherePart.Anchor.TOP
        );

        lHand.setMaterial(skin);
        lHand.getTransform().position =
                new Vector3(
                        0.0,
                        -fArmL,
                        0.0
                );

        rUpperArm = new CapsulePart(
                armR,
                uArmL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        rUpperArm.setMaterial(skin);
        rUpperArm.getTransform().position =
                new Vector3(
                        shoulderX,
                        shoulderY,
                        shoulderZ
                );

        rForeArm = new CapsulePart(
                armR * 0.92,
                fArmL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        rForeArm.setMaterial(skin);
        rForeArm.getTransform().position =
                new Vector3(
                        0.0,
                        -uArmL,
                        0.0
                );

        rHand = new SpherePart(
                handR,
                14,
                8,
                SpherePart.Anchor.TOP
        );

        rHand.setMaterial(skin);
        rHand.getTransform().position =
                new Vector3(
                        0.0,
                        -fArmL,
                        0.0
                );

        lUpperArm.getTransform().rotation.z = -0.40;
        rUpperArm.getTransform().rotation.z = 0.40;

        lUpperArm.getTransform().rotation.y = 0.10;
        rUpperArm.getTransform().rotation.y = -0.10;

        lForeArm.getTransform().rotation.x = 0.12;
        rForeArm.getTransform().rotation.x = 0.12;

        lHand.getTransform().rotation.x = 0.05;
        rHand.getTransform().rotation.x = 0.05;

        final double hipY =
                pelvisH * 0.10;

        final double hipX =
                w * 0.18;

        lThigh = new CapsulePart(
                legR,
                thighL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        lThigh.setMaterial(pants);
        lThigh.getTransform().position =
                new Vector3(-hipX, hipY, 0.0);

        lShin = new CapsulePart(
                legR * 0.92,
                shinL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        lShin.setMaterial(pants);
        lShin.getTransform().position =
                new Vector3(0.0, -thighL, 0.0);

        lFoot = new BoxPart(
                w * 0.22,
                footH,
                w * 0.38,
                BoxPart.Anchor.TOP
        );

        lFoot.setMaterial(shoes);
        lFoot.getTransform().position =
                new Vector3(
                        0.0,
                        -shinL,
                        w * 0.10
                );

        rThigh = new CapsulePart(
                legR,
                thighL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        rThigh.setMaterial(pants);
        rThigh.getTransform().position =
                new Vector3(hipX, hipY, 0.0);

        rShin = new CapsulePart(
                legR * 0.92,
                shinL,
                12,
                3,
                2,
                CapsulePart.Anchor.TOP
        );

        rShin.setMaterial(pants);
        rShin.getTransform().position =
                new Vector3(0.0, -thighL, 0.0);

        rFoot = new BoxPart(
                w * 0.22,
                footH,
                w * 0.38,
                BoxPart.Anchor.TOP
        );

        rFoot.setMaterial(shoes);
        rFoot.getTransform().position =
                new Vector3(
                        0.0,
                        -shinL,
                        w * 0.10
                );

        addChild(pelvis);

        pelvis.addChild(torso);

        torso.addChild(neck);
        neck.addChild(head);

        torso.addChild(lUpperArm);
        lUpperArm.addChild(lForeArm);
        lForeArm.addChild(lHand);

        torso.addChild(rUpperArm);
        rUpperArm.addChild(rForeArm);
        rForeArm.addChild(rHand);

        pelvis.addChild(lThigh);
        lThigh.addChild(lShin);
        lShin.addChild(lFoot);

        pelvis.addChild(rThigh);
        rThigh.addChild(rShin);
        rShin.addChild(rFoot);

        rightHandSocket =
                new HandSocketNode();

        rightHandSocket.setName("hand_socket");
        rightHandSocket.setVisible(false);
        rightHandSocket.setActive(true);
        rightHandSocket.setSolid(false);
        rightHandSocket.setIgnorePlayerCollisions(
                true
        );

        rightHandSocket.getTransform().position =
                new Vector3(
                        0.0,
                        -handR * 0.95,
                        handR * 0.65
                );

        rHand.addChild(rightHandSocket);

        pPelvis = new PoseBase(pelvis);
        pTorso = new PoseBase(torso);
        pNeck = new PoseBase(neck);
        pHead = new PoseBase(head);

        pLUA = new PoseBase(lUpperArm);
        pLFA = new PoseBase(lForeArm);
        pLH = new PoseBase(lHand);

        pRUA = new PoseBase(rUpperArm);
        pRFA = new PoseBase(rForeArm);
        pRH = new PoseBase(rHand);

        pLT = new PoseBase(lThigh);
        pLS = new PoseBase(lShin);
        pLF = new PoseBase(lFoot);

        pRT = new PoseBase(rThigh);
        pRS = new PoseBase(rShin);
        pRF = new PoseBase(rFoot);
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
        this.movingIntent = movingIntent;
        this.onGround = onGround;
        this.yVel = yVelocity;
        this.flightMode = flightMode;
        this.intentForward = intentForward;
        this.intentStrafe = intentStrafe;
        this.intentSpeed = intentSpeed;
        this.viewPitch = viewPitch;
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

        pelvis.setVisible(visible);
        torso.setVisible(visible);
        neck.setVisible(visible);
        head.setVisible(visible);

        lUpperArm.setVisible(visible);
        lForeArm.setVisible(visible);
        lHand.setVisible(visible);

        rUpperArm.setVisible(visible);
        rForeArm.setVisible(visible);
        rHand.setVisible(visible);

        lThigh.setVisible(visible);
        lShin.setVisible(visible);
        lFoot.setVisible(visible);

        rThigh.setVisible(visible);
        rShin.setVisible(visible);
        rFoot.setVisible(visible);

        if (!visible) {
            velInit = false;
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

        t += dt;

        if (!prevOnGround && onGround) {
            landTimer = 0.12;
        }

        prevOnGround = onGround;

        if (landTimer > 0.0) {
            landTimer =
                    Math.max(0.0, landTimer - dt);
        }

        double fwd = intentForward;
        double str = intentStrafe;
        double spd = intentSpeed;

        final boolean hasDirectionalIntent =
                Math.abs(fwd) + Math.abs(str)
                        > 1.0e-4;

        final double px =
                transform.position.x;

        final double pz =
                transform.position.z;

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
                    transform.rotation.y;

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

        pPelvis.posY(
                interpolation,
                pPelvis.by
                        + pelvisBob
                        - landK * 0.05
        );

        pPelvis.rotX(
                interpolation,
                -torsoLeanF * 0.35
                        - landK * 0.10
        );

        pPelvis.rotZ(
                interpolation,
                idleSway * 0.50
                        + torsoLeanS * 0.55
        );

        pTorso.posY(
                interpolation,
                pTorso.by
                        + torsoBob
                        - landK * 0.08
        );

        pTorso.rotX(
                interpolation,
                breath
                        - torsoLeanF
                        - landK * 0.25
        );

        pTorso.rotZ(
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

        pTorso.rotY(
                interpolation,
                -str01 * 0.10 * speed01
        );

        final double pitchFollow =
                clamp(
                        pitchSm * 0.65,
                        -0.65,
                        0.65
                );

        pNeck.rotX(
                interpolation,
                -pitchFollow * 0.35
        );

        pHead.rotX(
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

        pHead.rotZ(
                interpolation,
                onGround
                        ? cosineGait
                        * 0.06
                        * speed01
                        : 0.0
        );

        pHead.rotY(
                interpolation,
                -str01 * 0.10
        );

        pLUA.rotX(interpolation, armLx);
        pRUA.rotX(interpolation, armRx);

        pLFA.rotX(
                interpolation,
                0.12 + elbowL
        );

        pRFA.rotX(
                interpolation,
                0.12 + elbowR
        );

        pLH.rotX(
                interpolation,
                0.06 + elbowL * 0.15
        );

        pRH.rotX(
                interpolation,
                0.06 + elbowR * 0.15
        );

        pLT.rotX(interpolation, hipLx);
        pRT.rotX(interpolation, hipRx);

        pLS.rotX(interpolation, kneeLx);
        pRS.rotX(interpolation, kneeRx);

        pLF.rotX(interpolation, ankleLx);
        pRF.rotX(interpolation, ankleRx);
    }

    private static final class HandSocketNode
            extends GameObject {

        @Override
        public void update(double delta) {
        }

        @Override
        public double[][] getVertices() {
            return EMPTY_VERTICES;
        }

        @Override
        public int[][] getFacesArray() {
            return null;
        }
    }

    private static final class PoseBase {

        private final GameObject object;

        private final double by;

        private PoseBase(GameObject object) {
            this.object = object;
            this.by =
                    object.getTransform().position.y;
        }

        private void posY(
                double interpolation,
                double value
        ) {
            final double current =
                    object.getTransform().position.y;

            object.getTransform().position.y =
                    current
                            + (
                            value - current
                    ) * interpolation;
        }

        private void rotX(
                double interpolation,
                double value
        ) {
            final double current =
                    object.getTransform().rotation.x;

            object.getTransform().rotation.x =
                    current
                            + (
                            value - current
                    ) * interpolation;
        }

        private void rotY(
                double interpolation,
                double value
        ) {
            final double current =
                    object.getTransform().rotation.y;

            object.getTransform().rotation.y =
                    current
                            + (
                            value - current
                    ) * interpolation;
        }

        private void rotZ(
                double interpolation,
                double value
        ) {
            final double current =
                    object.getTransform().rotation.z;

            object.getTransform().rotation.z =
                    current
                            + (
                            value - current
                    ) * interpolation;
        }
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