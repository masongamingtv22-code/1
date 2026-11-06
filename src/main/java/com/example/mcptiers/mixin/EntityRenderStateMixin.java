package com.example.mcptiers.mixin;

import com.example.mcptiers.TierHolder;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements TierHolder {
    @Unique
    private Text mcpvp$tier;
    @Unique
    private boolean mcpvp$drawn;

    @Override
    public Text mcpvp$getTier() {
        return mcpvp$tier;
    }

    @Override
    public void mcpvp$setTier(Text tier) {
        this.mcpvp$tier = tier;
    }

    @Override
    public boolean mcpvp$isDrawn() {
        return mcpvp$drawn;
    }

    @Override
    public void mcpvp$setDrawn(boolean drawn) {
        this.mcpvp$drawn = drawn;
    }
}
