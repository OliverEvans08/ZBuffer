package objects.dynamic;

import engine.render.Material;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

/**
 * Player body rig with “rounded” parts + procedural locomotion.
 *
 * Fixes:
 *  - Arms intersecting torso: shoulders placed outside torso radius + base outward splay rotations.
 *  - Animation “super slow / hardly moves”: if GameEngine doesn't provide intent vectors yet,
 *    we derive forward/strafe/speed from this object's world velocity each tick.
 *  - Human-like lean: moving forward leans forward; moving backward stays mostly upright (at most a slight forward lean),
 *    never leaning back.
 */
public class Body extends GameObject {

    // Rig parts
    private final BoxPart pelvis;
    private final CapsulePart torso;
    private final CapsulePart neck;
    private final SpherePart head;

    private final CapsulePart lUpperArm, lForeArm;
    private final SpherePart  lHand;
    private final CapsulePart rUpperArm, rForeArm;
    private final SpherePart  rHand;

    private final CapsulePart lThigh, lShin;
    private final BoxPart     lFoot;
    private final CapsulePart rThigh, rShin;
    private final BoxPart     rFoot;

    // Base transforms (so animation offsets never “drift”)
    private final PoseBase pPelvis, pTorso, pNeck, pHead;
    private final PoseBase pLUA, pLFA, pLH, pRUA, pRFA, pRH;
    private final PoseBase pLT, pLS, pLF, pRT, pRS, pRF;

    // Motion state (fed from GameEngine)
    private boolean movingIntent;
    private boolean onGround;
    private boolean prevOnGround = true;
    private boolean flightMode;
    private double yVel;

    // Optional external intent (if GameEngine is updated to pass it)
    private double intentForward;
    private double intentStrafe;
    private double intentSpeed;
    private double viewPitch;

    // Derived intent from velocity (works even if GameEngine calls old setMotionState)
    private boolean velInit = false;
    private double lastX, lastZ;

    private double vFwdSm = 0.0;
    private double vStrSm = 0.0;
    private double vSpdSm = 0.0;
    private double pitchSm = 0.0;

    // Animation clocks
    private double t = 0.0;
    private double gait = 0.0;
    private double landTimer = 0.0;

    public Body(double width, double height) {
        setFull(false);

        final double H = Math.max(1.2, height);
        final double W = Math.max(0.35, width);

        // --- proportions (tuned to feel “human-ish”) ---
        double legH    = H * 0.52;
        double pelvisH = H * 0.10;
        double torsoH  = H * 0.28;
        double neckH   = H * 0.05;

        double headR   = H * 0.10;

        double thighL  = legH * 0.48;
        double shinL   = legH * 0.40;
        double footH   = Math.max(0.06, legH - thighL - shinL);

        double armH    = torsoH * 0.92;
        double uArmL   = armH * 0.52;
        double fArmL   = armH * 0.48;
        double handR   = W * 0.12;

        double legR    = W * 0.12;
        double armR    = W * 0.10;
        double torsoR  = W * 0.22;
        double neckR   = W * 0.11;

        // Materials
        var skin  = Material.solid(new Color(255, 196, 160)).setAmbient(0.22).setDiffuse(0.90);
        var shirt = Material.solid(new Color(70, 170, 255)).setAmbient(0.22).setDiffuse(0.90);
        var pants = Material.solid(new Color(50, 70, 95)).setAmbient(0.22).setDiffuse(0.90);
        var shoes = Material.solid(new Color(35, 35, 35)).setAmbient(0.20).setDiffuse(0.95);

        // Root is at feet (y=0)
        double legTotal = thighL + shinL + footH;

        // Pelvis
        pelvis = new BoxPart(W * 0.55, pelvisH, W * 0.30, BoxPart.Anchor.BOTTOM);
        pelvis.setMaterial(pants);
        pelvis.getTransform().position = new Vector3(0, legTotal, 0);

        // Torso (capsule = rounded) — place at top of pelvis (avoid overlap)
        torso = new CapsulePart(torsoR, torsoH, 12, 4, 2, CapsulePart.Anchor.BOTTOM);
        torso.setMaterial(shirt);
        torso.getTransform().position = new Vector3(0, pelvisH * 0.98, 0);

        // Neck — near top of torso
        neck = new CapsulePart(neckR, neckH, 12, 3, 1, CapsulePart.Anchor.BOTTOM);
        neck.setMaterial(skin);
        neck.getTransform().position = new Vector3(0, torsoH * 0.985, 0);

        // Head (sphere) — above neck
        head = new SpherePart(headR, 16, 10, SpherePart.Anchor.BOTTOM);
        head.setMaterial(skin);
        head.getTransform().position = new Vector3(0, neckH * 0.98, 0);

        // Arms (shoulders outside torso radius)
        double shoulderY = torsoH * 0.82;
        double shoulderX = torsoR + armR * 1.75;
        double shoulderZ = torsoR * 0.16;

        lUpperArm = new CapsulePart(armR, uArmL, 12, 3, 2, CapsulePart.Anchor.TOP);
        lUpperArm.setMaterial(skin);
        lUpperArm.getTransform().position = new Vector3(-shoulderX, shoulderY, shoulderZ);

        lForeArm = new CapsulePart(armR * 0.92, fArmL, 12, 3, 2, CapsulePart.Anchor.TOP);
        lForeArm.setMaterial(skin);
        lForeArm.getTransform().position = new Vector3(0, -uArmL, 0);

        lHand = new SpherePart(handR, 14, 8, SpherePart.Anchor.TOP);
        lHand.setMaterial(skin);
        lHand.getTransform().position = new Vector3(0, -fArmL, 0);

        rUpperArm = new CapsulePart(armR, uArmL, 12, 3, 2, CapsulePart.Anchor.TOP);
        rUpperArm.setMaterial(skin);
        rUpperArm.getTransform().position = new Vector3(+shoulderX, shoulderY, shoulderZ);

        rForeArm = new CapsulePart(armR * 0.92, fArmL, 12, 3, 2, CapsulePart.Anchor.TOP);
        rForeArm.setMaterial(skin);
        rForeArm.getTransform().position = new Vector3(0, -uArmL, 0);

        rHand = new SpherePart(handR, 14, 8, SpherePart.Anchor.TOP);
        rHand.setMaterial(skin);
        rHand.getTransform().position = new Vector3(0, -fArmL, 0);

        // Base arm splay so they sit OUTWARD from the torso (fixed sign)
        // If your engine's handedness differs, swap these signs.
        lUpperArm.getTransform().rotation.z = -0.40; // outward
        rUpperArm.getTransform().rotation.z = +0.40; // outward
        lUpperArm.getTransform().rotation.y = +0.10;
        rUpperArm.getTransform().rotation.y = -0.10;

        // Slight natural bend so hands don't clip torso at rest
        lForeArm.getTransform().rotation.x = 0.12;
        rForeArm.getTransform().rotation.x = 0.12;
        lHand.getTransform().rotation.x = 0.05;
        rHand.getTransform().rotation.x = 0.05;

        // Legs
        double hipY = pelvisH * 0.10;
        double hipX = W * 0.18;

        lThigh = new CapsulePart(legR, thighL, 12, 3, 2, CapsulePart.Anchor.TOP);
        lThigh.setMaterial(pants);
        lThigh.getTransform().position = new Vector3(-hipX, hipY, 0);

        lShin = new CapsulePart(legR * 0.92, shinL, 12, 3, 2, CapsulePart.Anchor.TOP);
        lShin.setMaterial(pants);
        lShin.getTransform().position = new Vector3(0, -thighL, 0);

        lFoot = new BoxPart(W * 0.22, footH, W * 0.38, BoxPart.Anchor.TOP);
        lFoot.setMaterial(shoes);
        lFoot.getTransform().position = new Vector3(0, -shinL, W * 0.10);

        rThigh = new CapsulePart(legR, thighL, 12, 3, 2, CapsulePart.Anchor.TOP);
        rThigh.setMaterial(pants);
        rThigh.getTransform().position = new Vector3(+hipX, hipY, 0);

        rShin = new CapsulePart(legR * 0.92, shinL, 12, 3, 2, CapsulePart.Anchor.TOP);
        rShin.setMaterial(pants);
        rShin.getTransform().position = new Vector3(0, -thighL, 0);

        rFoot = new BoxPart(W * 0.22, footH, W * 0.38, BoxPart.Anchor.TOP);
        rFoot.setMaterial(shoes);
        rFoot.getTransform().position = new Vector3(0, -shinL, W * 0.10);

        // Build hierarchy (real joints)
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

        // Cache base pose
        pPelvis = new PoseBase(pelvis);
        pTorso  = new PoseBase(torso);
        pNeck   = new PoseBase(neck);
        pHead   = new PoseBase(head);

        pLUA = new PoseBase(lUpperArm);
        pLFA = new PoseBase(lForeArm);
        pLH  = new PoseBase(lHand);

        pRUA = new PoseBase(rUpperArm);
        pRFA = new PoseBase(rForeArm);
        pRH  = new PoseBase(rHand);

        pLT = new PoseBase(lThigh);
        pLS = new PoseBase(lShin);
        pLF = new PoseBase(lFoot);

        pRT = new PoseBase(rThigh);
        pRS = new PoseBase(rShin);
        pRF = new PoseBase(rFoot);
    }

    // NEW signature used by GameEngine (kept minimal but enough for realistic motion)
    public void setMotionState(boolean movingIntent, boolean onGround, double yVelocity, boolean flightMode,
                               double intentForward, double intentStrafe, double intentSpeed, double viewPitch) {
        this.movingIntent = movingIntent;
        this.onGround = onGround;
        this.yVel = yVelocity;
        this.flightMode = flightMode;
        this.intentForward = intentForward;
        this.intentStrafe = intentStrafe;
        this.intentSpeed = intentSpeed;
        this.viewPitch = viewPitch;
    }

    // Keep old call sites safe
    public void setMotionState(boolean movingIntent, boolean onGround, double yVelocity, boolean flightMode) {
        // NOTE: old call sites don't provide direction, so we will derive it from velocity in update().
        setMotionState(movingIntent, onGround, yVelocity, flightMode, 0, 0, 0, 0);
    }

    @Override public double[][] getVertices() { return new double[0][0]; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return null; }

    @Override
    public void update(double dt) {
        t += dt;

        // Landing detect
        if (!prevOnGround && onGround) {
            landTimer = 0.12;
        }
        prevOnGround = onGround;
        if (landTimer > 0.0) landTimer = Math.max(0.0, landTimer - dt);

        // --- derive intent from velocity (fixes "slow / hardly moves" when engine doesn't pass intent) ---
        double fwd = intentForward;
        double str = intentStrafe;
        double spd = intentSpeed;

        boolean hasDirectionalIntent = (Math.abs(fwd) + Math.abs(str)) > 1e-4;

        double px = transform.position.x;
        double pz = transform.position.z;

        if (!velInit) {
            velInit = true;
            lastX = px;
            lastZ = pz;
        }

        double vFwd = 0.0, vStr = 0.0, vSpd = 0.0;

        if (dt > 1e-6) {
            double vx = (px - lastX) / dt;
            double vz = (pz - lastZ) / dt;

            double yaw = transform.rotation.y;
            double sy = Math.sin(yaw);
            double cy = Math.cos(yaw);

            // local +Z forward => (sy, cy), local +X right => (cy, -sy)
            double fwdX = sy, fwdZ = cy;
            double rightX = cy, rightZ = -sy;

            vFwd = vx * fwdX + vz * fwdZ;
            vStr = vx * rightX + vz * rightZ;
            vSpd = Math.sqrt(vFwd * vFwd + vStr * vStr);
        }

        lastX = px;
        lastZ = pz;

        if (!hasDirectionalIntent) {
            fwd = vFwd;
            str = vStr;
            spd = vSpd;
        } else {
            if (spd <= 1e-6) spd = Math.sqrt(fwd * fwd + str * str);
        }

        // Smooth the intent a bit for stable animation
        double intentA = 1.0 - Math.exp(-dt * 10.0);
        vFwdSm += (fwd - vFwdSm) * intentA;
        vStrSm += (str - vStrSm) * intentA;
        vSpdSm += (spd - vSpdSm) * intentA;

        double pitchA = 1.0 - Math.exp(-dt * 12.0);
        pitchSm += (viewPitch - pitchSm) * pitchA;

        // Main smoothing factor
        double a = 1.0 - Math.exp(-dt * 22.0);

        // Normalize intent
        double speed01 = clamp01(vSpdSm / 5.5);
        double fwd01 = (vSpdSm > 1e-6) ? (vFwdSm / vSpdSm) : 0.0;
        double str01 = (vSpdSm > 1e-6) ? (vStrSm / vSpdSm) : 0.0;

        // Update gait phase only while grounded & moving
        if (!flightMode && onGround && speed01 > 0.02) {
            double freq = vSpdSm / 1.35;
            freq = clamp(freq, 1.5, 3.8);
            gait += dt * freq * (Math.PI * 2.0);
        } else {
            gait += dt * 1.0;
        }

        double s = Math.sin(gait);
        double c = Math.cos(gait);

        // Breathing / idle micro-movement
        double breath = Math.sin(t * 1.4) * 0.035;
        double idleSway = Math.sin(t * 0.9) * 0.025;

        // Landing squash
        double landK = (landTimer > 0.0) ? (landTimer / 0.12) : 0.0;
        landK = landK * landK;

        // Torso/pelvis motion
        double pelvisBob = (onGround ? Math.abs(s) : 0.0) * (0.030 * speed01);
        double torsoBob  = (onGround ? Math.abs(s) : 0.0) * (0.060 * speed01);

        // Human-ish: forward gait leans forward; backward gait stays mostly upright (at most slight forward lean).
        double fwdComp = Math.max(0.0, fwd01);
        double backComp = Math.max(0.0, -fwd01);

        double forwardLean  = fwdComp  * (0.26 * speed01);
        double backwardLean = backComp * (0.08 * speed01); // small forward lean, not backward

        double torsoLeanF = -(forwardLean + backwardLean);
        double torsoLeanS = -str01 * (0.20 * speed01);

        boolean inAir = (!flightMode && !onGround);
        double fall01 = clamp01((-yVel) / 18.0);
        double jump01 = clamp01(( yVel) / 12.0);

        // Arm/leg amplitudes scale with speed (walk -> run)
        double hipSwing  = 0.30 + 0.55 * speed01;
        double kneeBend  = 0.35 + 0.95 * speed01;
        double armSwing  = 0.25 + 0.70 * speed01;
        double elbowBend = 0.15 + 0.55 * speed01;

        // Phase offsets
        double sL = Math.sin(gait);
        double sR = Math.sin(gait + Math.PI);

        // Legs: bend on lift
        double kneeLiftL = Math.max(0.0, -sL);
        double kneeLiftR = Math.max(0.0, -sR);

        double hipLx = sL * hipSwing;
        double hipRx = sR * hipSwing;

        double kneeLx = kneeLiftL * kneeBend;
        double kneeRx = kneeLiftR * kneeBend;

        double ankleLx = -Math.max(0.0, sL) * (0.40 * speed01);
        double ankleRx = -Math.max(0.0, sR) * (0.40 * speed01);

        // Arms opposite legs
        double armLx = -sL * armSwing;
        double armRx = -sR * armSwing;

        double elbowL = Math.max(0.0, sL) * elbowBend;
        double elbowR = Math.max(0.0, sR) * elbowBend;

        // Backwards movement: soften
        if (fwd01 < -0.2) {
            hipLx *= 0.75; hipRx *= 0.75;
            armLx *= 0.65; armRx *= 0.65;
        }

        // Air pose override
        if (flightMode) {
            hipLx = hipRx = 0.10;
            kneeLx = kneeRx = 0.25;
            ankleLx = ankleRx = 0.0;

            armLx = armRx = -0.35;
            elbowL = elbowR = 0.25;
        } else if (inAir) {
            double tuck = 0.55 + 0.35 * fall01;
            hipLx = hipRx = 0.25;
            kneeLx = kneeRx = tuck;
            ankleLx = ankleRx = 0.10;

            armLx = armRx = -0.55 + 0.20 * jump01;
            elbowL = elbowR = 0.25 + 0.25 * fall01;
        }

        // --- Apply base pose + offsets (smoothed) ---
        // Pelvis
        pPelvis.posY(a, pPelvis.by + pelvisBob - landK * 0.05);
        pPelvis.rotX(a, -torsoLeanF * 0.35 - landK * 0.10);
        pPelvis.rotZ(a, idleSway * 0.50 + torsoLeanS * 0.55);

        // Torso
        pTorso.posY(a, pTorso.by + torsoBob - landK * 0.08);
        pTorso.rotX(a, breath - torsoLeanF - landK * 0.25);
        pTorso.rotZ(a, idleSway + torsoLeanS + (onGround ? c * (0.06 * speed01) : 0.0));
        pTorso.rotY(a, -str01 * (0.10 * speed01));

        // Neck + head: follow pitch + step bob
        double pitchFollow = clamp(pitchSm * 0.65, -0.65, 0.65);
        pNeck.rotX(a, -pitchFollow * 0.35);
        pHead.rotX(a, pitchFollow + (onGround ? Math.abs(s) * (0.09 * speed01) : 0.0));
        pHead.rotZ(a, (onGround ? c * (0.06 * speed01) : 0.0));
        pHead.rotY(a, -str01 * 0.10);

        // Arms
        pLUA.rotX(a, armLx);
        pRUA.rotX(a, armRx);

        pLFA.rotX(a, 0.12 + elbowL);
        pRFA.rotX(a, 0.12 + elbowR);

        // tiny hand “settle”
        pLH.rotX(a, 0.06 + elbowL * 0.15);
        pRH.rotX(a, 0.06 + elbowR * 0.15);

        // Legs
        pLT.rotX(a, hipLx);
        pRT.rotX(a, hipRx);

        pLS.rotX(a, kneeLx);
        pRS.rotX(a, kneeRx);

        pLF.rotX(a, ankleLx);
        pRF.rotX(a, ankleRx);
    }

    // -------------------------------------------------------
    // Small helper that keeps a “base pose” and applies
    // smoothed offsets without allocations every frame.
    // -------------------------------------------------------
    private static final class PoseBase {
        final GameObject g;

        final double bx, by, bz;
        final double brx, bry, brz;

        PoseBase(GameObject g) {
            this.g = g;
            this.bx = g.getTransform().position.x;
            this.by = g.getTransform().position.y;
            this.bz = g.getTransform().position.z;
            this.brx = g.getTransform().rotation.x;
            this.bry = g.getTransform().rotation.y;
            this.brz = g.getTransform().rotation.z;
        }

        void posY(double a, double targetAbsY) {
            var p = g.getTransform().position;
            p.y += (targetAbsY - p.y) * a;
        }

        void rotX(double a, double off) {
            var r = g.getTransform().rotation;
            double target = brx + off;
            r.x += (target - r.x) * a;
        }

        void rotY(double a, double off) {
            var r = g.getTransform().rotation;
            double target = bry + off;
            r.y += (target - r.y) * a;
        }

        void rotZ(double a, double off) {
            var r = g.getTransform().rotation;
            double target = brz + off;
            r.z += (target - r.z) * a;
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
