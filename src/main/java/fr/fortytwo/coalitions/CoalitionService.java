package fr.fortytwo.coalitions;

import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.api.IntraClient;
import fr.fortytwo.coalitions.api.IntraException;
import fr.fortytwo.coalitions.config.Settings;
import fr.fortytwo.coalitions.util.HouseTags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a Minecraft name into the house its owner belongs to. The campus's
 * 42cursus bloc is the set we accept -- a cadet's piscine coalitions are not
 * houses. Blocks on the network; call it off the server thread.
 */
public final class CoalitionService {

    private final Logger logger;
    private final Settings settings;
    private final IntraClient client;
    private final MembershipCache cache;
    private final HouseTags tags;
    private final Map<Integer, Coalition> houses = new ConcurrentHashMap<>();

    public CoalitionService(Logger logger, Settings settings, IntraClient client,
                            MembershipCache cache, HouseTags tags) {
        this.logger = logger;
        this.settings = settings;
        this.client = client;
        this.cache = cache;
        this.tags = tags;
    }

    /** Loads the campus's houses; without a campus id they are learnt on join. */
    public void loadHouses() throws IntraException {
        if (settings.campusId() <= 0) {
            logger.warning("intra.campus-id is not set: every coalition a player belongs to is accepted, "
                    + "piscine houses included. Set it (or FT_CAMPUS_ID) to sort players properly.");
            return;
        }

        List<Coalition> fetched = client.campusCoalitions(settings.campusId(), settings.cursusId());
        Map<Integer, Coalition> loaded = new ConcurrentHashMap<>();
        for (Coalition coalition : fetched) {
            if (settings.allows(coalition.id(), coalition.slug(), coalition.name())) {
                loaded.put(coalition.id(), withOverride(coalition));
            }
        }

        if (loaded.isEmpty()) {
            throw new IntraException("campus " + settings.campusId() + " has no cursus "
                    + settings.cursusId() + " coalitions matching coalitions.allow", 0);
        }

        houses.clear();
        houses.putAll(loaded);
        tags.recompute(houses.values());
        logger.info("Loaded " + houses.size() + " coalitions for campus " + settings.campusId() + ": " + names());
    }

    /** What we know about a Minecraft name. Blocks. */
    public Membership lookup(String minecraftName, boolean useCache) {
        if (settings.isBypassed(minecraftName)) {
            return new Membership.Bypassed();
        }

        String login = settings.loginFor(minecraftName);
        if (!isPlausibleLogin(login)) {
            return new Membership.UnknownUser(login);
        }

        if (useCache) {
            Integer cached = cache.get(login);
            if (cached != null) {
                if (cached == 0) {
                    return new Membership.NoCoalition(login);
                }
                Coalition known = houses.get(cached);
                if (known != null) {
                    return new Membership.Member(known);
                }
                // The house went away with a config change; look it up again.
            }
        }

        if (houses.isEmpty() && settings.campusId() > 0) {
            try {
                loadHouses();
            } catch (IntraException e) {
                return new Membership.Unavailable(e.getMessage());
            }
        }

        List<Coalition> candidates;
        try {
            candidates = client.coalitionsOf(login);
        } catch (IntraException e) {
            if (e.notFound()) {
                cache.put(login, 0, settings.negativeCacheTtl());
                return new Membership.UnknownUser(login);
            }
            logger.log(Level.WARNING, "Intra lookup for " + login + " failed: " + e.getMessage());
            return new Membership.Unavailable(e.getMessage());
        }

        Optional<Coalition> picked = pick(candidates);
        if (picked.isEmpty()) {
            cache.put(login, 0, settings.negativeCacheTtl());
            return new Membership.NoCoalition(login);
        }

        Coalition coalition = picked.get();
        if (houses.putIfAbsent(coalition.id(), coalition) == null) {
            // A house we had not met: without a campus id they turn up one
            // player at a time, and a new one can change everyone's tag.
            tags.recompute(houses.values());
        }
        cache.put(login, coalition.id(), settings.cacheTtl());
        return new Membership.Member(coalition);
    }

    /** The house to sort into: "coalitions.preferred" first, else the first match. */
    private Optional<Coalition> pick(List<Coalition> candidates) {
        List<Coalition> eligible = new ArrayList<>();
        for (Coalition candidate : candidates) {
            if (!settings.allows(candidate.id(), candidate.slug(), candidate.name())) {
                continue;
            }
            // With no campus id there is no bloc to check against, so anything
            // the player belongs to counts.
            if (houses.isEmpty() || houses.containsKey(candidate.id())) {
                eligible.add(withOverride(candidate));
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        for (String preferred : settings.preferred()) {
            for (Coalition coalition : eligible) {
                if (matches(coalition, preferred)) {
                    return Optional.of(coalition);
                }
            }
        }
        return Optional.of(eligible.get(0));
    }

    private Coalition withOverride(Coalition coalition) {
        String override = settings.colorOverrides().get(coalition.slug().toLowerCase(Locale.ROOT));
        if (override == null) {
            override = settings.colorOverrides().get(coalition.name().toLowerCase(Locale.ROOT));
        }
        return override == null ? coalition
                : new Coalition(coalition.id(), coalition.name(), coalition.slug(), override);
    }

    private static boolean matches(Coalition coalition, String needle) {
        return coalition.slug().equalsIgnoreCase(needle)
                || coalition.name().equalsIgnoreCase(needle)
                || String.valueOf(coalition.id()).equals(needle);
    }

    /** 42 logins are lowercase, digits and dashes; junk names cost no request. */
    private static boolean isPlausibleLogin(String login) {
        if (login.isEmpty() || login.length() > 32) {
            return false;
        }
        for (int i = 0; i < login.length(); i++) {
            char c = login.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    public Collection<Coalition> houses() {
        return houses.values();
    }

    public Optional<Coalition> house(int id) {
        return Optional.ofNullable(houses.get(id));
    }

    public String names() {
        return houses.values().stream().map(Coalition::name).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }

    public HouseTags tags() {
        return tags;
    }

    public MembershipCache cache() {
        return cache;
    }

    public Settings settings() {
        return settings;
    }

    public IntraClient client() {
        return client;
    }
}
