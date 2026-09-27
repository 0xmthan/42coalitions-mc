package fr.fortytwo.coalitions;

import fr.fortytwo.coalitions.api.Coalition;

/** What a lookup made of a Minecraft name. */
public sealed interface Membership {

    /** The player belongs to a house we recognise. */
    record Member(Coalition coalition) implements Membership {}

    /** The intra has no such login. */
    record UnknownUser(String login) implements Membership {}

    /** The login exists but sits in none of the houses we accept. */
    record NoCoalition(String login) implements Membership {}

    /** The intra could not be reached, so we know nothing either way. */
    record Unavailable(String reason) implements Membership {}

    /** The name is on the bypass list and was never looked up. */
    record Bypassed() implements Membership {}
}
