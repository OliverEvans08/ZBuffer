package objects.dynamic;

import engine.animation.AnimationClip;
import engine.animation.Animator;
import engine.render.Material;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public class Body extends GameObject {

    // Rig parts (children)
    private final BoxPart torso;
    private final BoxPart head;
    private final BoxPart leftArm;
    private final BoxPart rightArm;
    private final BoxPart leftLeg;
    private final BoxPart rightLeg;

    // Animators (per-limb)
    private final Animator aTorso;
    private final Animator aHead;
    private final Animator aLA;
    private final Animator aRA;
    private final Animator aLL;
    private final Animator aRL;

    // Clips
    private final AnimationClip walkArmClip;
    private final AnimationClip walkLegClip;
    private final AnimationClip jumpArmClip;
    private final AnimationClip jumpLegClip;

    // External motion state (fed by GameEngine each tick)
    private boolean movingIntent;
    private boolean onGround;
    private boolean prevOnGround = true;
    private boolean flightMode;
    private double yVel;

    // Internal state
    private boolean walking = false;

    public Body(double width, double height) {
        setFull(false); // NOT a collision obstacle

        // Proportions (based on total height)
        double totalH = Math.max(0.5, height);
        double legLen = totalH * 0.50;
        double torsoH = totalH * 0.33;
        double headH  = totalH - legLen - torsoH;

        double torsoW = width * 0.72;
        double torsoD = width * 0.38;

        double headW = width * 0.55;
        double headD = width * 0.55;

        double legW = width * 0.28;
        double legD = width * 0.28;

        double armW = width * 0.22;
        double armD = width * 0.22;
        double armLen = torsoH * 0.95;

        double hipY = legLen;

        // Build parts (anchors matter for joint rotation)
        torso = new BoxPart(torsoW, torsoH, torsoD, BoxPart.Anchor.BOTTOM);
        torso.setMaterial(Material.solid(new Color(90, 200, 255)));
        torso.getTransform().position = new Vector3(0, hipY, 0);

        head = new BoxPart(headW, headH, headD, BoxPart.Anchor.BOTTOM);
        head.setMaterial(Material.solid(new Color(240, 240, 240)));
        head.getTransform().position = new Vector3(0, hipY + torsoH, 0);

        // Legs pivot at hip, extend downward (TOP anchor => y in [-len..0])
        double legX = torsoW * 0.28;
        leftLeg = new BoxPart(legW, legLen, legD, BoxPart.Anchor.TOP);
        leftLeg.setMaterial(Material.solid(new Color(60, 80, 110)));
        leftLeg.getTransform().position = new Vector3(-legX, hipY, 0);

        rightLeg = new BoxPart(legW, legLen, legD, BoxPart.Anchor.TOP);
        rightLeg.setMaterial(Material.solid(new Color(60, 80, 110)));
        rightLeg.getTransform().position = new Vector3(+legX, hipY, 0);

        // Arms pivot at shoulder, extend downward
        double shoulderY = hipY + torsoH * 0.84;
        double armX = (torsoW * 0.55) + (armW * 0.60);

        leftArm = new BoxPart(armW, armLen, armD, BoxPart.Anchor.TOP);
        leftArm.setMaterial(Material.solid(new Color(255, 160, 90)));
        leftArm.getTransform().position = new Vector3(-armX, shoulderY, 0);

        rightArm = new BoxPart(armW, armLen, armD, BoxPart.Anchor.TOP);
        rightArm.setMaterial(Material.solid(new Color(255, 160, 90)));
        rightArm.getTransform().position = new Vector3(+armX, shoulderY, 0);

        // Attach to rig root
        addChild(torso);
        addChild(head);
        addChild(leftLeg);
        addChild(rightLeg);
        addChild(leftArm);
        addChild(rightArm);

        // Animators (RELATIVE mode so base pose stays the rig default)
        aTorso = torso.animate().setMode(Animator.Mode.RELATIVE);
        aHead  = head.animate().setMode(Animator.Mode.RELATIVE);
        aLA    = leftArm.animate().setMode(Animator.Mode.RELATIVE);
        aRA    = rightArm.animate().setMode(Animator.Mode.RELATIVE);
        aLL    = leftLeg.animate().setMode(Animator.Mode.RELATIVE);
        aRL    = rightLeg.animate().setMode(Animator.Mode.RELATIVE);

        // Clips
        walkLegClip = buildWalkClip(0.70, 0.85); // bigger swing
        walkArmClip = buildWalkClip(0.70, 0.65); // slightly smaller swing

        // Jump pose: quickly move into a “jump” pose and HOLD it mid-air (non-loop)
        jumpArmClip = buildPoseClip(0.18, -1.05); // arms forward/up-ish
        jumpLegClip = buildPoseClip(0.18, +0.55); // legs tuck

        // Default rest pose
        resetPoseImmediate();
    }

    // Motion state fed by GameEngine
    public void setMotionState(boolean movingIntent, boolean onGround, double yVelocity, boolean flightMode) {
        this.movingIntent = movingIntent;
        this.onGround = onGround;
        this.yVel = yVelocity;
        this.flightMode = flightMode;
    }

    // Root has no geometry (children do)
    @Override public double[][] getVertices() { return new double[0][0]; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return null; }

    @Override
    public void update(double delta) {
        // If flying, keep neutral pose (no walk/jump)
        if (flightMode) {
            if (walking) {
                stopAllAnimators();
                resetPoseImmediate();
                walking = false;
            }
            prevOnGround = onGround;
            return;
        }

        // TAKEOFF (jump initiated): only when leaving ground with upward velocity
        if (prevOnGround && !onGround && yVel > 0.01) {
            walking = false;
            stopAllAnimators();
            resetPoseImmediate();
            startJumpPose();
        }

        // LANDING: reset pose (then potentially start walk)
        if (!prevOnGround && onGround) {
            stopAllAnimators();
            resetPoseImmediate();
            walking = false;
        }

        // Ground locomotion
        if (onGround) {
            if (movingIntent) {
                if (!walking) startWalk();
            } else {
                if (walking) {
                    stopAllAnimators();
                    resetPoseImmediate();
                    walking = false;
                }
            }
        }

        prevOnGround = onGround;
    }

    private void startWalk() {
        stopAllAnimators();
        resetPoseImmediate();

        double d = walkLegClip.getDuration();
        double half = d * 0.5;

        // Legs opposite phase
        aRL.play(walkLegClip).setTime(0.0);   aRL.update(0.0);
        aLL.play(walkLegClip).setTime(half);  aLL.update(0.0);

        // Arms opposite to legs
        aLA.play(walkArmClip).setTime(0.0);   aLA.update(0.0);
        aRA.play(walkArmClip).setTime(half);  aRA.update(0.0);

        // Tiny torso counter-motion (subtle)
        AnimationClip torsoClip = buildWalkTorsoBobClip(walkLegClip.getDuration(), 0.06);
        aTorso.play(torsoClip).setTime(0.0); aTorso.update(0.0);

        walking = true;
    }

    private void startJumpPose() {
        // Arms forward/up-ish, legs tuck
        aLA.play(jumpArmClip).setTime(0.0); aLA.update(0.0);
        aRA.play(jumpArmClip).setTime(0.0); aRA.update(0.0);

        aLL.play(jumpLegClip).setTime(0.0); aLL.update(0.0);
        aRL.play(jumpLegClip).setTime(0.0); aRL.update(0.0);

        // Slight torso lean
        AnimationClip lean = AnimationClip.builder(0.18)
                .loop(false)
                .key(AnimationClip.Channel.ROT_X, 0.0, 0.0)
                .key(AnimationClip.Channel.ROT_X, 0.18, 0.20)
                .build();
        aTorso.play(lean).setTime(0.0); aTorso.update(0.0);
    }

    private void stopAllAnimators() {
        aTorso.stop();
        aHead.stop();
        aLA.stop(); aRA.stop();
        aLL.stop(); aRL.stop();
    }

    private void resetPoseImmediate() {
        // Head/torso neutral
        torso.getTransform().rotation.x = 0; torso.getTransform().rotation.y = 0; torso.getTransform().rotation.z = 0;
        head.getTransform().rotation.x  = 0; head.getTransform().rotation.y  = 0; head.getTransform().rotation.z  = 0;

        // Limbs neutral
        leftArm.getTransform().rotation.x = 0; leftArm.getTransform().rotation.y = 0; leftArm.getTransform().rotation.z = 0;
        rightArm.getTransform().rotation.x = 0; rightArm.getTransform().rotation.y = 0; rightArm.getTransform().rotation.z = 0;
        leftLeg.getTransform().rotation.x = 0; leftLeg.getTransform().rotation.y = 0; leftLeg.getTransform().rotation.z = 0;
        rightLeg.getTransform().rotation.x = 0; rightLeg.getTransform().rotation.y = 0; rightLeg.getTransform().rotation.z = 0;
    }

    private static AnimationClip buildWalkClip(double duration, double swingRad) {
        return AnimationClip.builder(duration)
                .loop(true)
                .key(AnimationClip.Channel.ROT_X, 0.0, swingRad)
                .key(AnimationClip.Channel.ROT_X, duration * 0.5, -swingRad)
                .key(AnimationClip.Channel.ROT_X, duration, swingRad)
                .build();
    }

    private static AnimationClip buildWalkTorsoBobClip(double duration, double bobRad) {
        // tiny roll/lean to avoid “robot plank”
        return AnimationClip.builder(duration)
                .loop(true)
                .key(AnimationClip.Channel.ROT_Z, 0.0, -bobRad)
                .key(AnimationClip.Channel.ROT_Z, duration * 0.5, bobRad)
                .key(AnimationClip.Channel.ROT_Z, duration, -bobRad)
                .build();
    }

    private static AnimationClip buildPoseClip(double duration, double finalRotX) {
        return AnimationClip.builder(duration)
                .loop(false)
                .key(AnimationClip.Channel.ROT_X, 0.0, 0.0)
                .key(AnimationClip.Channel.ROT_X, duration, finalRotX)
                .build();
    }
}
