package engine.render.lighting;

import engine.render.Material;
import engine.render.Texture;

public final class MaterialCacheEntry {
    public final Material material;
    public final MaterialState state;

    public MaterialCacheEntry(
            Material material,
            MaterialState state
    ) {
        this.material = material;
        this.state = state;
    }

    public boolean matches(
            Material material,
            Texture texture,
            Texture.Wrap wrap,
            int tint,
            double ambient,
            double diffuse,
            int emissive,
            boolean wireframe
    ) {
        return this.material == material &&
                state.texture == texture &&
                state.wrap == wrap &&
                state.tint == tint &&
                Double.doubleToLongBits(state.ambient) ==
                        Double.doubleToLongBits(ambient) &&
                Double.doubleToLongBits(state.diffuse) ==
                        Double.doubleToLongBits(diffuse) &&
                state.emissive == emissive &&
                state.wireframe == wireframe;
    }
}