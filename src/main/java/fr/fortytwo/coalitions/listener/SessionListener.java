package fr.fortytwo.coalitions.listener;

import fr.fortytwo.coalitions.Roster;
import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.team.TeamManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Applies what pre-login worked out, once the player is really in the world. */
public final class SessionListener implements Listener {

    private final LoginListener logins;
    private final Roster roster;
    private final TeamManager teams;

    public SessionListener(LoginListener logins, Roster roster, TeamManager teams) {
        this.logins = logins;
        this.roster = roster;
        this.teams = teams;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Coalition coalition = logins.take(player.getUniqueId());
        if (coalition == null) {
            return;
        }
        roster.set(player.getUniqueId(), coalition);
        teams.apply(player, coalition);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        logins.forget(player.getUniqueId());
        roster.remove(player.getUniqueId());
        teams.clear(player);
    }
}
