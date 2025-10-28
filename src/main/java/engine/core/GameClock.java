package engine.core;

/**
 * Fixed-step accumulator clock.
 * Add elapsed time each frame; consume steps at fixed delta when ready.
 */
public class GameClock {
    private final double fixedDeltaSeconds;
    private double accumulator = 0.0;

    public GameClock(double fixedDeltaSeconds) {
        this.fixedDeltaSeconds = fixedDeltaSeconds;
    }

    public void addElapsed(double seconds) {
        // Clamp to avoid spiral of death if frame stalls badly
        double capped = Math.min(seconds, fixedDeltaSeconds * 5.0);
        accumulator += capped;
    }

    public boolean stepReady() {
        return accumulator >= fixedDeltaSeconds;
    }

    public void consumeStep() {
        accumulator -= fixedDeltaSeconds;
    }

    public double fixedDeltaSeconds() {
        return fixedDeltaSeconds;
    }
}
