package fr.fortytwo.coalitions.listener;

import fr.fortytwo.coalitions.CoalitionService;
import fr.fortytwo.coalitions.Membership;
import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The gate. Pre-login is off the server thread -- where a blocking intra call
 * belongs -- and early enough that a refused player never enters the world.
 */
public final class LoginListener implements Listener {

    private final Logger logger;
    private final CoalitionService service;
    private final Map<UUID, Coalition> resolved = new ConcurrentHashMap<>();

    public LoginListener(Logger logger, CoalitionService service) {
        this.logger = logger;
        this.service = service;
    }

    /** The house resolved for a player at pre-login, consumed once on join. */
    public Coalition take(UUID player) {
        return resolved.remove(player);
    }

    public void forget(UUID player) {
        resolved.remove(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        Settings settings = service.settings();
        String name = event.getName();
        Membership membership = service.lookup(name, true);

        switch (membership) {
            case Membership.Member member -> {
                resolved.put(event.getUniqueId(), member.coalition());
                logger.info(name + " joined as " + member.coalition().name());
            }
            case Membership.Bypassed ignored ->
                    logger.info(name + " is on login.bypass; no coalition check");
            case Membership.UnknownUser unknown -> {
                if (settings.requireCoalition()) {
                    deny(event, settings.unknownUserMessage(), name, "");
                    logger.info("Refused " + name + ": no 42 user named " + unknown.login());
                }
            }
            case Membership.NoCoalition none -> {
                if (settings.requireCoalition()) {
                    deny(event, settings.noCoalitionMessage(), name, "");
                    logger.info("Refused " + name + ": " + none.login() + " is in no coalition of this campus");
                }
            }
            case Membership.Unavailable unavailable -> {
                if (settings.requireCoalition() && !settings.allowOnApiError()) {
                    deny(event, settings.apiErrorMessage(), name, unavailable.reason());
                    logger.warning("Refused " + name + ": intra unreachable (" + unavailable.reason() + ")");
                } else {
                    logger.warning("Letting " + name + " in uncoloured: intra unreachable ("
                            + unavailable.reason() + ")");
                }
            }
        }
    }

    private void deny(AsyncPlayerPreLoginEvent event, String template, String name, String error) {
        Component message = MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("error", error == null ? "" : error)));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
    }
}
