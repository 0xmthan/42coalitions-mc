package fr.fortytwo.coalitions.util;

import fr.fortytwo.coalitions.api.Coalition;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The short tag in front of a name: a house's initial, widened to two letters
 * for names that would share one -- G, H, R, S, but SE and SA for a Serpent
 * and a Salamander. Built from the distinct names, which repeat across blocs.
 */
public final class HouseTags {

    private volatile Map<String, String> byName = Map.of();

    /** Works the tags out afresh for the houses we know about. */
    public void recompute(Collection<Coalition> houses) {
        Map<String, Integer> initials = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Coalition house : houses) {
            if (names.add(house.name())) {
                initials.merge(abbrev(house.name(), 1), 1, Integer::sum);
            }
        }

        Map<String, String> tags = new TreeMap<>();
        for (String name : names) {
            String tag = abbrev(name, 1);
            tags.put(name, initials.getOrDefault(tag, 0) > 1 ? abbrev(name, 2) : tag);
        }
        byName = Map.copyOf(tags);
    }

    /** A house's tag, falling back to its initial when we have not seen it yet. */
    public String of(Coalition house) {
        if (house == null) {
            return "?";
        }
        String tag = byName.get(house.name());
        return tag == null ? abbrev(house.name(), 1) : tag;
    }

    /** The first n letters of a name, upper-cased: "Gryffindor" -> "G". */
    private static String abbrev(String name, int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < name.length() && out.length() < n; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        return out.isEmpty() ? "?" : out.toString();
    }
}
