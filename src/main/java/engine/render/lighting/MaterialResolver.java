package engine.render.lighting;

import engine.render.Material;
import engine.render.Texture;
import java.util.IdentityHashMap;
import objects.GameObject;

public final class MaterialResolver {
    private final IdentityHashMap<
            GameObject,
            MaterialCacheEntry
            > materialStateCache =
            new IdentityHashMap<>(512);

    public MaterialState getMaterialState(GameObject object) {
        final Material material = object.getMaterial();
        final boolean wireframe = object.isWireframe();
        final Texture texture =
                !wireframe && material != null
                        ? material.getAlbedo()
                        : null;
        final Texture.Wrap wrap =
                material != null
                        ? material.getWrap()
                        : Texture.Wrap.REPEAT;
        final java.awt.Color tintColor =
                material != null
                        ? material.getTint()
                        : object.getColor();
        final int tint =
                tintColor == null
                        ? 0xFFFFFF
                        : tintColor.getRGB() & 0xFFFFFF;
        final double ambient =
                material != null
                        ? material.getAmbient()
                        : 0.20;
        final double diffuse =
                material != null
                        ? material.getDiffuse()
                        : 0.85;

        int emissive = 0;

        if (
                material != null &&
                        material.getEmissiveStrength() > 0.0
        ) {
            final double strength =
                    material.getEmissiveStrength();
            final java.awt.Color color =
                    material.getEmissiveColor();

            if (color != null) {
                emissive =
                        clamp255(
                                (int) Math.round(
                                        color.getRed() *
                                                strength
                                )
                        ) << 16 |
                                clamp255(
                                        (int) Math.round(
                                                color.getGreen() *
                                                        strength
                                        )
                                ) << 8 |
                                clamp255(
                                        (int) Math.round(
                                                color.getBlue() *
                                                        strength
                                        )
                                );
            }
        }

        final Texture.Wrap validatedWrap =
                wrap == null
                        ? Texture.Wrap.REPEAT
                        : wrap;
        MaterialCacheEntry entry =
                materialStateCache.get(object);

        if (
                entry != null &&
                        entry.matches(
                                material,
                                texture,
                                validatedWrap,
                                tint,
                                ambient,
                                diffuse,
                                emissive,
                                wireframe
                        )
        ) {
            return entry.state;
        }

        final MaterialState state = new MaterialState(
                texture,
                validatedWrap,
                tint,
                ambient,
                diffuse,
                emissive,
                wireframe
        );

        entry = new MaterialCacheEntry(material, state);
        materialStateCache.put(object, entry);
        return state;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}