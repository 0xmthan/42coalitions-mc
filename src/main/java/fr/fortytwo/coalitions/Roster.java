package fr.fortytwo.coalitions;

import fr.fortytwo.coalitions.api.Coalition;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Which house everyone currently online was sorted into. */
public final class Roster {

    private final Map<UUID, Coalition> byPlayer = new ConcurrentHashMap<>();

    public void set(UUID player, Coalition coalition) {
        byPlayer.put(player, coalition);
    }

    public Optional<Coalition> of(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public void remove(UUID player) {
        byPlayer.remove(player);
    }

    public void clear() {
        byPlayer.clear();
    }

    /** True only when both players are in a house and it is the same one. */
    public boolean sameHouse(UUID one, UUID other) {
        Coalition a = byPlayer.get(one);
        Coalition b = byPlayer.get(other);
        return a != null && b != null && a.id() == b.id();
    }
}
