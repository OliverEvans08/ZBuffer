package gui.components;

import java.awt.*;
import java.util.function.IntConsumer;

public class Slider {
    public final Rectangle bounds;
    private final int min;
    private final int max;
    private int value;
    private boolean dragging;
    private final String name;

    private boolean isHovering;
    private IntConsumer onChange;

    public Slider(int x, int y, int width, int min, int max, int initialValue, String name) {
        this.bounds = new Rectangle(x, y, width, 20);
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, initialValue));
        this.dragging = false;
        this.name = name;
        this.isHovering = false;
    }

    public Slider onChange(IntConsumer cb) {
        this.onChange = cb;
        // fire initial value
        if (cb != null) cb.accept(value);
        return this;
    }

    public void render(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;

        g.setColor(Color.WHITE);
        g.drawString(name, bounds.x - g.getFontMetrics().stringWidth(name) - 5, bounds.y + 15);

        GradientPaint gradient = new GradientPaint(bounds.x, bounds.y + 10, new Color(100, 100, 100),
                bounds.x, bounds.y + 15, new Color(50, 50, 50));
        g2d.setPaint(gradient);
        g2d.fillRoundRect(bounds.x, bounds.y + 10, bounds.width, 5, 5, 5);

        int knobX = bounds.x + (int) Math.round(((value - min) / (double) (max - min)) * bounds.width);

        g.setColor(isHovering ? new Color(255, 165, 0) : Color.BLUE);
        g2d.fillRoundRect(knobX - 8, bounds.y, 16, 20, 10, 10);

        g.setColor(Color.WHITE);
        g.drawString(String.valueOf(value), bounds.x + bounds.width + 10, bounds.y + 15);
    }

    public boolean contains(int x, int y) {
        return bounds.contains(x, y);
    }

    // legacy signature (kept for compatibility if used elsewhere)
    public void startDragging(int mouseX) {
        startDragging(mouseX, bounds.y + 10);
    }

    public void startDragging(int mouseX, int mouseY) {
        if (contains(mouseX, mouseY)) {
            dragging = true;
            updateValue(mouseX);
        }
    }

    public void stopDragging() {
        dragging = false;
    }

    public void drag(int mouseX) {
        if (dragging) {
            updateValue(mouseX);
        }
    }

    private void updateValue(int mouseX) {
        double t = (mouseX - bounds.x) / (double) bounds.width;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int newValue = min + (int) Math.round(t * (max - min));
        if (newValue != value) {
            value = newValue;
            if (onChange != null) onChange.accept(value);
        }
    }

    public int getValue() { return value; }
    public void setValue(int value) {
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != this.value) {
            this.value = clamped;
            if (onChange != null) onChange.accept(this.value);
        }
    }
    public int getMin() { return min; }
    public int getMax() { return max; }

    public void updateHoverStatus(int mouseX, int mouseY) {
        isHovering = bounds.contains(mouseX, mouseY);
    }
}
