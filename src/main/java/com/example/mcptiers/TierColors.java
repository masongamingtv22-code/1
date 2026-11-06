package com.example.mcptiers;

import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Single source of truth for tier colors. Used by both the tab list and the name tag,
 * so a tier always has the same color everywhere. The color depends only on the tier
 * number, so ST/LT/MT/HT of the same number share one color.
 *
 * 6 (ST6-HT6) brown | 5 (LT5-HT5) blue | 4 (LT4-HT4) red
 * 3 (LT3-HT3) bronze | 2 (LT2-HT2) silver | 1 (LT1-HT1) yellow
 */
public final class TierColors {
    public static final int BROWN = 0x9C6B3C;
    public static final int BLUE = 0x55AAFF;
    public static final int RED = 0xFF5555;
    public static final int BRONZE = 0xCD7F32;
    public static final int SILVER = 0xC0C0C0;
    public static final int YELLOW = 0xFFFF55;
    public static final int WHITE = 0xFFFFFF;

    private TierColors() {}

    public static int colorFor(String tier) {
        if (tier == null) return WHITE;
        String t = tier.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return WHITE;
        return switch (t.charAt(t.length() - 1)) {
            case '6' -> BROWN;
            case '5' -> BLUE;
            case '4' -> RED;
            case '3' -> BRONZE;
            case '2' -> SILVER;
            case '1' -> YELLOW;
            default -> WHITE;
        };
    }

    /** "[HT3]" in the tier's color, no leading space. Used for the name tag line. */
    public static Text tag(String tier) {
        return Text.literal("[" + tier + "]").withColor(colorFor(tier));
    }
}
