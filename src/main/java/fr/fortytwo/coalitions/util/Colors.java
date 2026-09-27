package fr.fortytwo.coalitions.util;

import fr.fortytwo.coalitions.api.Coalition;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Coalition hex, and what the game can actually draw with it. */
public final class Colors {

    private Colors() {
    }

    /** A coalition's colour, white when the intra gave us nothing usable. */
    public static TextColor of(Coalition coalition) {
        TextColor parsed = coalition == null ? null : TextColor.fromHexString(normalise(coalition.color()));
        return parsed == null ? NamedTextColor.WHITE : parsed;
    }

    /**
     * The closest of the sixteen vanilla colours: a name tag is drawn in its
     * team's colour, and a team colour cannot be a hex.
     */
    public static NamedTextColor nearest(TextColor color) {
        return NamedTextColor.nearestTo(color);
    }

    public static String hex(TextColor color) {
        return color.asHexString();
    }

    private static String normalise(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("#") ? trimmed : "#" + trimmed;
    }
}
