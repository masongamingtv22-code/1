package com.example.mcptiers;

import net.minecraft.text.Text;

/**
 * Duck interface mixed into EntityRenderState so the tier text found while the render
 * state is updated can be read again when the name tag is drawn.
 * Lives outside the mixin package on purpose (mixin packages cannot be referenced directly).
 */
public interface TierHolder {
    Text mcpvp$getTier();

    void mcpvp$setTier(Text tier);

    boolean mcpvp$isDrawn();

    void mcpvp$setDrawn(boolean drawn);
}
