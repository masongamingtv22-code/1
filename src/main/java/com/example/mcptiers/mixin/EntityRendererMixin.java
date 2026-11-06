package com.example.mcptiers.mixin;

import com.example.mcptiers.TierHolder;
import com.example.mcptiers.TierLookup;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the tier on its own line directly above the player's name tag.
 * Uses the same label queue as the vanilla name tag, so it billboards, fades,
 * and hides exactly like the name does.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    /** One text line is 9px tall; negative y moves the label up. */
    private static final int LINE_UP = -10;

    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void mcpvp$captureTier(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        TierHolder holder = (TierHolder) (Object) state;
        holder.mcpvp$setDrawn(false);
        Text tier = null;
        if (entity instanceof PlayerEntity player) {
            tier = TierLookup.tag(player.getGameProfile().name());
        }
        holder.mcpvp$setTier(tier);
    }

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void mcpvp$renderTierAboveName(EntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                           CameraRenderState cameraState, CallbackInfo ci) {
        TierHolder holder = (TierHolder) (Object) state;
        Text tier = holder.mcpvp$getTier();
        // Only once per state per frame, and only when vanilla is drawing a name tag.
        if (tier == null || holder.mcpvp$isDrawn() || state.nameLabelPos == null || state.displayName == null) return;
        holder.mcpvp$setDrawn(true);
        TierLookup.debug("name tag: drawing " + tier.getString() + " above " + state.displayName.getString());
        int y = "deadmau5".equals(state.displayName.getString()) ? LINE_UP * 2 : LINE_UP;
        queue.submitLabel(matrices, state.nameLabelPos, y, tier, !state.sneaking, state.light,
                state.squaredDistanceToCamera, cameraState);
    }
}
