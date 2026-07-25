package engine.ui;

import engine.EngineContext;
import engine.EngineSettings;
import engine.loop.FrameTiming;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;

public class GamePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private final FrameTiming frameTiming = new FrameTiming();

    private EngineContext context;
    private CursorController cursorController;
    private DebugOverlay debugOverlay;

    public int fps;

    public void initialize(EngineContext context) {
        this.context = context;
        cursorController = new CursorController(this, context);
        debugOverlay = new DebugOverlay(context);

        setPreferredSize(
                new Dimension(
                        EngineSettings.WIDTH,
                        EngineSettings.HEIGHT
                )
        );

        setFocusable(true);
        setDoubleBuffered(true);
        requestFocusInWindow();

        addKeyListener(context.inputHandler);
        addMouseMotionListener(context.inputHandler);
        addMouseListener(context.inputHandler);

        cursorController.installFocusListener();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        repaintPending.set(false);

        super.paintComponent(graphics);

        if (!(graphics instanceof Graphics2D graphics2D)) {
            return;
        }

        graphics2D.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED
        );

        graphics2D.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );

        graphics2D.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF
        );

        graphics2D.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        );

        final int width = getWidth();
        final int height = getHeight();

        context.renderer.render(
                graphics2D,
                context.scene.getRootObjects(),
                width,
                height
        );

        context.inventoryUI.render(
                graphics2D,
                width,
                height
        );

        context.clickGUI.render(graphics2D);

        if (context.clickGUI.isDebug()) {
            debugOverlay.draw(graphics2D, fps);
        }

        fps = frameTiming.countFrame();
    }

    public boolean requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            repaint();
            return true;
        }

        return false;
    }

    public FrameTiming getFrameTiming() {
        return frameTiming;
    }

    public void hideCursor() {
        cursorController.hideCursor();
    }

    public void showCursor() {
        cursorController.showCursor();
    }

    public void onGuiToggled(boolean open) {
        cursorController.onGuiToggled(open);
    }

    public void onInventoryToggled(boolean open) {
        cursorController.onInventoryToggled(open);
    }
}