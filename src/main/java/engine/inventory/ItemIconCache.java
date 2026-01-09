package engine.inventory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemIconCache {

    public static final int SOURCE_ICON_SIZE = 64;

    /**
     * Resolution order (no guessing):
     *  1) JVM property: -Dzbuffer.iconRoot="C:\...\ZBuffer\icons"
     *  2) Env var:      ZBUFFER_ICON_ROOT
     *  3) Search upwards from user.dir for a folder named "icons"
     */
    private final Path iconRoot;

    private final ConcurrentHashMap<String, SoftReference<BufferedImage>> originals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SoftReference<Image>> scaled = new ConcurrentHashMap<>();

    public ItemIconCache() {
        this(resolveDefaultRoot());
    }

    public ItemIconCache(Path iconRoot) {
        this.iconRoot = (iconRoot == null ? resolveDefaultRoot() : iconRoot.toAbsolutePath().normalize());
    }

    public Path getIconRoot() { return iconRoot; }

    public Image getIcon(ItemDefinition def, int size) {
        if (def == null) return null;
        return getIcon(def.getIconId(), size);
    }

    public Image getIcon(String iconId, int size) {
        if (iconId == null || iconId.isBlank()) return null;
        if (size <= 0) return null;

        final String key = iconId + "@" + size;

        SoftReference<Image> ref = scaled.get(key);
        Image cached = (ref != null) ? ref.get() : null;
        if (cached != null) return cached;

        BufferedImage src = getOriginal(iconId);
        if (src == null) return null;

        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int sw = src.getWidth();
            int sh = src.getHeight();
            if (sw <= 0 || sh <= 0) return null;

            // keep aspect ratio + center
            double s = Math.min(size / (double) sw, size / (double) sh);
            int dw = Math.max(1, (int) Math.round(sw * s));
            int dh = Math.max(1, (int) Math.round(sh * s));
            int dx = (size - dw) / 2;
            int dy = (size - dh) / 2;

            g.drawImage(src, dx, dy, dw, dh, null);
        } finally {
            g.dispose();
        }

        scaled.put(key, new SoftReference<>(dst));
        return dst;
    }

    public void clear() {
        originals.clear();
        scaled.clear();
    }

    private BufferedImage getOriginal(String iconId) {
        SoftReference<BufferedImage> ref = originals.get(iconId);
        BufferedImage img = (ref != null) ? ref.get() : null;
        if (img != null) return img;

        img = loadOriginal(iconId);
        if (img != null) originals.put(iconId, new SoftReference<>(img));
        return img;
    }

    private BufferedImage loadOriginal(String iconId) {
        // 1) Filesystem: <iconRoot>/<iconId>.png
        try {
            Path p = iconRoot.resolve(iconId + ".png");
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return ImageIO.read(p.toFile());
            }
        } catch (Exception ignored) {}

        // 2) Optional classpath fallback (only if you later put icons into resources)
        // These are just fallbacks and won't be used for your current setup.
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("engine/inventory/icons/" + iconId + ".png")) {
            if (in != null) return ImageIO.read(in);
        } catch (Exception ignored) {}

        try (InputStream in = ItemIconCache.class.getResourceAsStream("/engine/inventory/icons/" + iconId + ".png")) {
            if (in != null) return ImageIO.read(in);
        } catch (Exception ignored) {}

        return null;
    }

    private static Path resolveDefaultRoot() {
        // 1) JVM property override
        String prop = System.getProperty("zbuffer.iconRoot");
        Path p = validateDir(prop);
        if (p != null) return p;

        // 2) Environment variable override
        String env = System.getenv("ZBUFFER_ICON_ROOT");
        p = validateDir(env);
        if (p != null) return p;

        // 3) Deterministic search up from user.dir for "icons" directory
        Path base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && base != null; i++) {
            Path cand = base.resolve("icons");
            if (Files.exists(cand) && Files.isDirectory(cand)) return cand;
            base = base.getParent();
        }

        // Final fallback (won't work unless it exists)
        return Paths.get("icons").toAbsolutePath().normalize();
    }

    private static Path validateDir(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            Path p = Paths.get(s).toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isDirectory(p)) return p;
        } catch (Exception ignored) {}
        return null;
    }
}
