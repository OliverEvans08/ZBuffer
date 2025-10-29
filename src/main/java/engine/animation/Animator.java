// File: src/main/java/engine/animation/Animator.java
package engine.animation;

import engine.animation.AnimationClip.Channel;
import objects.GameObject;
import util.Vector3;

/**
 * Animator applies an AnimationClip to a GameObject's local Transform.
 * Mode.ABSOLUTE: keys REPLACE base values (missing keys keep base).
 * Mode.RELATIVE: keys OFFSET base (pos/rot add, scale multiply).
 */
public final class Animator {

    public enum Mode { ABSOLUTE, RELATIVE }

    private final GameObject target;

    private AnimationClip clip;
    private double time;
    private double speed = 1.0;
    private boolean playing = false;
    private Mode mode = Mode.ABSOLUTE;

    // Snapshot of base (bind) transform on play()
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
        // Bind snapshot
        basePos = target.transform.position.copy();
        baseRot = target.transform.rotation.copy();
        baseScl = target.transform.scale.copy();
        return this;
    }

    public Animator stop() {
        this.playing = false;
        return this;
    }

    public Animator setTime(double t) {
        this.time = t;
        return this;
    }

    public void update(double dt) {
        if (!playing || clip == null) return;

        time += dt * speed;

        // Sample per channel
        double px = clip.sample(Channel.POS_X, time);
        double py = clip.sample(Channel.POS_Y, time);
        double pz = clip.sample(Channel.POS_Z, time);

        double rx = clip.sample(Channel.ROT_X, time);
        double ry = clip.sample(Channel.ROT_Y, time);
        double rz = clip.sample(Channel.ROT_Z, time);

        double sx = clip.sample(Channel.SCALE_X, time);
        double sy = clip.sample(Channel.SCALE_Y, time);
        double sz = clip.sample(Channel.SCALE_Z, time);

        switch (mode) {
            case ABSOLUTE -> {
                // Missing channels => keep base
                target.transform.position.x = Double.isNaN(px) ? basePos.x : px;
                target.transform.position.y = Double.isNaN(py) ? basePos.y : py;
                target.transform.position.z = Double.isNaN(pz) ? basePos.z : pz;

                target.transform.rotation.x = Double.isNaN(rx) ? baseRot.x : rx;
                target.transform.rotation.y = Double.isNaN(ry) ? baseRot.y : ry;
                target.transform.rotation.z = Double.isNaN(rz) ? baseRot.z : rz;

                target.transform.scale.x = Double.isNaN(sx) ? baseScl.x : sx;
                target.transform.scale.y = Double.isNaN(sy) ? baseScl.y : sy;
                target.transform.scale.z = Double.isNaN(sz) ? baseScl.z : sz;
            }
            case RELATIVE -> {
                // Missing channels => 0 offset for pos/rot, 1x for scale
                target.transform.position.x = basePos.x + (Double.isNaN(px) ? 0.0 : px);
                target.transform.position.y = basePos.y + (Double.isNaN(py) ? 0.0 : py);
                target.transform.position.z = basePos.z + (Double.isNaN(pz) ? 0.0 : pz);

                target.transform.rotation.x = baseRot.x + (Double.isNaN(rx) ? 0.0 : rx);
                target.transform.rotation.y = baseRot.y + (Double.isNaN(ry) ? 0.0 : ry);
                target.transform.rotation.z = baseRot.z + (Double.isNaN(rz) ? 0.0 : rz);

                target.transform.scale.x = baseScl.x * (Double.isNaN(sx) ? 1.0 : sx);
                target.transform.scale.y = baseScl.y * (Double.isNaN(sy) ? 1.0 : sy);
                target.transform.scale.z = baseScl.z * (Double.isNaN(sz) ? 1.0 : sz);
            }
        }

        // If non-looping and time exceeded, clamp and stop
        if (!clip.isLoop() && time >= clip.getDuration()) {
            time = clip.getDuration();
            playing = false;
        }
    }
}
