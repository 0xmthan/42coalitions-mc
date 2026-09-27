package fr.fortytwo.coalitions;

import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.api.CoalitionsApi;
import fr.fortytwo.coalitions.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** {@link CoalitionsApi} over the plugin's own roster and teams. */
final class ApiImpl implements CoalitionsApi {

    private final CoalitionsPlugin plugin;

    ApiImpl(CoalitionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Optional<Coalition> coalitionOf(UUID player) {
        return plugin.roster().of(player);
    }

    @Override
    public boolean sameHouse(UUID one, UUID other) {
        return plugin.roster().sameHouse(one, other);
    }

    @Override
    public Collection<Coalition> houses() {
        return plugin.service().houses();
    }

    @Override
    public String tagOf(Coalition house) {
        return plugin.service().tags().of(house);
    }

    @Override
    public TextColor colorOf(Coalition house) {
        return Colors.of(house);
    }

    @Override
    public NamedTextColor teamColorOf(Coalition house) {
        return Colors.nearest(Colors.of(house));
    }

    @Override
    public Component prefixOf(Coalition house) {
        return plugin.teams().prefix(house);
    }

    @Override
    public void applyTeamTo(Scoreboard scoreboard, Player player) {
        coalitionOf(player.getUniqueId()).ifPresent(house ->
                plugin.teams().ensure(scoreboard, house).addEntry(player.getName()));
    }

    @Override
    public void applyTeamsTo(Scoreboard scoreboard) {
        for (Coalition house : houses()) {
            plugin.teams().ensure(scoreboard, house);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            coalitionOf(player.getUniqueId()).ifPresent(house ->
                    plugin.teams().ensure(scoreboard, house).addEntry(player.getName()));
        }
    }
}
