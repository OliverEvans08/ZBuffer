package engine.render.lighting;

import engine.render.Texture;

public final class MaterialState {
    public final Texture texture;
    public final Texture.Wrap wrap;
    public final int[] texturePixels;
    public final int textureWidth;
    public final int textureHeight;
    public final int maximumTextureX;
    public final int maximumTextureY;
    public final int textureWidthMask;
    public final int textureHeightMask;
    public final boolean repeatTexture;
    public final int tint;
    public final double ambient;
    public final double diffuse;
    public final int emissive;
    public final boolean wireframe;

    public MaterialState(
            Texture texture,
            Texture.Wrap wrap,
            int tint,
            double ambient,
            double diffuse,
            int emissive,
            boolean wireframe
    ) {
        this.texture = texture;
        this.wrap = wrap;
        this.tint = tint;
        this.ambient = ambient;
        this.diffuse = diffuse;
        this.emissive = emissive;
        this.wireframe = wireframe;
        this.repeatTexture =
                wrap == Texture.Wrap.REPEAT;

        if (
                texture != null &&
                        texture.argb != null &&
                        texture.width > 0 &&
                        texture.height > 0 &&
                        (long) texture.width *
                                texture.height <=
                                texture.argb.length
        ) {
            texturePixels = texture.argb;
            textureWidth = texture.width;
            textureHeight = texture.height;
            maximumTextureX = textureWidth - 1;
            maximumTextureY = textureHeight - 1;
            textureWidthMask =
                    (textureWidth & maximumTextureX) == 0
                            ? maximumTextureX
                            : -1;
            textureHeightMask =
                    (textureHeight & maximumTextureY) == 0
                            ? maximumTextureY
                            : -1;
        } else {
            texturePixels = null;
            textureWidth = 0;
            textureHeight = 0;
            maximumTextureX = 0;
            maximumTextureY = 0;
            textureWidthMask = -1;
            textureHeightMask = -1;
        }
    }
}