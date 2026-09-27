package fr.fortytwo.coalitions.command;

import fr.fortytwo.coalitions.CoalitionsPlugin;
import fr.fortytwo.coalitions.Membership;
import fr.fortytwo.coalitions.api.Coalition;
import fr.fortytwo.coalitions.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** /coalition -- who is in what, and the two admin knobs. */
public final class CoalitionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> USER_SUBCOMMANDS = List.of("info", "who", "houses");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "refresh", "lookup");

    private final CoalitionsPlugin plugin;

    public CoalitionCommand(CoalitionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "info" -> info(sender, args.length > 1 ? args[1] : null);
            case "who" -> who(sender);
            case "houses", "list" -> houses(sender);
            case "reload" -> {
                if (deniedAdmin(sender)) {
                    return true;
                }
                plugin.reload();
                sender.sendMessage(ok("Reloaded config.yml and .env."));
            }
            case "refresh" -> {
                if (deniedAdmin(sender)) {
                    return true;
                }
                refresh(sender, args.length > 1 ? args[1] : senderName(sender));
            }
            case "lookup" -> {
                if (deniedAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(bad("Usage: /" + label + " lookup <42 login>"));
                    return true;
                }
                lookup(sender, args[1]);
            }
            default -> sender.sendMessage(bad("Usage: /" + label + " <info|who|houses|reload|refresh|lookup>"));
        }
        return true;
    }

    private void info(CommandSender sender, String target) {
        String name = target == null ? senderName(sender) : target;
        if (name == null) {
            sender.sendMessage(bad("From the console, name a player: /coalition info <player>"));
            return;
        }

        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(bad(name + " is not online. Try /coalition lookup " + name.toLowerCase(Locale.ROOT)));
            return;
        }

        Coalition coalition = plugin.roster().of(player.getUniqueId()).orElse(null);
        if (coalition == null) {
            sender.sendMessage(Component.text(player.getName() + " is in no coalition.", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(player.getName() + " -- ", NamedTextColor.GRAY)
                .append(Component.text(coalition.name(), Colors.of(coalition)))
                .append(Component.text(" (" + coalition.slug() + ", " + coalition.color() + ")",
                        NamedTextColor.DARK_GRAY)));
    }

    private void who(CommandSender sender) {
        Map<String, List<String>> byHouse = new TreeMap<>();
        Map<String, Coalition> houses = new TreeMap<>();
        List<String> loose = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Coalition coalition = plugin.roster().of(player.getUniqueId()).orElse(null);
            if (coalition == null) {
                loose.add(player.getName());
            } else {
                houses.put(coalition.name(), coalition);
                byHouse.computeIfAbsent(coalition.name(), key -> new ArrayList<>()).add(player.getName());
            }
        }

        if (byHouse.isEmpty() && loose.isEmpty()) {
            sender.sendMessage(Component.text("Nobody is online.", NamedTextColor.GRAY));
            return;
        }

        byHouse.forEach((name, players) -> {
            Coalition coalition = houses.get(name);
            sender.sendMessage(Component.text(name + " (" + players.size() + ") ", Colors.of(coalition))
                    .append(Component.text(String.join(", ", players), NamedTextColor.GRAY)));
        });
        if (!loose.isEmpty()) {
            sender.sendMessage(Component.text("No coalition (" + loose.size() + ") ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(String.join(", ", loose), NamedTextColor.GRAY)));
        }
    }

    private void houses(CommandSender sender) {
        var all = plugin.service().houses();
        if (all.isEmpty()) {
            sender.sendMessage(bad("No coalitions loaded yet -- check intra.campus-id and the console."));
            return;
        }
        sender.sendMessage(Component.text("Coalitions on this campus:", NamedTextColor.GRAY));
        all.stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .forEach(coalition -> {
                    // The team colour is what name tags and glow outlines get
                    // drawn in, so it is worth seeing next to the real hex.
                    NamedTextColor drawn = Colors.nearest(Colors.of(coalition));
                    sender.sendMessage(Component.text("  [" + plugin.service().tags().of(coalition) + "] ", drawn)
                            .append(Component.text(coalition.name(), Colors.of(coalition)))
                            .append(Component.text(" " + coalition.color() + " -> " + NamedTextColor.NAMES.key(drawn),
                                    NamedTextColor.DARK_GRAY)));
                });
    }

    private void refresh(CommandSender sender, String name) {
        if (name == null) {
            sender.sendMessage(bad("From the console, name a player: /coalition refresh <player>"));
            return;
        }
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(bad(name + " is not online."));
            return;
        }

        plugin.service().cache().invalidate(plugin.service().settings().loginFor(player.getName()));
        sender.sendMessage(ok("Looking " + player.getName() + " up again..."));
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            plugin.resort(player, false);
            sender.sendMessage(ok(player.getName() + " re-sorted."));
        });
    }

    private void lookup(CommandSender sender, String login) {
        sender.sendMessage(Component.text("Asking the intra about " + login + "...", NamedTextColor.GRAY));
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Membership membership = plugin.service().lookup(login, false);
            sender.sendMessage(switch (membership) {
                case Membership.Member member -> Component.text(login + " -- ", NamedTextColor.GRAY)
                        .append(Component.text(member.coalition().name(), Colors.of(member.coalition())));
                case Membership.NoCoalition ignored ->
                        Component.text(login + " is in no coalition of this campus.", NamedTextColor.YELLOW);
                case Membership.UnknownUser ignored ->
                        Component.text("No 42 user named " + login + ".", NamedTextColor.RED);
                case Membership.Bypassed ignored ->
                        Component.text(login + " is on login.bypass.", NamedTextColor.GRAY);
                case Membership.Unavailable unavailable ->
                        Component.text("Intra unreachable: " + unavailable.reason(), NamedTextColor.RED);
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(USER_SUBCOMMANDS);
            if (sender.hasPermission("coalitions.admin")) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
        } else if (args.length == 2 && List.of("info", "refresh").contains(args[0].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private boolean deniedAdmin(CommandSender sender) {
        if (sender.hasPermission("coalitions.admin")) {
            return false;
        }
        sender.sendMessage(bad("You need coalitions.admin for that."));
        return true;
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : null;
    }

    private static Component ok(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    private static Component bad(String text) {
        return Component.text(text, NamedTextColor.RED);
    }
}
