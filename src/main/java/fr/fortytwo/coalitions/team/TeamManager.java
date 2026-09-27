package fr.fortytwo.coalitions.team;

import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.config.Settings;
import fr.fortytwo.coalitions.util.Colors;
import fr.fortytwo.coalitions.util.HouseTags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Puts players on their coalition's scoreboard team -- what colours the name
 * above the head and turns friendly fire off for the house.
 */
public final class TeamManager {

    /** Vanilla caps a team name at sixteen characters. */
    private static final int MAX_TEAM_NAME = 16;
    private static final String PREFIX = "co_";

    private final Logger logger;
    private final HouseTags tags;
    private final Set<String> managed = new HashSet<>();
    private Settings settings;

    public TeamManager(Logger logger, Settings settings, HouseTags tags) {
        this.logger = logger;
        this.settings = settings;
        this.tags = tags;
    }

    public void settings(Settings settings) {
        this.settings = settings;
    }

    /** Sorts a player into their house and paints their name. */
    public void apply(Player player, Coalition coalition) {
        TextColor color = Colors.of(coalition);
        Component prefix = prefix(coalition, color);
        Team team = team(coalition);
        if (team != null && !team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        // The name above the head gets the prefix from the team; the tab list
        // and chat draw the name we hand them, so it carries its own copy.
        Component name = Component.empty()
                .append(prefix)
                .append(Component.text(player.getName(), color));
        if (settings.colorNameTags()) {
            player.playerListName(name);
        }
        // The vanilla chat renderer draws the display name, so this is all the
        // colouring chat needs.
        if (settings.colorChat()) {
            player.displayName(name);
        }
    }

    /** Takes a player back out, on quit or when their house changed. */
    public void clear(Player player) {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return;
        }
        Team team = scoreboard.getEntryTeam(player.getName());
        if (team != null && managed.contains(team.getName())) {
            team.removeEntry(player.getName());
        }
        player.playerListName(null);
        player.displayName(null);
    }

    /** Drops the teams we made, so a reload or a disable leaves no litter. */
    public void shutdown() {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return;
        }
        for (String name : managed) {
            Team team = scoreboard.getTeam(name);
            if (team != null) {
                team.unregister();
            }
        }
        managed.clear();
    }

    /** Get-or-create the house's team on any scoreboard, styled to match. */
    public Team ensure(Scoreboard scoreboard, Coalition coalition) {
        String name = teamName(coalition);
        Team team = scoreboard.getTeam(name);
        if (team == null) {
            team = scoreboard.registerNewTeam(name);
        }
        team.color(Colors.nearest(Colors.of(coalition)));
        team.setAllowFriendlyFire(settings.friendlyFire());
        team.prefix(prefix(coalition, Colors.of(coalition)));
        return team;
    }

    /** What goes in front of a name, e.g. "[G] ". */
    public Component prefix(Coalition coalition) {
        return prefix(coalition, Colors.of(coalition));
    }

    private Team team(Coalition coalition) {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return null;
        }
        managed.add(teamName(coalition));
        return ensure(scoreboard, coalition);
    }

    private Component prefix(Coalition coalition, TextColor color) {
        String template = settings.tagPrefix();
        if (template == null || template.isBlank()) {
            return Component.empty();
        }
        return MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(
                Placeholder.unparsed("tag", tags.of(coalition)),
                Placeholder.unparsed("coalition", coalition.name()),
                Placeholder.styling("c", color)));
    }

    /**
     * A house's team name, inside the sixteen characters vanilla allows.
     *
     * <p>The id leads, because it is the only part guaranteed unique and short:
     * a campus's slugs share a long prefix -- "42cursus-istanbul-slytherin" and
     * "42cursus-istanbul-hufflepuff" -- so cutting the slug to sixteen gave
     * every house the same name, put everyone on one team, and with it one
     * colour, one tag, and friendly fire off between all of them. Whatever room
     * is left after the id carries the name, to keep /team list readable.
     */
    private String teamName(Coalition coalition) {
        String head = PREFIX + coalition.id() + "_";
        StringBuilder out = new StringBuilder(head);
        for (char c : coalition.name().toLowerCase(Locale.ROOT).toCharArray()) {
            if (out.length() >= MAX_TEAM_NAME) {
                break;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Unregisters coalition teams that are not ours any more -- houses that
     * left the config, and the single squashed team left by the name collision
     * above. Players move teams on their own when they are added to a new one,
     * so this only clears what is left behind.
     */
    public void pruneOrphans(Collection<Coalition> houses) {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return;
        }
        Set<String> keep = new HashSet<>();
        for (Coalition house : houses) {
            keep.add(teamName(house));
        }
        for (Team team : Set.copyOf(scoreboard.getTeams())) {
            if (team.getName().startsWith(PREFIX) && !keep.contains(team.getName())) {
                logger.info("Removing stale coalition team " + team.getName());
                team.unregister();
                managed.remove(team.getName());
            }
        }
    }

    private Scoreboard scoreboard() {
        var manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            logger.warning("No scoreboard manager yet; name tags will be coloured on the next join.");
            return null;
        }
        return manager.getMainScoreboard();
    }
}
