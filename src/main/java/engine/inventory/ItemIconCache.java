package engine.inventory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, deterministic cache for source and scaled inventory icons. */
public final class ItemIconCache {

    public static final int SOURCE_ICON_SIZE = 64;

    private static final System.Logger LOGGER =
            System.getLogger(ItemIconCache.class.getName());

    private static final int MAX_ORIGINALS = 128;
    private static final int MAX_SCALED_ICONS = 512;
    private static final int MAX_REQUESTED_SIZE = 512;

    private final Path iconRoot;
    private final Map<String, BufferedImage> originals =
            lruMap(MAX_ORIGINALS);
    private final Map<ScaledKey, BufferedImage> scaled =
            lruMap(MAX_SCALED_ICONS);

    public ItemIconCache() {
        this(resolveDefaultRoot());
    }

    public ItemIconCache(Path iconRoot) {
        this.iconRoot = (
                iconRoot == null
                        ? resolveDefaultRoot()
                        : iconRoot
        ).toAbsolutePath().normalize();
    }

    public Path getIconRoot() {
        return iconRoot;
    }

    public Image getIcon(
            ItemDefinition definition,
            int size
    ) {
        return definition == null
                ? null
                : getIcon(definition.getIconId(), size);
    }

    public synchronized Image getIcon(
            String iconId,
            int size
    ) {
        final String normalizedId =
                normalizeIconId(iconId);

        if (normalizedId == null
                || size <= 0
                || size > MAX_REQUESTED_SIZE) {
            return null;
        }

        final ScaledKey key =
                new ScaledKey(normalizedId, size);

        final BufferedImage cached = scaled.get(key);

        if (cached != null) {
            return cached;
        }

        final BufferedImage source =
                getOriginal(normalizedId);

        if (source == null) {
            return null;
        }

        final BufferedImage resized =
                scaleToSquare(source, size);

        scaled.put(key, resized);
        return resized;
    }

    public synchronized void clear() {
        originals.clear();
        scaled.clear();
    }

    private BufferedImage getOriginal(String iconId) {
        final BufferedImage cached =
                originals.get(iconId);

        if (cached != null) {
            return cached;
        }

        final BufferedImage loaded =
                loadOriginal(iconId);

        if (loaded != null) {
            originals.put(iconId, loaded);
        }

        return loaded;
    }

    private BufferedImage loadOriginal(String iconId) {
        final Path file = iconRoot
                .resolve(iconId + ".png")
                .normalize();

        if (file.startsWith(iconRoot)
                && Files.isRegularFile(file)) {
            try {
                final BufferedImage image =
                        ImageIO.read(file.toFile());

                if (image != null) {
                    return image;
                }

                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Unsupported icon image: " + file
                );
            } catch (IOException exception) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Failed to read icon: " + file,
                        exception
                );
            }
        }

        final String resourceName =
                "engine/inventory/icons/"
                        + iconId
                        + ".png";

        final ClassLoader contextLoader =
                Thread.currentThread()
                        .getContextClassLoader();

        if (contextLoader != null) {
            final BufferedImage image =
                    readClasspathResource(
                            contextLoader,
                            resourceName
                    );

            if (image != null) {
                return image;
            }
        }

        try (InputStream input =
                     ItemIconCache.class.getResourceAsStream(
                             '/' + resourceName
                     )) {
            if (input != null) {
                return ImageIO.read(input);
            }
        } catch (IOException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Failed to read classpath icon: "
                            + resourceName,
                    exception
            );
        }

        return null;
    }

    private static BufferedImage readClasspathResource(
            ClassLoader loader,
            String resourceName
    ) {
        try (InputStream input =
                     loader.getResourceAsStream(resourceName)) {
            return input == null
                    ? null
                    : ImageIO.read(input);
        } catch (IOException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Failed to read classpath icon: "
                            + resourceName,
                    exception
            );
            return null;
        }
    }

    private static BufferedImage scaleToSquare(
            BufferedImage source,
            int size
    ) {
        final BufferedImage destination =
                new BufferedImage(
                        size,
                        size,
                        BufferedImage.TYPE_INT_ARGB
                );

        final Graphics2D graphics =
                destination.createGraphics();

        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );

            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );

            final int sourceWidth = source.getWidth();
            final int sourceHeight = source.getHeight();

            final double scale = Math.min(
                    size / (double) sourceWidth,
                    size / (double) sourceHeight
            );

            final int width = Math.max(
                    1,
                    (int) Math.round(sourceWidth * scale)
            );

            final int height = Math.max(
                    1,
                    (int) Math.round(sourceHeight * scale)
            );

            graphics.drawImage(
                    source,
                    (size - width) / 2,
                    (size - height) / 2,
                    width,
                    height,
                    null
            );
        } finally {
            graphics.dispose();
        }

        return destination;
    }

    private static String normalizeIconId(String iconId) {
        if (iconId == null) {
            return null;
        }

        final String normalized =
                iconId.trim().replace('\\', '/');

        if (normalized.isEmpty()
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("//")) {
            return null;
        }

        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")) {
                return null;
            }
        }

        return normalized;
    }

    private static Path resolveDefaultRoot() {
        final Path propertyRoot =
                validateDirectory(
                        System.getProperty(
                                "zbuffer.iconRoot"
                        )
                );

        if (propertyRoot != null) {
            return propertyRoot;
        }

        final Path environmentRoot =
                validateDirectory(
                        System.getenv(
                                "ZBUFFER_ICON_ROOT"
                        )
                );

        if (environmentRoot != null) {
            return environmentRoot;
        }

        Path current = Paths.get(
                System.getProperty("user.dir", ".")
        ).toAbsolutePath().normalize();

        for (int i = 0; i < 8 && current != null; i++) {
            final Path candidate =
                    current.resolve("icons");

            if (Files.isDirectory(candidate)) {
                return candidate;
            }

            current = current.getParent();
        }

        return Paths.get("icons")
                .toAbsolutePath()
                .normalize();
    }

    private static Path validateDirectory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            final Path path = Paths
                    .get(value.trim())
                    .toAbsolutePath()
                    .normalize();

            return Files.isDirectory(path)
                    ? path
                    : null;
        } catch (InvalidPathException | SecurityException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Ignoring invalid icon directory: " + value,
                    exception
            );
            return null;
        }
    }

    private static <K, V> Map<K, V> lruMap(
            int maximumSize
    ) {
        return new LinkedHashMap<>(
                16,
                0.75f,
                true
        ) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<K, V> eldest
            ) {
                return size() > maximumSize;
            }
        };
    }

    private record ScaledKey(
            String iconId,
            int size
    ) {
    }
}