package engine.render;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public final class Texture {

    public enum Wrap { CLAMP, REPEAT }

    public final int width;
    public final int height;
    public final int[] argb;

    public Texture(int width, int height, int[] argb) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        if (argb == null || argb.length != this.width * this.height) {
            throw new IllegalArgumentException("Texture pixel array must be width*height");
        }
        this.argb = argb;
    }

    public static Texture fromBufferedImage(BufferedImage img) {
        if (img == null) throw new IllegalArgumentException("img == null");
        BufferedImage src = img;
        if (src.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage converted = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            converted.getGraphics().drawImage(src, 0, 0, null);
            src = converted;
        }
        int[] px = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] copy = new int[px.length];
        System.arraycopy(px, 0, copy, 0, px.length);
        return new Texture(src.getWidth(), src.getHeight(), copy);
    }

    public int sampleNearest(double u, double v, Wrap wrap) {
        double uu = wrapCoord(u, wrap);
        double vv = wrapCoord(v, wrap);

        int x = (int) (uu * width);
        int y = (int) (vv * height);

        if (x < 0) x = 0;
        if (x >= width) x = width - 1;
        if (y < 0) y = 0;
        if (y >= height) y = height - 1;

        return argb[y * width + x];
    }

    // Kept for compatibility, but simplified (no gamma-correct blending).
    public int sampleBilinear(double u, double v, Wrap wrap) {
        double uu = wrapCoord(u, wrap);
        double vv = wrapCoord(v, wrap);

        double fx = uu * (width - 1);
        double fy = vv * (height - 1);

        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);

        float tx = (float) (fx - x0);
        float ty = (float) (fy - y0);

        int c00 = argb[y0 * width + x0];
        int c10 = argb[y0 * width + x1];
        int c01 = argb[y1 * width + x0];
        int c11 = argb[y1 * width + x1];

        return bilerpARGB(c00, c10, c01, c11, tx, ty);
    }

    private static int bilerpARGB(int c00, int c10, int c01, int c11, float tx, float ty) {
        int a00 = (c00 >>> 24) & 255, r00 = (c00 >>> 16) & 255, g00 = (c00 >>> 8) & 255, b00 = c00 & 255;
        int a10 = (c10 >>> 24) & 255, r10 = (c10 >>> 16) & 255, g10 = (c10 >>> 8) & 255, b10 = c10 & 255;
        int a01 = (c01 >>> 24) & 255, r01 = (c01 >>> 16) & 255, g01 = (c01 >>> 8) & 255, b01 = c01 & 255;
        int a11 = (c11 >>> 24) & 255, r11 = (c11 >>> 16) & 255, g11 = (c11 >>> 8) & 255, b11 = c11 & 255;

        float a0 = a00 + (a10 - a00) * tx;
        float r0 = r00 + (r10 - r00) * tx;
        float g0 = g00 + (g10 - g00) * tx;
        float b0 = b00 + (b10 - b00) * tx;

        float a1 = a01 + (a11 - a01) * tx;
        float r1 = r01 + (r11 - r01) * tx;
        float g1 = g01 + (g11 - g01) * tx;
        float b1 = b01 + (b11 - b01) * tx;

        int a = clamp255(Math.round(a0 + (a1 - a0) * ty));
        int r = clamp255(Math.round(r0 + (r1 - r0) * ty));
        int g = clamp255(Math.round(g0 + (g1 - g0) * ty));
        int b = clamp255(Math.round(b0 + (b1 - b0) * ty));

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    private static double wrapCoord(double t, Wrap wrap) {
        if (wrap == Wrap.REPEAT) {
            t = t - Math.floor(t);
            if (t < 0) t += 1.0;
            return t;
        }
        if (t < 0) return 0.0;
        if (t > 1) return 1.0;
        return t;
    }
}
