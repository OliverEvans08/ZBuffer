package gui.components;

import java.awt.*;

public class Slider {
    public final Rectangle bounds;
    private int min;
    private int max;
    private int value;
    private boolean dragging;
    private String name;

    private boolean isHovering;

    public Slider(int x, int y, int width, int min, int max, int initialValue, String name) {
        this.bounds = new Rectangle(x, y, width, 20);
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, initialValue));
        this.dragging = false;
        this.name = name;
        this.isHovering = false;
    }

    public void render(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;

        g.setColor(Color.WHITE);
        g.drawString(name, bounds.x - g.getFontMetrics().stringWidth(name) - 5, bounds.y + 15);

        GradientPaint gradient = new GradientPaint(bounds.x, bounds.y + 10, new Color(100, 100, 100), bounds.x, bounds.y + 10 + 5, new Color(50, 50, 50));
        g2d.setPaint(gradient);
        g2d.fillRoundRect(bounds.x, bounds.y + 10, bounds.width, 5, 5, 5);

        int knobX = bounds.x + (int) ((float) (value - min) / (max - min) * bounds.width);

        if (isHovering) {
            g.setColor(new Color(255, 165, 0));
        } else {
            g.setColor(Color.BLUE);
        }
        g2d.fillRoundRect(knobX - 8, bounds.y, 16, 20, 10, 10);

        g.setColor(Color.WHITE);
        g.drawString(String.valueOf(value), bounds.x + bounds.width + 10, bounds.y + 15);
    }

    public boolean contains(int x, int y) {
        return bounds.contains(x, y);
    }

    public void startDragging(int mouseX) {
        if (contains(mouseX, bounds.y)) {
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
        int newValue = min + (int) (((float) (mouseX - bounds.x) / bounds.width) * (max - min));
        value = Math.max(min, Math.min(max, newValue));
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = Math.max(min, Math.min(max, value));
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public void updateHoverStatus(int mouseX, int mouseY) {
        isHovering = bounds.contains(mouseX, mouseY);
    }
}
