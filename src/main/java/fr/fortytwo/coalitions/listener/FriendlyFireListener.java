package fr.fortytwo.coalitions.listener;

import fr.fortytwo.coalitions.CoalitionService;
import fr.fortytwo.coalitions.Roster;
import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.config.Settings;
import fr.fortytwo.coalitions.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No hitting your own house. The team's friendly-fire flag stops a melee hit,
 * but does not follow an arrow, potion or TNT back to whoever let it go.
 */
public final class FriendlyFireListener implements Listener {

    private static final long WARN_COOLDOWN_MILLIS = 3_000;

    private final CoalitionService service;
    private final Roster roster;
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();

    public FriendlyFireListener(CoalitionService service, Roster roster) {
        this.service = service;
        this.roster = roster;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Settings settings = service.settings();
        if (settings.friendlyFire()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = attacker(event.getDamager(), settings.coverIndirect());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (!roster.sameHouse(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        warn(attacker, victim);
    }

    /** Whoever is really behind the damage, as far as we can follow it. */
    private static Player attacker(Entity damager, boolean coverIndirect) {
        if (damager instanceof Player player) {
            return player;
        }
        if (!coverIndirect) {
            return null;
        }
        if (damager instanceof Projectile projectile) {
            return asPlayer(projectile.getShooter());
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return asPlayer(cloud.getSource());
        }
        if (damager instanceof TNTPrimed tnt) {
            return tnt.getSource() instanceof Player player ? player : null;
        }
        return null;
    }

    private static Player asPlayer(ProjectileSource source) {
        return source instanceof Player player ? player : null;
    }

    private void warn(Player attacker, Player victim) {
        Settings settings = service.settings();
        String template = settings.friendlyFireMessage();
        if (template == null || template.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = warned.get(attacker.getUniqueId());
        if (last != null && now - last < WARN_COOLDOWN_MILLIS) {
            return;
        }
        warned.put(attacker.getUniqueId(), now);

        Coalition coalition = roster.of(victim.getUniqueId()).orElse(null);
        attacker.sendActionBar(MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(
                Placeholder.unparsed("target", victim.getName()),
                Placeholder.unparsed("coalition", coalition == null ? "" : coalition.name()),
                Placeholder.styling("c", Colors.of(coalition)))));
    }
}
