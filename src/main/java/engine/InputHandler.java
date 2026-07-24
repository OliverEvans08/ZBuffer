package engine;

import engine.event.EventBus;
import engine.event.GameEvent;
import engine.event.events.DropHeldItemRequestedEvent;
import engine.event.events.GuiToggleRequestedEvent;
import engine.event.events.HotbarSelectRequestedEvent;
import engine.event.events.InventoryToggleRequestedEvent;
import engine.event.events.MouseLookEvent;
import engine.event.events.MovementIntentEvent;
import engine.event.events.PickupRequestedEvent;
import engine.event.events.ToggleFlightRequestedEvent;
import engine.event.events.ToggleViewRequestedEvent;
import engine.event.events.UseHeldItemRequestedEvent;
import gui.ClickGUI;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Bridges AWT's event-dispatch thread to the fixed-update game thread.
 *
 * <p>AWT callbacks only update atomic input state or enqueue commands.
 * EventBus publication is performed by {@link #updatePerTick()} on the game
 * thread, so gameplay systems are not mutated concurrently by Swing.</p>
 */
public final class InputHandler
        implements KeyListener,
        MouseMotionListener,
        MouseListener,
        AutoCloseable {

    private static final System.Logger LOGGER =
            System.getLogger(InputHandler.class.getName());

    private static final long USE_ACTION_COOLDOWN_NANOS =
            120_000_000L;
    private static final int EDGE_MARGIN = 20;
    private static final int KEY_CAPACITY = 1024;

    private final EventBus eventBus;
    private final GameEngine gameEngine;
    private final Robot robot;
    private final AtomicIntegerArray keyDown =
            new AtomicIntegerArray(KEY_CAPACITY);
    private final ConcurrentLinkedQueue<GameEvent> queuedEvents =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingMouseDeltaX =
            new AtomicInteger();
    private final AtomicInteger pendingMouseDeltaY =
            new AtomicInteger();
    private final AtomicBoolean captureMouse =
            new AtomicBoolean(true);
    private final AtomicBoolean ignoreNextMouseMove =
            new AtomicBoolean();
    private final AtomicBoolean closed =
            new AtomicBoolean();
    private final KeyEventDispatcher globalDispatcher;

    private volatile long lastUseActionNanos;
    private volatile int lastMouseX = -1;
    private volatile int lastMouseY = -1;

    public InputHandler(
            EventBus eventBus,
            GameEngine gameEngine
    ) {
        this.eventBus = Objects.requireNonNull(
                eventBus,
                "eventBus"
        );
        this.gameEngine = Objects.requireNonNull(
                gameEngine,
                "gameEngine"
        );

        this.robot = createRobot();
        this.globalDispatcher =
                this::dispatchGlobalKeyEvent;

        KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(globalDispatcher);
    }

    /** Called once per fixed update on the game thread. */
    public void updatePerTick() {
        GameEvent event;

        while ((event = queuedEvents.poll()) != null) {
            eventBus.publish(event);
        }

        final int mouseDx =
                pendingMouseDeltaX.getAndSet(0);
        final int mouseDy =
                pendingMouseDeltaY.getAndSet(0);

        if (mouseDx != 0 || mouseDy != 0) {
            eventBus.publish(
                    new MouseLookEvent(mouseDx, mouseDy)
            );
        }

        final boolean uiOpen = isAnyUiOpen();

        eventBus.publish(
                new MovementIntentEvent(
                        !uiOpen && isDown(KeyEvent.VK_W),
                        !uiOpen && isDown(KeyEvent.VK_S),
                        !uiOpen && isDown(KeyEvent.VK_A),
                        !uiOpen && isDown(KeyEvent.VK_D),
                        !uiOpen && isDown(KeyEvent.VK_SPACE),
                        !uiOpen && isDown(KeyEvent.VK_SHIFT)
                )
        );
    }

    public void setCaptureMouse(boolean enabled) {
        captureMouse.set(enabled);

        if (!enabled) {
            clearKeys();
            resetMouseTracking();
        }
    }

    public void centerCursor(JPanel panel) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(
                    () -> centerCursor(panel)
            );
            return;
        }

        if (panel == null
                || !panel.isShowing()
                || !captureMouse.get()
                || robot == null
                || isAnyUiOpen()) {
            gameEngine.showCursor();
            return;
        }

        final Point center = new Point(
                panel.getWidth() / 2,
                panel.getHeight() / 2
        );

        SwingUtilities.convertPointToScreen(center, panel);

        ignoreNextMouseMove.set(true);
        robot.mouseMove(center.x, center.y);

        lastMouseX = panel.getWidth() / 2;
        lastMouseY = panel.getHeight() / 2;

        gameEngine.hideCursor();
    }

    @Override
    public void keyPressed(KeyEvent event) {
        setKeyState(event.getKeyCode(), true);
    }

    @Override
    public void keyReleased(KeyEvent event) {
        setKeyState(event.getKeyCode(), false);
    }

    @Override
    public void keyTyped(KeyEvent event) {
        // Physical key state is handled by keyPressed/keyReleased.
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        if (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen()) {
            gameEngine.showCursor();
            gameEngine.inventoryUI.mouseMoved(event);
            return;
        }

        final ClickGUI gui = gameEngine.clickGUI;

        if (gui != null && gui.isOpen()) {
            gameEngine.showCursor();
            gui.mouseMoved(event);
            return;
        }

        if (!captureMouse.get()) {
            gameEngine.showCursor();
            return;
        }

        if (ignoreNextMouseMove.compareAndSet(true, false)) {
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            return;
        }

        if (lastMouseX < 0 || lastMouseY < 0) {
            lastMouseX = event.getX();
            lastMouseY = event.getY();
            return;
        }

        final int dx = event.getX() - lastMouseX;
        final int dy = event.getY() - lastMouseY;

        lastMouseX = event.getX();
        lastMouseY = event.getY();

        if (dx != 0) {
            pendingMouseDeltaX.addAndGet(dx);
        }

        if (dy != 0) {
            pendingMouseDeltaY.addAndGet(dy);
        }

        final int width = gameEngine.getWidth();
        final int height = gameEngine.getHeight();

        if (robot != null && width > 0 && height > 0) {
            final int x = event.getX();
            final int y = event.getY();

            if (x < EDGE_MARGIN
                    || x > width - EDGE_MARGIN
                    || y < EDGE_MARGIN
                    || y > height - EDGE_MARGIN) {
                centerCursor(gameEngine);
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        if (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseDragged(event);
            return;
        }

        if (gameEngine.clickGUI != null) {
            gameEngine.clickGUI.mouseDragged(
                    event.getX(),
                    event.getY()
            );
        }
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        if (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseClicked(event);
            return;
        }

        if (gameEngine.clickGUI != null) {
            gameEngine.clickGUI.clicked(
                    event.getX(),
                    event.getY()
            );
        }
    }

    @Override
    public void mousePressed(MouseEvent event) {
        if (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mousePressed(event);
            return;
        }

        if (gameEngine.clickGUI != null
                && gameEngine.clickGUI.isOpen()) {
            gameEngine.clickGUI.mousePressed(event);
            return;
        }

        if (event.getButton() == MouseEvent.BUTTON1
                && useActionReady()) {
            queuedEvents.add(
                    new UseHeldItemRequestedEvent()
            );
        }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        if (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseReleased(event);
            return;
        }

        if (gameEngine.clickGUI != null) {
            gameEngine.clickGUI.mouseReleased(event);
        }
    }

    @Override
    public void mouseEntered(MouseEvent event) {
    }

    @Override
    public void mouseExited(MouseEvent event) {
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            KeyboardFocusManager
                    .getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(globalDispatcher);

            queuedEvents.clear();
            clearKeys();
        }
    }

    private boolean dispatchGlobalKeyEvent(KeyEvent event) {
        if (closed.get()) {
            return false;
        }

        final int id = event.getID();
        final int keyCode = event.getKeyCode();

        if (id == KeyEvent.KEY_RELEASED) {
            setKeyState(keyCode, false);
            return isCommandKey(keyCode);
        }

        if (id != KeyEvent.KEY_PRESSED) {
            return false;
        }

        final boolean firstPress =
                setKeyState(keyCode, true);

        if (!firstPress) {
            return isCommandKey(keyCode);
        }

        final GameEvent command = commandFor(keyCode);

        if (command == null) {
            return false;
        }

        queuedEvents.add(command);
        return true;
    }

    /**
     * Returns true only for a transition from up to down.
     */
    private boolean setKeyState(
            int keyCode,
            boolean down
    ) {
        if (keyCode < 0 || keyCode >= keyDown.length()) {
            return false;
        }

        if (!down) {
            keyDown.set(keyCode, 0);
            return false;
        }

        return keyDown.compareAndSet(keyCode, 0, 1);
    }

    private boolean isDown(int keyCode) {
        return keyCode >= 0
                && keyCode < keyDown.length()
                && keyDown.get(keyCode) != 0;
    }

    private void clearKeys() {
        for (int i = 0; i < keyDown.length(); i++) {
            keyDown.set(i, 0);
        }
    }

    private void resetMouseTracking() {
        lastMouseX = -1;
        lastMouseY = -1;
        pendingMouseDeltaX.set(0);
        pendingMouseDeltaY.set(0);
        ignoreNextMouseMove.set(false);
    }

    private boolean isAnyUiOpen() {
        return (gameEngine.clickGUI != null
                && gameEngine.clickGUI.isOpen())
                || (gameEngine.inventoryUI != null
                && gameEngine.inventoryUI.isOpen());
    }

    private boolean useActionReady() {
        final long now = System.nanoTime();
        final long previous = lastUseActionNanos;

        if (now - previous < USE_ACTION_COOLDOWN_NANOS) {
            return false;
        }

        lastUseActionNanos = now;
        return true;
    }

    private static boolean isCommandKey(int keyCode) {
        return commandFor(keyCode) != null;
    }

    private static GameEvent commandFor(int keyCode) {
        if (keyCode == KeyEvent.VK_E) {
            return new InventoryToggleRequestedEvent();
        }

        if (keyCode >= KeyEvent.VK_1
                && keyCode <= KeyEvent.VK_9) {
            return new HotbarSelectRequestedEvent(
                    keyCode - KeyEvent.VK_1
            );
        }

        return switch (keyCode) {
            case KeyEvent.VK_P ->
                    new GuiToggleRequestedEvent();
            case KeyEvent.VK_G ->
                    new ToggleFlightRequestedEvent();
            case KeyEvent.VK_V ->
                    new ToggleViewRequestedEvent();
            case KeyEvent.VK_F ->
                    new PickupRequestedEvent();
            case KeyEvent.VK_Q ->
                    new DropHeldItemRequestedEvent();
            default -> null;
        };
    }

    private static Robot createRobot() {
        try {
            return new Robot();
        } catch (AWTException | SecurityException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Mouse recentering is unavailable; "
                            + "pointer capture will be disabled",
                    exception
            );
            return null;
        }
    }
}