package com.example.mcptiers;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class TierConfigScreen extends Screen {
    private static final String[] OPTIONS = TierLookup.getOptions();
    private final Screen parent;
    private int selected;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("MCPVP Tiers Settings"));
        this.parent = parent;
        String current = TierLookup.getConfiguredTier();
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].equalsIgnoreCase(current)) {
                selected = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(optionLabel(), button -> {
            selected = (selected + 1) % OPTIONS.length;
            // Saved right away, so the choice sticks even if the screen is closed with Esc.
            TierLookup.setConfiguredTier(OPTIONS[selected]);
            button.setMessage(optionLabel());
        }).dimensions(width / 2 - 100, height / 2 - 10, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(width / 2 - 50, height / 2 + 20, 100, 20).build());
    }

    private Text optionLabel() {
        String value = OPTIONS[selected];
        String display = value.equals("highest") ? "Highest" : value.equals("overall") ? "Overall" : value;
        return Text.literal("Tier to show: " + display);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Choose which tier shows above name tags and in the tab list"),
                width / 2, height / 2 - 35, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
