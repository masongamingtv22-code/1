package com.example.mcptiers.mixin;

import com.example.mcptiers.TierLookup;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void mcpvp$appendTier(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text name = cir.getReturnValue();
        if (name == null) return;
        String player = TierLookup.resolveName(entry.getProfile().name(), name);
        Text tier = TierLookup.suffix(player);
        if (tier != null && !tier.getString().isEmpty()) {
            TierLookup.debug("tab list: adding tier to " + player + " -> " + tier.getString().trim());
            cir.setReturnValue(name.copy().append(tier));
        }
    }
}
