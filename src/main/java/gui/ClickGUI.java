package gui;

import engine.GameEngine;
import gui.components.Button;
import gui.components.Slider;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ClickGUI {

    private static final int CONTROL_X_OFFSET = 20;
    private static final int DEBUG_BUTTON_Y_OFFSET = 20;
    private static final int FXAA_BUTTON_Y_OFFSET = 60;
    private static final int FOV_SLIDER_Y_OFFSET = 110;
    private static final int RENDER_DISTANCE_SLIDER_Y_OFFSET = 150;
    private static final int RENDER_SCALE_SLIDER_Y_OFFSET = 190;

    private final GameEngine engine;

    private boolean isOpen = false;

    public final List<Button> buttons = new ArrayList<>();
    public final List<Slider> sliders = new ArrayList<>();

    private int panelX = 100;
    private int panelY = 100;
    private int panelWidth = 260;
    private int panelHeight = 235;

    private boolean dragging = false;
    private int dragX;
    private int dragY;
    private boolean mousePressed = false;

    private final Button debugButton;
    private final Button fxaaButton;

    private final Slider fovSlider;
    private final Slider renderSlider;
    private final Slider renderScaleSlider;

    public ClickGUI(GameEngine engine) {
        this.engine = engine;

        debugButton = new Button(
                panelX + CONTROL_X_OFFSET,
                panelY + DEBUG_BUTTON_Y_OFFSET,
                200,
                36,
                "Debug"
        );

        buttons.add(debugButton);

        fxaaButton = new Button(
                panelX + CONTROL_X_OFFSET,
                panelY + FXAA_BUTTON_Y_OFFSET,
                200,
                36,
                "FXAA"
        );

        if (engine.renderer.isFxaaEnabled()) {
            fxaaButton.toggle();
        }

        buttons.add(fxaaButton);

        fovSlider = new Slider(
                panelX + CONTROL_X_OFFSET,
                panelY + FOV_SLIDER_Y_OFFSET,
                200,
                30,
                120,
                70,
                "FOV"
        ).onChange(engine::setFovDegrees);

        sliders.add(fovSlider);

        renderSlider = new Slider(
                panelX + CONTROL_X_OFFSET,
                panelY + RENDER_DISTANCE_SLIDER_Y_OFFSET,
                200,
                50,
                500,
                200,
                "Render Distance"
        ).onChange(engine::setRenderDistance);

        sliders.add(renderSlider);

        final int initialRenderScale =
                (int) Math.round(
                        engine.renderer.getRenderScale() * 100.0
                );

        renderScaleSlider = new Slider(
                panelX + CONTROL_X_OFFSET,
                panelY + RENDER_SCALE_SLIDER_Y_OFFSET,
                200,
                25,
                100,
                initialRenderScale,
                "Render Scale"
        ).onChange(
                value -> engine.renderer.setRenderScale(
                        value / 100.0
                )
        );

        sliders.add(renderScaleSlider);
    }

    public void render(Graphics graphics) {
        if (!isOpen) {
            return;
        }

        final Graphics2D graphics2D =
                (Graphics2D) graphics;

        final GradientPaint gradient =
                new GradientPaint(
                        panelX,
                        panelY,
                        new Color(32, 32, 32),
                        panelX,
                        panelY + panelHeight,
                        new Color(10, 10, 10)
                );

        graphics2D.setPaint(gradient);
        graphics2D.fillRoundRect(
                panelX,
                panelY,
                panelWidth,
                panelHeight,
                16,
                16
        );

        graphics2D.setColor(
                new Color(255, 255, 255, 24)
        );

        graphics2D.fillRoundRect(
                panelX,
                panelY,
                panelWidth,
                24,
                16,
                16
        );

        graphics2D.setColor(Color.WHITE);
        graphics2D.drawString(
                "Settings",
                panelX + 10,
                panelY + 16
        );

        for (Button button : buttons) {
            button.render(graphics);
        }

        for (Slider slider : sliders) {
            slider.render(graphics);
        }
    }

    public void setOpen(boolean open) {
        if (isOpen == open) {
            return;
        }

        isOpen = open;
        engine.onGuiToggled(open);
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void clicked(int x, int y) {
        if (!isOpen) {
            return;
        }

        for (Button button : buttons) {
            if (button.contains(x, y)) {
                button.toggle();
                handleButtonClick(button);
                return;
            }
        }

        for (Slider slider : sliders) {
            if (slider.contains(x, y)) {
                slider.startDragging(x, y);
                return;
            }
        }
    }

    public void mouseReleased(MouseEvent event) {
        dragging = false;
        mousePressed = false;

        for (Slider slider : sliders) {
            slider.stopDragging();
        }
    }

    public void mouseDragged(int x, int y) {
        if (!isOpen) {
            return;
        }

        if (dragging) {
            panelX = x - dragX;
            panelY = y - dragY;

            updateControlLocations();
            return;
        }

        for (Slider slider : sliders) {
            slider.drag(x);
        }
    }

    public void mouseMoved(MouseEvent event) {
        if (!isOpen) {
            return;
        }

        final int mouseX = event.getX();
        final int mouseY = event.getY();

        for (Button button : buttons) {
            button.updateHoverStatus(mouseX, mouseY);
        }

        for (Slider slider : sliders) {
            slider.updateHoverStatus(mouseX, mouseY);
        }
    }

    public void mousePressed(MouseEvent event) {
        mousePressed = true;

        if (!isOpen) {
            return;
        }

        final int x = event.getX();
        final int y = event.getY();

        final boolean insidePanel =
                x >= panelX
                        && x <= panelX + panelWidth
                        && y >= panelY
                        && y <= panelY + panelHeight;

        final boolean inHeader =
                insidePanel
                        && y <= panelY + 24;

        if (inHeader) {
            dragX = x - panelX;
            dragY = y - panelY;
            dragging = true;
            return;
        }

        for (Slider slider : sliders) {
            if (slider.contains(x, y)) {
                slider.startDragging(x, y);
                break;
            }
        }
    }

    private void handleButtonClick(Button button) {
        if (button == fxaaButton) {
            engine.renderer.setFxaaEnabled(
                    fxaaButton.isToggled()
            );
        }
    }

    private void updateControlLocations() {
        debugButton.bounds.setLocation(
                panelX + CONTROL_X_OFFSET,
                panelY + DEBUG_BUTTON_Y_OFFSET
        );

        fxaaButton.bounds.setLocation(
                panelX + CONTROL_X_OFFSET,
                panelY + FXAA_BUTTON_Y_OFFSET
        );

        fovSlider.bounds.setLocation(
                panelX + CONTROL_X_OFFSET,
                panelY + FOV_SLIDER_Y_OFFSET
        );

        renderSlider.bounds.setLocation(
                panelX + CONTROL_X_OFFSET,
                panelY + RENDER_DISTANCE_SLIDER_Y_OFFSET
        );

        renderScaleSlider.bounds.setLocation(
                panelX + CONTROL_X_OFFSET,
                panelY + RENDER_SCALE_SLIDER_Y_OFFSET
        );
    }

    public boolean isMousePressed() {
        return mousePressed;
    }

    public Slider getFOVSlider() {
        return fovSlider;
    }

    public Slider getRenderDistanceSlider() {
        return renderSlider;
    }

    public Slider getRenderScaleSlider() {
        return renderScaleSlider;
    }

    public boolean isDebug() {
        return debugButton.isToggled();
    }
}