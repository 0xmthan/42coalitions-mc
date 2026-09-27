package fr.fortytwo.coalitions.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * What other plugins may ask about coalitions. Registered with Bukkit's
 * {@code ServicesManager}, so a dependant gets it with
 * {@code getServicesManager().load(CoalitionsApi.class)}.
 */
public interface CoalitionsApi {

    /** The house a player was sorted into, empty if they were let in without one. */
    Optional<Coalition> coalitionOf(UUID player);

    default Optional<Coalition> coalitionOf(Player player) {
        return coalitionOf(player.getUniqueId());
    }

    /** True only when both are in a house and it is the same one. */
    boolean sameHouse(UUID one, UUID other);

    /** Every house this campus plays in. */
    Collection<Coalition> houses();

    /** The house's short tag: "G", "H", or "SE" when two would collide. */
    String tagOf(Coalition house);

    /** The house's exact colour. */
    TextColor colorOf(Coalition house);

    /**
     * The house's colour as the game can draw it on a team -- name tags, glow
     * outlines and the like are limited to the sixteen vanilla colours.
     */
    NamedTextColor teamColorOf(Coalition house);

    /** What goes in front of a name, e.g. "[G] ", empty when tags are off. */
    Component prefixOf(Coalition house);

    /**
     * Mirrors the coalition teams onto another scoreboard, with everyone online
     * on the right one.
     *
     * <p>A plugin that gives players their own scoreboard -- for a sidebar --
     * takes them off the main one, and their coalition team with it. Call this
     * on the new scoreboard to get the colours, tags and friendly fire back.
     */
    void applyTeamsTo(Scoreboard scoreboard);

    /**
     * Puts one player onto their house's team on this scoreboard.
     *
     * <p>Name tags are drawn from the team on the *viewer's* scoreboard, so a
     * plugin holding a scoreboard per player has to add a newcomer to every one
     * of them -- otherwise everybody already online sees them untagged until
     * they reconnect.
     */
    void applyTeamTo(Scoreboard scoreboard, Player player);
}
