package mods.car;

import engine.EngineContext;
import engine.camera.Camera;
import engine.camera.CameraSettings;
import engine.event.EventBus;
import engine.event.events.MouseLookEvent;
import engine.event.events.TickEvent;
import engine.systems.PlayerController;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public final class CarMod implements AutoCloseable {

    private static final int ENTER_EXIT = 1;
    private static final int RESET = 2;
    private static final int CAMERA = 4;
    private static final int SUMMON = 8;
    private static final int FSD = 16;
    private static final int ZOOM_IN = 32;
    private static final int ZOOM_OUT = 64;
    private static final int VIEW_FSD = 128;

    private static final Font FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 13);

    private final EngineContext context;
    private final CarVehicle car;
    private final CarPerception perception = new CarPerception();
    private final CarFSD fsd = new CarFSD();
    private final CarNavigation navigation = new CarNavigation();

    private final CarCollisionQueries collisionQueries =
            new CarCollisionQueries();

    private final AtomicIntegerArray keys = new AtomicIntegerArray(512);
    private final AtomicInteger commands = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile CarFsdWindow fsdWindow;
    private volatile boolean manualSteering;
    private volatile boolean manualMotion;

    private final KeyEventDispatcher dispatcher;
    private final FocusAdapter focusListener;
    private final EventBus.Subscription tickSubscription;
    private final EventBus.Subscription mouseSubscription;

    private volatile boolean driving;
    private volatile boolean interior;
    private volatile String hud = "V summon car | X enter";

    private double lookYaw;
    private double lookPitch;
    private double orbitYaw;
    private double orbitPitch = .28;
    private double targetDistance = 6.5;
    private double currentDistance = 6.5;

    private boolean previousFlight;
    private boolean previousThirdPerson;

    private double noticeSeconds;
    private String notice = "";

    public CarMod(EngineContext context) {
        this.context = context;

        car = new CarVehicle(8, 1);
        context.scene.addRootObjectImmediate(car);

        dispatcher = this::dispatch;

        focusListener = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // Release stale manual keys, but do not disengage FSD.
                clearKeys();
                commands.set(0);
                manualSteering = false;
                manualMotion = false;
            }
        };

        context.engine.addFocusListener(focusListener);

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(dispatcher);

        mouseSubscription = context.eventBus.subscribe(
                MouseLookEvent.class,
                e -> {
                    if (!driving || uiOpen() || closed.get()
                            || !gameHasKeyboardFocus()) {
                        return;
                    }

                    if (interior) {
                        lookYaw = clamp(
                                lookYaw - e.dx * .003,
                                -1.55, 1.55
                        );
                        lookPitch = clamp(
                                lookPitch + e.dy * .003,
                                -.65, .80
                        );
                    } else {
                        orbitYaw = wrap(orbitYaw - e.dx * .003);
                        orbitPitch = clamp(
                                orbitPitch + e.dy * .003,
                                -.12, 1.15
                        );
                    }
                }
        );

        tickSubscription = context.eventBus.subscribe(
                TickEvent.class,
                e -> {
                    if (e.phase == TickEvent.Phase.PRE) {
                        tick(e.dt);
                    } else if (e.phase == TickEvent.Phase.POST
                            && !closed.get()) {
                        if (perception.update(context.engine, car, e.dt)
                                && driving) {
                            car.updateDisplay(perception.getSnapshot());
                        }
                    }
                }
        );

        SwingUtilities.invokeLater(() -> {
            if (closed.get()) return;

            CarFsdWindow window = new CarFsdWindow(
                    context.engine,
                    fsd::getVisualization,
                    perception::getSnapshot,
                    navigation
            );

            fsdWindow = window;
            window.setVisible(false);
        });
    }

    private boolean dispatch(KeyEvent e) {
        if (closed.get()) return false;

        int code = e.getKeyCode();
        if (code < 0 || code >= keys.length()) return false;

        int command = code == KeyEvent.VK_X ? ENTER_EXIT
                : code == KeyEvent.VK_R ? RESET
                : code == KeyEvent.VK_C ? CAMERA
                : code == KeyEvent.VK_B ? FSD
                : code == KeyEvent.VK_M ? VIEW_FSD
                : code == KeyEvent.VK_OPEN_BRACKET ? ZOOM_IN
                : code == KeyEvent.VK_CLOSE_BRACKET ? ZOOM_OUT
                : code == KeyEvent.VK_V || code == KeyEvent.VK_T
                ? SUMMON : 0;

        boolean allowed = acceptsKey(e.getComponent());

        if (e.getID() == KeyEvent.KEY_RELEASED) {
            keys.set(code, 0);
            return allowed && (
                    command != 0 || driving && isDriveKey(code)
            );
        }

        if (e.getID() != KeyEvent.KEY_PRESSED || !allowed) {
            return false;
        }

        if (keys.compareAndSet(code, 0, 1) && command != 0) {
            commands.getAndUpdate(value -> value | command);
        }

        return command != 0 || driving && isDriveKey(code);
    }

    private boolean acceptsKey(Component source) {
        if (source == null || source instanceof JTextComponent) {
            return false;
        }

        if (source == context.engine
                || SwingUtilities.isDescendingFrom(
                source, context.engine)) {
            return true;
        }

        Window sourceWindow = SwingUtilities.getWindowAncestor(source);
        Window gameWindow =
                SwingUtilities.getWindowAncestor(context.engine);

        return sourceWindow != null && sourceWindow == gameWindow;
    }

    private boolean gameHasKeyboardFocus() {
        Component owner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .getFocusOwner();

        if (owner == null || owner instanceof JTextComponent) {
            return false;
        }

        Window gameWindow =
                SwingUtilities.getWindowAncestor(context.engine);

        return gameWindow != null
                && gameWindow == SwingUtilities.getWindowAncestor(owner);
    }

    private static boolean isDriveKey(int code) {
        return code == KeyEvent.VK_W
                || code == KeyEvent.VK_A
                || code == KeyEvent.VK_S
                || code == KeyEvent.VK_D
                || code == KeyEvent.VK_SPACE;
    }

    private boolean down(int key) {
        return keys.get(key) != 0;
    }

    private void clearKeys() {
        for (int i = 0; i < keys.length(); i++) {
            keys.set(i, 0);
        }
    }

    private boolean uiOpen() {
        return context.clickGUI.isOpen()
                || context.inventoryUI.isOpen();
    }

    private void tick(double dt) {
        if (closed.get() || !Double.isFinite(dt) || dt <= 0) return;

        // Navigation and perception continue regardless of focus.
        fsd.navigate(navigation.update(car, dt));

        noticeSeconds = Math.max(0, noticeSeconds - dt);

        int actions = commands.getAndSet(0);
        boolean ui = uiOpen();

        if (!ui && (actions & VIEW_FSD) != 0) {
            CarFsdWindow window = fsdWindow;

            if (window != null) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed.get()) {
                        window.setVisible(!window.isVisible());
                    }
                });
            }
        }

        if (!ui && !driving && (actions & SUMMON) != 0) {
            summonCar();
        }

        if (!ui && (actions & ENTER_EXIT) != 0) {
            if (driving) exit();
            else enter();
        }

        if (!ui && !driving && (actions & FSD) != 0) {
            message("Enter the car with X before enabling FSD");
        }

        if (driving) {
            if (!ui && (actions & RESET) != 0) {
                fsd.setEnabled(false);
                car.reset(context.engine);
                perception.invalidate();
                message("Vehicle reset");
            }

            if (!ui && (actions & CAMERA) != 0) {
                interior = !interior;
                lookYaw = lookPitch = orbitYaw = 0;
                orbitPitch = .28;
            }

            if (!ui && (actions & FSD) != 0) {
                fsd.toggle();

                message(fsd.isEnabled()
                        ? "FSD enabled | background driving active"
                        : "FSD disabled | manual driving");
            }

            if (!ui && !interior) {
                if ((actions & ZOOM_IN) != 0) {
                    targetDistance = clamp(
                            targetDistance - .65, 3.5, 12
                    );
                }

                if ((actions & ZOOM_OUT) != 0) {
                    targetDistance = clamp(
                            targetDistance + .65, 3.5, 12
                    );
                }
            }

            // Only accept manual input when the game owns keyboard focus.
            boolean input = !ui && gameHasKeyboardFocus();

            boolean w = input && down(KeyEvent.VK_W);
            boolean s = input && down(KeyEvent.VK_S);
            boolean a = input && down(KeyEvent.VK_A);
            boolean d = input && down(KeyEvent.VK_D);
            boolean space = input && down(KeyEvent.VK_SPACE);

            manualSteering = a || d;
            manualMotion = w || s || space;

            if (fsd.isEnabled()) {
                int motionIntent = w == s ? 0 : w ? 1 : -1;

                /*
                 * FSD availability is independent of the game window's
                 * keyboard focus. Manual steering/motion still requires
                 * focus, but autonomous steering and acceleration do not.
                 *
                 * Passing true here means "autonomy may run", not that
                 * manual keyboard input has focus.
                 */
                CarFSD.Controls control = fsd.update(
                        dt,
                        car,
                        perception.getSnapshot(),
                        a,
                        d,
                        motionIntent,
                        manualMotion,
                        true
                );

                /*
                 * Critical difference from the previous code:
                 *
                 * Do not gate autonomous commands on 'input'.
                 * Doing so would brake the car when switching windows.
                 */
                car.drive(
                        context.engine,
                        dt,
                        manualMotion ? w : control.forward,
                        manualMotion ? s : control.backward,
                        manualSteering ? a : control.left,
                        manualSteering ? d : control.right,
                        manualMotion ? space : control.brake
                );
            } else {
                manualSteering = false;
                manualMotion = false;

                // Manual driving still brakes when focus is lost.
                car.drive(
                        context.engine,
                        dt,
                        w, s, a, d,
                        !input || space
                );
            }

            positionCamera(dt);
        }

        updateHud();
    }

    private void summonCar() {
        fsd.setEnabled(false);

        Camera camera = context.camera;

        double sideX = Math.cos(camera.yaw);
        double sideZ = Math.sin(camera.yaw);
        double forwardX = -Math.sin(camera.yaw);
        double forwardZ = Math.cos(camera.yaw);

        double[][] offsets = {
                {sideX * 3.5, sideZ * 3.5},
                {-sideX * 3.5, -sideZ * 3.5},
                {-forwardX * 4, -forwardZ * 4},
                {forwardX * 4, forwardZ * 4}
        };

        double oldX = car.transform.position.x;
        double oldY = car.transform.position.y;
        double oldZ = car.transform.position.z;

        for (double[] offset : offsets) {
            double x = camera.x + offset[0];
            double z = camera.z + offset[1];

            car.transform.position.x = x;
            car.transform.position.y = camera.y;
            car.transform.position.z = z;

            double floor = car.exitGroundHeight(
                    context.engine, x, z
            );

            if (!Double.isFinite(floor)
                    || Math.abs(floor - camera.y) > 2) {
                continue;
            }

            car.reset();
            car.transform.position.x = x;
            car.transform.position.y = floor;
            car.transform.position.z = z;
            car.transform.invalidate();

            perception.invalidate();
            message("Car summoned | X enter");
            return;
        }

        car.transform.position.x = oldX;
        car.transform.position.y = oldY;
        car.transform.position.z = oldZ;
        car.transform.invalidate();

        message("Could not find ground beside the player");
    }

    private void enter() {
        fsd.setEnabled(false);

        Camera camera = context.camera;

        double dx = camera.x - car.transform.position.x;
        double dz = camera.z - car.transform.position.z;

        if (dx * dx + dz * dz > 16
                || Math.abs(
                camera.y - car.transform.position.y
        ) > 3) {
            message("Move within 4 metres of the car");
            return;
        }

        previousFlight = camera.flightMode;
        previousThirdPerson = camera.isThirdPerson();

        context.playerController.close();
        context.scene.removeRootObject(context.playerBody);

        orbitYaw = lookYaw = lookPitch = 0;
        orbitPitch = .28;
        currentDistance = targetDistance;
        interior = true;
        driving = true;

        perception.invalidate();

        if (camera.isThirdPerson()) camera.toggleMode();

        positionCamera(0);

        message(
                "WASD drive | B FSD | Space brake | C camera | [ ] zoom"
        );
    }

    private void exit() {
        fsd.setEnabled(false);

        if (car.getGroundSpeed() > .5) {
            message("Stop before exiting");
            return;
        }

        double c = Math.cos(car.getHeading());
        double s = Math.sin(car.getHeading());

        double[][] offsets = {
                {-2.3, 0},
                {2.3, 0},
                {0, -3.4},
                {0, 3.4}
        };

        for (double[] offset : offsets) {
            double x = car.transform.position.x
                    + c * offset[0] + s * offset[1];

            double z = car.transform.position.z
                    - s * offset[0] + c * offset[1];

            double floor =
                    car.exitGroundHeight(context.engine, x, z);

            if (!Double.isFinite(floor)
                    || !collisionQueries.canStandAt(
                    context.engine, car, x, floor + .08, z
            )) {
                continue;
            }

            Camera camera = context.camera;

            camera.x = x;
            camera.y = floor + .08;
            camera.z = z;
            camera.yaw = -car.getHeading();
            camera.pitch = 0;

            camera.setFlightMode(previousFlight);
            clearCameraMomentum(camera);

            if (camera.isThirdPerson() != previousThirdPerson) {
                camera.toggleMode();
            }

            context.playerController = new PlayerController(
                    camera, context.eventBus, context.playerBody
            );

            context.scene.addRootObject(context.playerBody);

            driving = false;
            interior = false;
            clearKeys();

            message("Exited car");
            return;
        }

        message("No safe exit: move to a clearer area");
    }

    private void positionCamera(double dt) {
        Camera camera = context.camera;

        if (camera.isThirdPerson()) camera.toggleMode();
        camera.setFlightMode(true);

        if (interior) {
            double yaw = wrap(-car.getHeading() + lookYaw);

            double eyeX = car.worldX(
                    CarModel.EYE_X,
                    CarModel.EYE_Y,
                    CarModel.EYE_Z
            );
            double eyeY = car.worldY(
                    CarModel.EYE_X,
                    CarModel.EYE_Y,
                    CarModel.EYE_Z
            );
            double eyeZ = car.worldZ(
                    CarModel.EYE_X,
                    CarModel.EYE_Y,
                    CarModel.EYE_Z
            );

            camera.x = eyeX
                    + Math.sin(yaw)
                    * CameraSettings.FP_FORWARD_OFFSET;

            camera.z = eyeZ
                    - Math.cos(yaw)
                    * CameraSettings.FP_FORWARD_OFFSET;

            camera.y = eyeY - Camera.HEAD_HEIGHT;
            camera.yaw = yaw;
            camera.pitch = clamp(
                    car.getPitch() + lookPitch,
                    -1.20, 1.20
            );

            clearCameraMomentum(camera);
            return;
        }

        currentDistance += (targetDistance - currentDistance)
                * (1 - Math.exp(-10 * dt));

        double yaw = wrap(-car.getHeading() + orbitYaw);

        double tx = car.transform.position.x;
        double ty = car.transform.position.y + 1;
        double tz = car.transform.position.z;

        double planar =
                Math.cos(orbitPitch) * currentDistance;

        double ex = tx + Math.sin(yaw) * planar;
        double ez = tz - Math.cos(yaw) * planar;

        double ey = Math.max(
                car.transform.position.y + .35,
                ty + Math.sin(orbitPitch) * currentDistance
        );

        double fraction = collisionQueries.cameraFraction(
                context.engine, car,
                tx, ty, tz,
                ex, ey, ez
        );

        ex = tx + (ex - tx) * fraction;
        ey = ty + (ey - ty) * fraction;
        ez = tz + (ez - tz) * fraction;

        camera.x = ex
                + Math.sin(yaw)
                * CameraSettings.FP_FORWARD_OFFSET;

        camera.z = ez
                - Math.cos(yaw)
                * CameraSettings.FP_FORWARD_OFFSET;

        camera.y = ey - Camera.HEAD_HEIGHT;
        camera.yaw = yaw;

        camera.pitch = Math.atan2(
                ey - ty,
                Math.max(
                        .001,
                        Math.hypot(tx - ex, tz - ez)
                )
        );

        clearCameraMomentum(camera);
    }

    private static void clearCameraMomentum(Camera camera) {
        camera.dx = camera.dy = camera.dz =
                camera.yVelocity = 0;
    }

    private void message(String text) {
        notice = text;
        noticeSeconds = 3;
    }

    private void updateHud() {
        if (driving && fsd.isEnabled() && manualSteering) {
            hud = manualMotion
                    ? "WARNING: FSD WILL NOT STEER | BOTH MANUAL | B off"
                    : "WARNING: FSD WILL NOT STEER | SPEED AUTO | B off";
        } else if (noticeSeconds > 0) {
            hud = notice;
        } else if (driving && fsd.isEnabled() && manualMotion) {
            hud = "FSD STEERING ONLY | MOTION MANUAL | B off";
        } else if (driving && fsd.isEnabled()) {
            hud = fsd.getStatus() + " | B off | M maps";
        } else if (driving) {
            hud = String.format(
                    Locale.ROOT,
                    "%s %.0f km/h | WASD drive | Space brake | B FSD | "
                            + "C camera | [ ] zoom | R reset | X exit",
                    car.getSpeed() < -.15 ? "R" : "D",
                    car.getGroundSpeed() * 3.6
            );
        } else {
            hud = "V summon car | X enter | M maps";
        }
    }

    public void paintHud(
            Graphics2D source,
            int width,
            int height
    ) {
        if (closed.get() || uiOpen() || width < 60) return;

        Graphics2D g = (Graphics2D) source.create();

        try {
            g.setFont(FONT);

            if (driving && fsd.isEnabled()) {
                paintFsdPath(g, width, height);
            }

            String text = hud;
            int limit = Math.max(1, width - 32);

            while (text.length() > 1
                    && g.getFontMetrics().stringWidth(text) > limit) {
                text = text.substring(0, text.length() - 1);
            }

            int x = 16;
            int y = Math.max(18, height - 24);

            g.setColor(Color.BLACK);
            g.drawString(text, x + 1, y + 1);

            g.setColor(
                    driving && fsd.isEnabled()
                            ? new Color(120, 240, 174)
                            : Color.WHITE
            );

            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
    }

    private void paintFsdPath(
            Graphics2D source,
            int width,
            int height
    ) {
        if (width < 320 || height < 300) return;

        CarFSD.Visualization view = fsd.getVisualization();
        if (!view.enabled) return;

        int left = width - 226;
        int top = height - 230;
        int panelWidth = 210;
        int panelHeight = 184;

        Graphics2D g = (Graphics2D) source.create();

        try {
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            g.setColor(new Color(8, 18, 32, 185));
            g.fillRoundRect(
                    left, top, panelWidth, panelHeight, 14, 14
            );

            g.setColor(new Color(135, 165, 185, 120));
            g.setStroke(new BasicStroke(1.0f));
            g.drawRoundRect(
                    left, top, panelWidth, panelHeight, 14, 14
            );

            int dir = view.direction >= 0 ? 1 : -1;

            g.setFont(FONT);
            g.setColor(Color.WHITE);
            g.drawString(
                    dir > 0
                            ? "FSD PATH  FORWARD"
                            : "FSD PATH  REVERSE",
                    left + 12,
                    top + 20
            );

            int originX = left + panelWidth / 2;
            int originY = top + (dir > 0 ? 157 : 49);

            g.setColor(new Color(80, 240, 220));
            g.fillRoundRect(
                    originX - 6,
                    originY - 11,
                    12, 22, 5, 5
            );

            g.setColor(new Color(30, 45, 57));
            g.drawLine(
                    originX - 4, originY - 7,
                    originX + 4, originY - 7
            );

            if (!view.sensorFresh
                    || view.paused
                    || view.chosenDistance <= 0) {
                g.setColor(new Color(255, 195, 110));
                g.drawString(
                        "No verified driving path",
                        left + 12,
                        top + panelHeight - 12
                );
                return;
            }

            double curvature =
                    Math.tan(view.chosenAngle) / 2.875;

            double metres =
                    Math.min(17.0, view.chosenDistance + 2.55);

            double pixelsPerMetre = 6.5;

            Path2D.Double line = new Path2D.Double();

            for (double d = 0; d <= metres + .001; d += .25) {
                double travel = dir * Math.min(d, metres);
                double turn = travel * curvature;

                double lx = Math.abs(curvature) < 1e-7
                        ? 0
                        : (1 - Math.cos(turn)) / curvature;

                double lz = Math.abs(curvature) < 1e-7
                        ? travel
                        : Math.sin(turn) / curvature;

                double screenX =
                        originX + lx * pixelsPerMetre;

                double screenY =
                        originY - lz * pixelsPerMetre;

                if (d == 0) {
                    line.moveTo(screenX, screenY);
                } else {
                    line.lineTo(screenX, screenY);
                }
            }

            double turn = dir * metres * curvature;

            double endX = Math.abs(curvature) < 1e-7
                    ? 0
                    : (1 - Math.cos(turn)) / curvature;

            double endZ = Math.abs(curvature) < 1e-7
                    ? dir * metres
                    : Math.sin(turn) / curvature;

            line.lineTo(
                    originX + endX * pixelsPerMetre,
                    originY - endZ * pixelsPerMetre
            );

            g.clipRect(
                    left + 6,
                    top + 25,
                    panelWidth - 12,
                    panelHeight - 31
            );

            g.setColor(new Color(15, 75, 150, 160));
            g.setStroke(new BasicStroke(
                    10f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));
            g.draw(line);

            g.setColor(new Color(65, 175, 255));
            g.setStroke(new BasicStroke(
                    4f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));
            g.draw(line);
        } finally {
            g.dispose();
        }
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        tickSubscription.close();
        mouseSubscription.close();

        fsd.setEnabled(false);
        perception.close();
        navigation.close();

        CarFsdWindow window = fsdWindow;
        fsdWindow = null;

        if (window != null) window.close();

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher);

        Runnable remove =
                () -> context.engine.removeFocusListener(focusListener);

        if (SwingUtilities.isEventDispatchThread()) {
            remove.run();
        } else {
            SwingUtilities.invokeLater(remove);
        }

        clearKeys();
    }
}