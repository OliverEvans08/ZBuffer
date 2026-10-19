package engine.loop;

import engine.core.GameClock;

/**
 * Simulation methods belong to the game-loop thread.
 * countFrame() belongs to the Swing event-dispatch thread.
 */
public final class FrameTiming {

    private final GameClock clock = new GameClock(1.0 / 60.0);

    private long framesPerSecondLastTime = System.nanoTime();
    private int framesSinceFpsUpdate;
    private int framesPerSecond;

    public void addElapsed(double elapsedSeconds) {
        clock.addElapsed(elapsedSeconds);
    }

    public boolean stepReady() {
        return clock.stepReady();
    }

    public double fixedDeltaSeconds() {
        return clock.fixedDeltaSeconds();
    }

    public void consumeStep() {
        clock.consumeStep();
    }

    public long nanosUntilNextStep() {
        if (clock.stepReady()) {
            return 0L;
        }

        return Math.max(
                1L,
                (long) Math.ceil(
                        (1.0 - clock.interpolationAlpha())
                                * clock.fixedDeltaSeconds()
                                * 1_000_000_000.0
                )
        );
    }

    public int countFrame() {
        long now = System.nanoTime();
        long elapsed = now - framesPerSecondLastTime;

        framesSinceFpsUpdate++;

        if (elapsed >= 1_000_000_000L) {
            framesPerSecond = (int) Math.round(
                    framesSinceFpsUpdate
                            * 1_000_000_000.0
                            / elapsed
            );

            framesSinceFpsUpdate = 0;
            framesPerSecondLastTime = now;
        }

        return framesPerSecond;
    }
}