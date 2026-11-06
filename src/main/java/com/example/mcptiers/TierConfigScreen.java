package com.example.mcptiers;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class TierConfigScreen extends Screen {
    private static final String[] OPTIONS = TierLookup.getOptions();
    private static final String[] SOURCES = TierLookup.getSources();
    private final Screen parent;
    private int selectedTier;
    private int selectedSource;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("MCPVP Tiers Settings"));
        this.parent = parent;

        String currentTier = TierLookup.getConfiguredTier();
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].equalsIgnoreCase(currentTier)) {
                selectedTier = i;
                break;
            }
        }

        String currentSource = TierLookup.getConfiguredSource();
        for (int i = 0; i < SOURCES.length; i++) {
            if (SOURCES[i].equalsIgnoreCase(currentSource)) {
                selectedSource = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        // Button 1: choose the tier/mode to display.
        addDrawableChild(ButtonWidget.builder(tierLabel(), button -> {
            selectedTier = (selectedTier + 1) % OPTIONS.length;
            TierLookup.setConfiguredTier(OPTIONS[selectedTier]);
            button.setMessage(tierLabel());
        }).dimensions(width / 2 - 110, height / 2 - 25, 220, 20).build());

        // Button 2: choose the tier-list provider. MCPVP is the default.
        addDrawableChild(ButtonWidget.builder(sourceLabel(), button -> {
            selectedSource = (selectedSource + 1) % SOURCES.length;
            TierLookup.setConfiguredSource(SOURCES[selectedSource]);
            button.setMessage(sourceLabel());
        }).dimensions(width / 2 - 110, height / 2 + 2, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(width / 2 - 50, height / 2 + 32, 100, 20).build());
    }

    private Text tierLabel() {
        String value = OPTIONS[selectedTier];
        String display = value.equals("highest") ? "Highest" : value.equals("overall") ? "Overall" : value;
        return Text.literal("Tier to show: " + display);
    }

    private Text sourceLabel() {
        return Text.literal("Source: " + SOURCES[selectedSource]);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Choose the tier and ranking source to display"),
                width / 2, height / 2 - 52, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
