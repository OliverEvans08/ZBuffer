package engine.animation;

import objects.GameObject;
import util.Vector3;

public final class Animator {

    public enum Mode { ABSOLUTE, RELATIVE }

    private final GameObject target;
    private AnimationClip clip;
    private double time;
    private double speed = 1.0;
    private boolean playing = false;
    private Mode mode = Mode.ABSOLUTE;

    private Vector3 basePos = new Vector3(0,0,0);
    private Vector3 baseRot = new Vector3(0,0,0);
    private Vector3 baseScl = new Vector3(1,1,1);

    public Animator(GameObject target) {
        this.target = target;
    }

    public Animator setMode(Mode mode) { this.mode = mode; return this; }
    public Animator setSpeed(double speed) { this.speed = speed; return this; }
    public boolean isPlaying() { return playing; }
    public double getTime() { return time; }
    public AnimationClip getClip() { return clip; }

    public Animator play(AnimationClip clip) {
        if (clip == null) return this;
        this.clip = clip;
        this.time = 0.0;
        this.playing = true;
        basePos = target.transform.position.copy();
        baseRot = target.transform.rotation.copy();
        baseScl = target.transform.scale.copy();
        return this;
    }

    public Animator stop() { this.playing = false; return this; }
    public Animator setTime(double t) { this.time = t; return this; }

    public void update(double dt) {
        if (!playing || clip == null) return;
        time += dt * speed;

        final boolean abs = mode == Mode.ABSOLUTE;

        // Position
        if (clip.has(AnimationClip.Channel.POS_X)) {
            double v = clip.sample(AnimationClip.Channel.POS_X, time);
            target.transform.position.x = abs ? v : basePos.x + v;
        } else if (abs) {
            target.transform.position.x = basePos.x;
        }
        if (clip.has(AnimationClip.Channel.POS_Y)) {
            double v = clip.sample(AnimationClip.Channel.POS_Y, time);
            target.transform.position.y = abs ? v : basePos.y + v;
        } else if (abs) {
            target.transform.position.y = basePos.y;
        }
        if (clip.has(AnimationClip.Channel.POS_Z)) {
            double v = clip.sample(AnimationClip.Channel.POS_Z, time);
            target.transform.position.z = abs ? v : basePos.z + v;
        } else if (abs) {
            target.transform.position.z = basePos.z;
        }

        // Rotation (same pattern)
        if (clip.has(AnimationClip.Channel.ROT_X)) {
            double v = clip.sample(AnimationClip.Channel.ROT_X, time);
            target.transform.rotation.x = abs ? v : baseRot.x + v;
        } else if (abs) target.transform.rotation.x = baseRot.x;
        if (clip.has(AnimationClip.Channel.ROT_Y)) {
            double v = clip.sample(AnimationClip.Channel.ROT_Y, time);
            target.transform.rotation.y = abs ? v : baseRot.y + v;
        } else if (abs) target.transform.rotation.y = baseRot.y;
        if (clip.has(AnimationClip.Channel.ROT_Z)) {
            double v = clip.sample(AnimationClip.Channel.ROT_Z, time);
            target.transform.rotation.z = abs ? v : baseRot.z + v;
        } else if (abs) target.transform.rotation.z = baseRot.z;

        // Scale
        if (clip.has(AnimationClip.Channel.SCALE_X)) {
            double v = clip.sample(AnimationClip.Channel.SCALE_X, time);
            target.transform.scale.x = abs ? v : baseScl.x * v;
        } else if (abs) target.transform.scale.x = baseScl.x;
        if (clip.has(AnimationClip.Channel.SCALE_Y)) {
            double v = clip.sample(AnimationClip.Channel.SCALE_Y, time);
            target.transform.scale.y = abs ? v : baseScl.y * v;
        } else if (abs) target.transform.scale.y = baseScl.y;
        if (clip.has(AnimationClip.Channel.SCALE_Z)) {
            double v = clip.sample(AnimationClip.Channel.SCALE_Z, time);
            target.transform.scale.z = abs ? v : baseScl.z * v;
        } else if (abs) target.transform.scale.z = baseScl.z;

        if (!clip.isLoop() && time >= clip.getDuration()) {
            time = clip.getDuration();
            playing = false;
        }
    }
}