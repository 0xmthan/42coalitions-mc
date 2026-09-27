package fr.fortytwo.coalitions;

import fr.fortytwo.coalitions.api.CoalitionsApi;
import fr.fortytwo.coalitions.api.IntraClient;
import fr.fortytwo.coalitions.api.IntraException;
import fr.fortytwo.coalitions.command.CoalitionCommand;
import fr.fortytwo.coalitions.config.DotEnv;
import fr.fortytwo.coalitions.config.Settings;
import fr.fortytwo.coalitions.listener.FriendlyFireListener;
import fr.fortytwo.coalitions.listener.LoginListener;
import fr.fortytwo.coalitions.listener.SessionListener;
import fr.fortytwo.coalitions.team.TeamManager;
import fr.fortytwo.coalitions.util.HouseTags;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Sorts players into their 42 coalition: the intra says who gets in, the
 * coalition says what colour they are and who they cannot hit.
 */
public final class CoalitionsPlugin extends JavaPlugin {

    private final Roster roster = new Roster();
    private final MembershipCache cache = new MembershipCache();
    // Shared across reloads so the tags survive a new service.
    private final HouseTags tags = new HouseTags();

    private Settings settings;
    private CoalitionService service;
    private TeamManager teams;
    private LoginListener logins;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCache();

        settings = readSettings();
        service = new CoalitionService(getLogger(), settings, client(settings), cache, tags);
        teams = new TeamManager(getLogger(), settings, tags);
        wire();

        PluginCommand command = getCommand("coalition");
        if (command != null) {
            CoalitionCommand executor = new CoalitionCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        Bukkit.getServicesManager().register(CoalitionsApi.class, new ApiImpl(this), this, ServicePriority.Normal);

        warnAboutCredentials();
        Bukkit.getAsyncScheduler().runNow(this, task -> loadHouses());
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (teams != null) {
            teams.shutdown();
        }
        roster.clear();
        saveCache();
    }

    /** Re-reads config.yml and .env, then re-sorts everyone who is online. */
    public void reload() {
        reloadConfig();
        settings = readSettings();
        service = new CoalitionService(getLogger(), settings, client(settings), cache, tags);
        teams.settings(settings);
        wire();
        warnAboutCredentials();

        Bukkit.getAsyncScheduler().runNow(this, task -> {
            loadHouses();
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                resort(player, true);
            }
        });
    }

    /** Looks a player up again and re-sorts them. Blocks; run it async. */
    public void resort(Player player, boolean useCache) {
        Membership membership = service.lookup(player.getName(), useCache);
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) {
                return;
            }
            if (membership instanceof Membership.Member member) {
                roster.set(player.getUniqueId(), member.coalition());
                teams.apply(player, member.coalition());
            } else {
                roster.remove(player.getUniqueId());
                teams.clear(player);
            }
        }, null);
    }

    private void wire() {
        HandlerList.unregisterAll(this);
        logins = new LoginListener(getLogger(), service);
        Bukkit.getPluginManager().registerEvents(logins, this);
        Bukkit.getPluginManager().registerEvents(new SessionListener(logins, roster, teams), this);
        Bukkit.getPluginManager().registerEvents(new FriendlyFireListener(service, roster), this);
    }

    private Settings readSettings() {
        DotEnv env;
        try {
            env = DotEnv.load(envCandidates());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not read .env: " + e.getMessage());
            env = emptyEnv();
        }
        if (env.source() != null) {
            getLogger().info("Credentials from " + env.source());
        }
        return Settings.from(getConfig(), env);
    }

    private DotEnv emptyEnv() {
        try {
            return DotEnv.load(List.of());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * plugins/Coalitions/.env, then upwards to the server and repo roots. The
     * data folder is relative, so it is made absolute first or it has no parents.
     */
    private List<Path> envCandidates() {
        Path dataFolder = dataFolder();
        List<Path> candidates = new ArrayList<>();
        candidates.add(dataFolder.resolve(".env"));
        Path dir = dataFolder.getParent();
        for (int level = 0; dir != null && level < 3; level++, dir = dir.getParent()) {
            candidates.add(dir.resolve(".env"));
        }
        return candidates;
    }

    private Path dataFolder() {
        return getDataFolder().getAbsoluteFile().toPath().normalize();
    }

    private IntraClient client(Settings settings) {
        return new IntraClient(settings.baseUrl(), settings.clientId(), settings.clientSecret(),
                settings.timeout(), settings.requestsPerSecond());
    }

    private void warnAboutCredentials() {
        if (service.client().hasCredentials()) {
            return;
        }
        getLogger().severe("No FT_CLIENT_ID / FT_CLIENT_SECRET found. Put them in a .env next to this plugin "
                + "(plugins/Coalitions/.env), in the server root, or in the environment -- "
                + "credentials come from https://profile.intra.42.fr/oauth/applications");
        if (settings.requireCoalition() && !settings.allowOnApiError()) {
            getLogger().severe("Until then nobody can join, because login.require-coalition is on and "
                    + "login.on-api-error is 'deny'. Names in login.bypass still get in.");
        }
    }

    private void loadHouses() {
        try {
            service.loadHouses();
        } catch (IntraException e) {
            getLogger().warning("Could not load the campus coalitions: " + e.getMessage()
                    + " (retrying on the next login)");
            return;
        }
        // Teams are scoreboard state, so back to the main thread. This also
        // re-sorts anyone who joined before the houses were known.
        Bukkit.getScheduler().runTask(this, () -> {
            teams.pruneOrphans(service.houses());
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                roster.of(player.getUniqueId()).ifPresent(house -> teams.apply(player, house));
            }
        });
    }

    private void loadCache() {
        try {
            cache.load(cachePath());
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Ignoring unreadable cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        if (settings == null || !settings.persistCache()) {
            return;
        }
        try {
            cache.save(cachePath());
        } catch (IOException e) {
            getLogger().warning("Could not save the cache: " + e.getMessage());
        }
    }

    private Path cachePath() {
        return dataFolder().resolve("cache.json");
    }

    public CoalitionService service() {
        return service;
    }

    public Roster roster() {
        return roster;
    }

    public TeamManager teams() {
        return teams;
    }
}
