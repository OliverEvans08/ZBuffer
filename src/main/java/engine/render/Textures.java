package engine.render;

public final class Textures {

    private Textures() {}

    public static Texture checker(int w, int h, int cell, int aARGB, int bARGB) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        cell = Math.max(1, cell);

        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int cy = (y / cell) & 1;
            for (int x = 0; x < w; x++) {
                int cx = (x / cell) & 1;
                px[y * w + x] = ((cx ^ cy) == 0) ? aARGB : bARGB;
            }
        }
        return new Texture(w, h, px);
    }

    public static Texture grid(int w, int h, int cell, int lineARGB, int fillARGB) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        cell = Math.max(2, cell);

        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            boolean yLine = (y % cell) == 0;
            for (int x = 0; x < w; x++) {
                boolean xLine = (x % cell) == 0;
                px[y * w + x] = (xLine || yLine) ? lineARGB : fillARGB;
            }
        }
        return new Texture(w, h, px);
    }
}
