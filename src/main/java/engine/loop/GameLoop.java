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

    private static final System.Logger LOGGER = System.getLogger(GameEngine.class.getName());

    private final EngineContext context;
    private final Scene scene;
    private final GamePanel panel;
    private final FrameTiming frameTiming;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private Thread gameThread;

    public GameLoop(EngineContext context, GamePanel panel) {
        this.context = context;
        scene = context.scene;
        this.panel = panel;
        frameTiming = panel.getFrameTiming();
    }

    public void start() {
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
        long lastTime = System.nanoTime();
        long lastRepaintTime = lastTime;

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                final long now = System.nanoTime();
                final double elapsedSeconds = (now - lastTime) / 1_000_000_000.0;

                lastTime = now;
                frameTiming.addElapsed(elapsedSeconds);

                while (frameTiming.stepReady()) {
                    final double delta = frameTiming.fixedDeltaSeconds();

                    context.eventBus.publish(new TickEvent(TickEvent.Phase.PRE, delta));
                    fixedUpdate(delta);
                    context.eventBus.publish(new TickEvent(TickEvent.Phase.POST, delta));

                    frameTiming.consumeStep();
                }

                if (now - lastRepaintTime >= EngineSettings.MINIMUM_REPAINT_NANOSECONDS && panel.requestRepaint()) {
                    lastRepaintTime = now;
                }

                LockSupport.parkNanos(EngineSettings.IDLE_PARK_NANOSECONDS);
            }
        } catch (RuntimeException exception) {
            running.set(false);
            LOGGER.log(System.Logger.Level.ERROR, "Game loop terminated unexpectedly", exception);
        }
    }

    private void fixedUpdate(double delta) {
        scene.drainRootOperations();

        context.inputHandler.updatePerTick();
        context.playerController.updatePerTick(delta);

        final double strafeIntent = context.camera.dx;
        final double forwardIntent = context.camera.dz;
        final double intentSpeed = Math.sqrt(strafeIntent * strafeIntent + forwardIntent * forwardIntent);
        final boolean movingIntent = intentSpeed > 1.0e-9;

        context.camera.update(delta);

        synchronizePlayerBodyToCamera();

        context.playerBody.setModelVisible(context.camera.isThirdPerson());

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
        final Transform transform = context.playerBody.getTransform();

        transform.position.x = context.camera.x;
        transform.position.y = context.camera.y;
        transform.position.z = context.camera.z;
        transform.rotation.y = -context.camera.getViewYaw();
    }
}