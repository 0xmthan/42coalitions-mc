# Coalitions

A Paper plugin that sorts players into their 42 coalition.

- **Only 42 people get in.** Pre-login looks the Minecraft name up on the intra;
  no such login, or no coalition, and they never reach the world.
- **Everyone wears their house.** `[G] mtaheri` above the head, in the tab list and
  in chat, in the coalition's own colour.
- **No hitting your own house.** Arrows, splash potions and TNT included.

Server-side only -- players need nothing but the vanilla client.

## Setup

1. Make an intra app at <https://profile.intra.42.fr/oauth/applications/new>
   (the default `public` scope is enough).
2. Put a `.env` next to the server (copy `.env.example`) and fill it in. It is
   read from `plugins/Coalitions/.env` or the server root; real environment
   variables win over the file.

   ```
   FT_CLIENT_ID=u-s4t2ud-...
   FT_CLIENT_SECRET=s-s4t2ud-...
   FT_CAMPUS_ID=49          # which campus's coalitions count; 49 is Istanbul
   ```
3. Grab `Coalitions-x.y.z.jar` from the
   [releases](https://github.com/0xmthan/42coalitions-mc/releases) and drop it in
   the server's `plugins/` folder.
4. Start the server, then `coalition houses` in the console to check.

## Building

For local testing:

```
./build.sh                         # jar lands in target/Coalitions.jar
./build.sh --install ~/my-server   # also drops it in ~/my-server/plugins
```

It uses the JDK 25+ and Maven already on the machine, or fetches them into
`.tools/` if there are none.

Releases are built by GitHub Actions: push a version tag and the jar is built
and attached to a new release.

```
git tag v1.0.0 && git push origin v1.0.0
```

`.env` is only needed to *run*; the build never reads it. `/coalition reload`
picks up changes to it and to `config.yml` without a restart.

## How a player is sorted

A campus runs one bloc of coalitions per cursus -- the houses cadets are sorted
into, plus a separate set for every piscine -- so **one person is in several
coalitions at once**. The plugin takes the campus's 42cursus bloc from
`/v2/blocs` as the real houses and ignores the rest. Without `FT_CAMPUS_ID`
there is no bloc to filter against and piscine houses slip through, so set it.

Answers are cached (`cache.json`, 6h) and spaced to the intra's 2 req/s, so a
campus reconnecting after a restart does not hit the rate limit.

## Commands

| | |
| --- | --- |
| `/coalition info [player]` | which house someone is in |
| `/coalition who` | everyone online, grouped by house |
| `/coalition houses` | the campus's coalitions and colours |
| `/coalition reload` | re-read `config.yml` and `.env`, re-sort everyone |
| `/coalition refresh [player]` | drop a cached answer and ask again |
| `/coalition lookup <login>` | ask the intra about a login directly |

The first three are `coalitions.use` (everyone), the rest `coalitions.admin`
(operators).

## Config

`config.yml` is commented in full and lands in `server/plugins/Coalitions/` on
first start. Worth knowing:

- `login.bypass` -- names that skip the check. **Put your admin name here**; it
  is the way back in when the intra is down.
- `login.on-api-error` -- `deny` (default) or `allow`, when the intra is down.
- `login.require-coalition` -- `false` lets everyone in, uncoloured.
- `login.aliases` -- Minecraft name to 42 login, when they differ.
- `display.tag-prefix` -- `<tag>` is the house initial, `<coalition>` the full
  name, `<c>...</c>` its colour. `""` for no tag.
- `pvp.friendly-fire` -- `true` to let housemates hit each other after all.

## Two caveats

**Name tags above the head only render the sixteen vanilla colours.** The tag
over a player's head is drawn in their team's colour, and a team colour cannot
be a hex. The name falls back to the nearest vanilla colour; the exact
coalition hex survives in the tab list, in chat and in `display.tag-prefix`.

**`online-mode=false` means a username proves nothing.** The gate keeps out
anyone not on the intra, but cannot stop a player typing someone else's login
and being sorted into their house. Fine for a campus LAN event; it is not
authentication.
