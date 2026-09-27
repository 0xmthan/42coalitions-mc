package fr.fortytwo.coalitions.api;

/**
 * One house. The intra hands the colour out as "#RRGGBB", tuned for the dark
 * website.
 */
public record Coalition(int id, String name, String slug, String color) {

    public Coalition {
        name = name == null ? "" : name;
        slug = slug == null ? "" : slug;
        color = color == null ? "" : color;
    }
}
