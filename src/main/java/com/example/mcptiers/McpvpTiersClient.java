package com.example.mcptiers;
import net.fabricmc.api.ClientModInitializer;
public final class McpvpTiersClient implements ClientModInitializer {
    @Override public void onInitializeClient() { TierLookup.start(); }
}
