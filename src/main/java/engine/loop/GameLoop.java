package engine.loop;

import engine.EngineContext;
import engine.EngineSettings;
import engine.GameEngine;
import engine.event.events.TickEvent;
import engine.scene.Scene;
import engine.ui.GamePanel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import util.Transform;

public final class GameLoop implements Runnable {

    private static final System.Logger LOGGER =
            System.getLogger(GameEngine.class.getName());

    private final EngineContext context;
    private final Scene scene;
    private final GamePanel panel;
    private final FrameTiming frameTiming;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean entered = new AtomicBoolean();

    private volatile Thread gameThread;

    public GameLoop(
            EngineContext context,
            GamePanel panel
    ) {
        this.context = context;
        this.scene = context.scene;
        this.panel = panel;
        this.frameTiming = panel.getFrameTiming();
    }

    public synchronized void start() {
        if (
                gameThread != null
                        || entered.get()
                        || !running.get()
        ) {
            throw new IllegalStateException(
                    "Game loop already started or stopped"
            );
        }

        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    public void shutdown() {
        running.set(false);

        final Thread loop = gameThread;

        if (loop != null) {
            loop.interrupt();
        }

        if (loop != null && loop != Thread.currentThread()) {
            boolean interrupted = false;

            try {
                while (loop.isAlive()) {
                    try {
                        loop.join(250L);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void run() {
        if (!entered.compareAndSet(false, true)) {
            return;
        }

        gameThread = Thread.currentThread();

        long lastTime = System.nanoTime();
        long nextRepaint = lastTime;

        final long repaintInterval = Math.max(
                1L,
                EngineSettings.MINIMUM_REPAINT_NANOSECONDS
        );

        try {
            while (
                    running.get()
                            && !Thread.currentThread().isInterrupted()
            ) {
                long now = System.nanoTime();

                frameTiming.addElapsed(
                        (now - lastTime) / 1_000_000_000.0
                );

                lastTime = now;

                while (
                        running.get()
                                && !Thread.currentThread().isInterrupted()
                                && frameTiming.stepReady()
                ) {
                    double delta = frameTiming.fixedDeltaSeconds();

                    context.eventBus.publish(
                            new TickEvent(
                                    TickEvent.Phase.PRE,
                                    delta
                            )
                    );

                    fixedUpdate(delta);

                    context.eventBus.publish(
                            new TickEvent(
                                    TickEvent.Phase.POST,
                                    delta
                            )
                    );

                    frameTiming.consumeStep();
                }

                if (
                        !running.get()
                                || Thread.currentThread().isInterrupted()
                ) {
                    break;
                }

                now = System.nanoTime();

                if (now - nextRepaint >= 0L) {
                    panel.requestRepaint();

                    /*
                     * Advance even if Swing already has a repaint queued.
                     * Preserve cadence without queuing catch-up frames.
                     */
                    nextRepaint += repaintInterval;

                    if (now - nextRepaint >= 0L) {
                        nextRepaint = now + repaintInterval;
                    }
                }

                long afterWork = System.nanoTime();

                /*
                 * Account for time spent doing updates, because the clock
                 * accumulator was sampled before that work began.
                 */
                long untilTick =
                        frameTiming.nanosUntilNextStep()
                                - (afterWork - lastTime);

                long untilPaint = nextRepaint - afterWork;
                long sleep = Math.min(untilTick, untilPaint);

                if (sleep > 0L) {
                    LockSupport.parkNanos(this, sleep);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "Game loop terminated unexpectedly",
                    exception
            );
        } finally {
            running.set(false);
        }
    }

    private void fixedUpdate(double delta) {
        scene.drainRootOperations();

        context.inputHandler.updatePerTick();
        context.playerController.updatePerTick(delta);

        final double strafeIntent = context.camera.dx;
        final double forwardIntent = context.camera.dz;

        final double intentSpeed = Math.sqrt(
                strafeIntent * strafeIntent
                        + forwardIntent * forwardIntent
        );

        final boolean movingIntent = intentSpeed > 1.0e-9;

        context.camera.update(delta);

        synchronizePlayerBodyToCamera();

        context.playerBody.setModelVisible(
                context.camera.isThirdPerson()
        );

        context.inventorySystem.update(delta);

        context.playerBody.setMotionState(
                movingIntent,
                context.camera.onGround,
                context.camera.yVelocity,
                context.camera.flightMode,
                forwardIntent,
                strafeIntent,
                intentSpeed,
                context.camera.getViewPitch()
        );

        scene.runTwoPhaseUpdate(delta);
        context.soundEngine.tick();
    }

    private void synchronizePlayerBodyToCamera() {
        final Transform transform =
                context.playerBody.getTransform();

        transform.position.x = context.camera.x;
        transform.position.y = context.camera.y;
        transform.position.z = context.camera.z;

        transform.rotation.y = -context.camera.getViewYaw();
    }
}