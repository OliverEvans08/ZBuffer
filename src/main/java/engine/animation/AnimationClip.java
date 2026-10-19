package engine.animation;

import java.util.*;
import java.util.stream.Collectors;

public final class AnimationClip {

    public enum Channel {
        POS_X, POS_Y, POS_Z,
        ROT_X, ROT_Y, ROT_Z,
        SCALE_X, SCALE_Y, SCALE_Z
    }

    public static final class Keyframe {
        public final double t;
        public final double v;
        public Keyframe(double t, double v) { this.t = t; this.v = v; }
    }

    private final Map<Channel, List<Keyframe>> keys;
    private final boolean[] hasChannel = new boolean[Channel.values().length];
    private final double duration;
    private final boolean loop;

    private AnimationClip(Map<Channel, List<Keyframe>> keys, double duration, boolean loop) {
        this.keys = keys;
        this.duration = Math.max(1e-6, duration);
        this.loop = loop;
        for (Channel c : Channel.values()) {
            List<Keyframe> list = keys.get(c);
            hasChannel[c.ordinal()] = list != null && !list.isEmpty();
        }
    }

    public double getDuration() { return duration; }
    public boolean isLoop() { return loop; }
    public boolean has(Channel ch) { return hasChannel[ch.ordinal()]; }

    public double sample(Channel ch, double time) {
        if (!hasChannel[ch.ordinal()]) return Double.NaN;
        List<Keyframe> list = keys.get(ch);
        // (rest of binary-search logic unchanged — it is already efficient)
        double t = time;
        if (loop) {
            t %= duration;
            if (t < 0) t += duration;
        } else {
            if (t <= list.get(0).t) return list.get(0).v;
            if (t >= list.get(list.size()-1).t) return list.get(list.size()-1).v;
        }
        int lo = 0, hi = list.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double mt = list.get(mid).t;
            if (mt < t) lo = mid + 1;
            else hi = mid - 1;
        }
        int idx = Math.max(1, lo);
        Keyframe a = list.get(idx - 1);
        Keyframe b = list.get(idx < list.size() ? idx : list.size() - 1);
        if (a.t == b.t) return a.v;
        double u = (t - a.t) / (b.t - a.t);
        return a.v + (b.v - a.v) * u;
    }

    public static Builder builder(double durationSeconds) {
        return new Builder(durationSeconds);
    }

    public static final class Builder {
        private final Map<Channel, List<Keyframe>> keys = new EnumMap<>(Channel.class);
        private double duration;
        private boolean loop = true;

        public Builder(double durationSeconds) {
            this.duration = Math.max(1e-6, durationSeconds);
        }

        public Builder loop(boolean loop) { this.loop = loop; return this; }

        public Builder key(Channel c, double t, double v) {
            keys.computeIfAbsent(c, k -> new ArrayList<>()).add(new Keyframe(t, v));
            return this;
        }

        public Builder pos(double t, double x, double y, double z) {
            return key(Channel.POS_X, t, x).key(Channel.POS_Y, t, y).key(Channel.POS_Z, t, z);
        }
        public Builder rot(double t, double xRad, double yRad, double zRad) {
            return key(Channel.ROT_X, t, xRad).key(Channel.ROT_Y, t, yRad).key(Channel.ROT_Z, t, zRad);
        }
        public Builder scale(double t, double sx, double sy, double sz) {
            return key(Channel.SCALE_X, t, sx).key(Channel.SCALE_Y, t, sy).key(Channel.SCALE_Z, t, sz);
        }

        public AnimationClip build() {
            Map<Channel, List<Keyframe>> sorted = keys.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> {
                        List<Keyframe> l = e.getValue();
                        l.sort(Comparator.comparingDouble(k -> k.t));
                        List<Keyframe> c = new ArrayList<>(l.size());
                        for (Keyframe k : l) {
                            double tt = Math.min(Math.max(k.t, 0.0), duration);
                            c.add(new Keyframe(tt, k.v));
                        }
                        return c;
                    },
                    (a, b) -> a,
                    () -> new EnumMap<>(Channel.class)
            ));
            return new AnimationClip(sorted, duration, loop);
        }
    }
}