package dev.effecttrace;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EffectTrace: works out WHO is removing or cancelling a potion effect (Strength by default).
 *
 * Every time a player's Strength effect is added, changed, cleared or removed, it writes to the console and to
 * plugins/EffectTrace/trace.log:
 *   - what happened and why (the Bukkit "cause": POTION_DRINK, COMMAND, PLUGIN, EXPIRATION ...),
 *   - at which listener priority the event got cancelled, and which plugins have listeners at that priority,
 *   - which plugins are on the call stack when something removes the effect (that is the plugin doing it),
 *   - whether the effect was still there 3 seconds later, and whether it vanished with no event at all.
 * It also lists every plugin that listens to potion events and every plugin with scheduled tasks.
 */
public final class EffectTrace extends JavaPlugin implements Listener, TabExecutor {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private PotionEffectType watched = PotionEffectType.STRENGTH; // null = every effect
    private PrintWriter out;
    private long tick;
    private BukkitTask watcher;

    private final Deque<Trace> open = new ArrayDeque<>();
    private final Map<UUID, Long> lastEventTick = new HashMap<>();
    private final Map<UUID, PotionEffect> lastSeen = new HashMap<>();

    private static final class Trace {
        final List<String> priorities = new ArrayList<>();
        final List<Boolean> cancelled = new ArrayList<>();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        File dir = getDataFolder();
        dir.mkdirs();
        try {
            out = new PrintWriter(new FileWriter(new File(dir, "trace.log"), true), true);
        } catch (IOException e) {
            getLogger().warning("Could not open trace.log: " + e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("effecttrace");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        watcher = Bukkit.getScheduler().runTaskTimer(this, this::watchTick, 1L, 1L);
        log("EffectTrace started. Watching: " + (watched == null ? "ALL effects" : name(watched)) + ". Output also goes to plugins/EffectTrace/trace.log");
    }

    @Override
    public void onDisable() {
        if (watcher != null) watcher.cancel();
        if (out != null) out.close();
    }

    private void log(String line) {
        String stamped = "[" + LocalTime.now().format(TIME) + "] " + line;
        getLogger().info(line);
        if (out != null) out.println(stamped);
    }

    private static String name(PotionEffectType t) {
        return t == null ? "?" : t.getKey().getKey();
    }

    private static String fmt(PotionEffect e) {
        if (e == null) return "none";
        String dur = e.getDuration() < 0 ? "infinite" : String.format(Locale.ROOT, "%.1fs", e.getDuration() / 20.0);
        return name(e.getType()) + " " + (e.getAmplifier() + 1) + " (" + dur + ")";
    }

    // ------------------------------------------------------------------ the potion event, seen at every priority

    private boolean relevant(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player)) return false;
        if (watched == null) return true;
        PotionEffectType t = e.getModifiedType();
        return t != null && t.equals(watched);
    }

    private void step(String priority, EntityPotionEffectEvent e) {
        if (!relevant(e)) return;
        Trace t = open.peek();
        if (t == null) return;
        t.priorities.add(priority);
        t.cancelled.add(e.isCancelled());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void lowest(EntityPotionEffectEvent e) {
        if (!relevant(e)) return;
        if (open.size() > 40) open.clear();
        open.push(new Trace());
        step("LOWEST", e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void low(EntityPotionEffectEvent e) {
        step("LOW", e);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void normal(EntityPotionEffectEvent e) {
        step("NORMAL", e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void high(EntityPotionEffectEvent e) {
        step("HIGH", e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void highest(EntityPotionEffectEvent e) {
        step("HIGHEST", e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void monitor(EntityPotionEffectEvent e) {
        if (!relevant(e)) return;
        step("MONITOR", e);
        Trace t = open.poll();
        Player p = (Player) e.getEntity();
        lastEventTick.put(p.getUniqueId(), tick);

        log(p.getName() + ": " + name(e.getModifiedType()) + " " + e.getAction() + "  cause=" + e.getCause()
                + "  cancelled=" + e.isCancelled() + "  old=" + fmt(e.getOldEffect()) + "  new=" + fmt(e.getNewEffect()));

        if (t != null) {
            StringBuilder sb = new StringBuilder("    cancelled? ");
            for (int i = 0; i < t.priorities.size(); i++) {
                sb.append(t.priorities.get(i)).append('=').append(t.cancelled.get(i) ? "YES" : "no").append("  ");
            }
            log(sb.toString().trim());
            int flip = -1;
            for (int i = 0; i < t.cancelled.size(); i++) {
                if (t.cancelled.get(i)) {
                    flip = i;
                    break;
                }
            }
            if (flip >= 0) {
                String at = t.priorities.get(flip);
                String before = flip > 0 ? t.priorities.get(flip - 1) : at;
                Set<String> suspects = new LinkedHashSet<>();
                suspects.addAll(listenersAt(before));
                suspects.addAll(listenersAt(at));
                log("    -> the event was cancelled between " + before + " and " + at + ". Suspects (plugins listening to this event at those priorities): "
                        + (suspects.isEmpty() ? "none found - it may have been cancelled before dispatch" : String.join(", ", suspects)));
            }
        }

        String plugins = pluginsOnStack();
        log("    plugins on the call stack: " + (plugins.isEmpty() ? "none (vanilla / server code)" : plugins));
        EntityPotionEffectEvent.Action action = e.getAction();
        boolean removal = action == EntityPotionEffectEvent.Action.CLEARED || action == EntityPotionEffectEvent.Action.REMOVED;
        if (removal || e.isCancelled()) {
            for (String line : condensedStack()) log("      at " + line);
        }

        boolean added = action == EntityPotionEffectEvent.Action.ADDED || action == EntityPotionEffectEvent.Action.CHANGED;
        if (added && !e.isCancelled()) {
            final PotionEffectType type = e.getModifiedType();
            final String who = p.getName();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player now = Bukkit.getPlayerExact(who);
                if (now == null) return;
                PotionEffect still = now.getPotionEffect(type);
                if (still == null) {
                    log(who + ": 3 seconds later the SERVER has no " + name(type) + " any more (it was removed).");
                } else {
                    log(who + ": 3 seconds later the server STILL has " + fmt(still)
                            + ". If the player sees nothing on screen / feels no damage, something is hiding or ignoring it (packet plugin or damage plugin).");
                }
            }, 60L);
        }
    }

    // ------------------------------------------------------------------ silent removal watcher

    private void watchTick() {
        tick++;
        if (watched == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            PotionEffect now = p.getPotionEffect(watched);
            PotionEffect before = lastSeen.get(id);
            if (before != null && now == null) {
                Long evt = lastEventTick.get(id);
                boolean explained = evt != null && tick - evt <= 2;
                boolean expired = before.getDuration() >= 0 && before.getDuration() <= 40;
                if (!explained && !expired) {
                    log(p.getName() + ": " + name(watched) + " VANISHED with no Bukkit potion event (it had " + fmt(before)
                            + " left). Something removed it behind the API's back (direct server-internals code), or the player died / changed world / was teleported by a plugin that resets players.");
                }
            }
            if (now != null) lastSeen.put(id, now);
            else lastSeen.remove(id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> listenersAt(String priority) {
        Set<String> out = new LinkedHashSet<>();
        for (RegisteredListener rl : EntityPotionEffectEvent.getHandlerList().getRegisteredListeners()) {
            if (rl.getPlugin() == this) continue;
            if (rl.getPriority().name().equals(priority)) out.add(rl.getPlugin().getName());
        }
        return out;
    }

    private String pluginsOnStack() {
        Set<String> names = new LinkedHashSet<>();
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).forEach(f -> {
            Class<?> c = f.getDeclaringClass();
            if (c.getName().startsWith("dev.effecttrace")) return;
            try {
                Plugin pl = JavaPlugin.getProvidingPlugin(c);
                if (pl != this) names.add(pl.getName());
            } catch (Throwable ignored) {
                // not a plugin class
            }
        });
        return String.join(", ", names);
    }

    private List<String> condensedStack() {
        List<String> lines = new ArrayList<>();
        StackWalker.getInstance().forEach(f -> {
            String c = f.getClassName();
            if (c.startsWith("dev.effecttrace") || c.startsWith("org.bukkit.plugin.") || c.startsWith("java.") || c.startsWith("jdk.")
                    || c.startsWith("io.papermc.paper.plugin.manager") || c.startsWith("com.destroystokyo.paper.event.executor")
                    || c.startsWith("org.bukkit.craftbukkit.event") || c.startsWith("io.papermc.paper.event")) return;
            if (lines.size() < 14) lines.add(c + "." + f.getMethodName());
        });
        return lines;
    }

    private void dump(CommandSender to) {
        List<String> lines = new ArrayList<>();
        lines.add("---- plugins listening to potion-related events ----");
        listeners(lines, "EntityPotionEffectEvent (effect added/removed)", EntityPotionEffectEvent.getHandlerList());
        listeners(lines, "PlayerItemConsumeEvent (drinking)", PlayerItemConsumeEvent.getHandlerList());
        listeners(lines, "PotionSplashEvent", PotionSplashEvent.getHandlerList());
        listeners(lines, "LingeringPotionSplashEvent", LingeringPotionSplashEvent.getHandlerList());
        listeners(lines, "AreaEffectCloudApplyEvent", AreaEffectCloudApplyEvent.getHandlerList());

        lines.add("---- scheduled tasks per plugin (a plugin with a timer can clear effects every tick) ----");
        Map<String, Integer> counts = new TreeMap<>();
        for (var task : Bukkit.getScheduler().getPendingTasks()) {
            counts.merge(task.getOwner().getName(), 1, Integer::sum);
        }
        counts.forEach((k, v) -> lines.add("  " + k + ": " + v + " pending task(s)"));
        lines.add("---- installed plugins ----");
        lines.add("  " + Bukkit.getPluginManager().getPlugins().length + ": "
                + java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).sorted().collect(Collectors.joining(", ")));
        for (String l : lines) {
            log(l);
            if (to != null && !(to == Bukkit.getConsoleSender())) to.sendMessage(l);
        }
    }

    private void listeners(List<String> lines, String title, HandlerList list) {
        lines.add(title + ":");
        Map<String, List<String>> byPlugin = new TreeMap<>();
        for (RegisteredListener rl : list.getRegisteredListeners()) {
            if (rl.getPlugin() == this) continue;
            byPlugin.computeIfAbsent(rl.getPlugin().getName(), k -> new ArrayList<>())
                    .add(rl.getPriority().name() + (rl.isIgnoringCancelled() ? "*" : "") + " " + rl.getListener().getClass().getSimpleName());
        }
        if (byPlugin.isEmpty()) lines.add("  (nobody)");
        byPlugin.forEach((k, v) -> lines.add("  " + k + " -> " + String.join(", ", v)));
    }

    @EventHandler
    public void onServerLoaded(ServerLoadEvent e) {
        // everything is registered by now: write the listener list once so it is in trace.log even if nobody runs a command
        dump(null);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "test": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Run this as a player.");
                    return true;
                }
                PotionEffectType type = watched == null ? PotionEffectType.STRENGTH : watched;
                p.addPotionEffect(new PotionEffect(type, 20 * 60, 0));
                sender.sendMessage("Gave you " + name(type) + " I for 60s. Watch the console / trace.log.");
                return true;
            }
            case "potion": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Run this as a player.");
                    return true;
                }
                ItemStack item = new ItemStack(Material.POTION);
                PotionMeta meta = (PotionMeta) item.getItemMeta();
                meta.setBasePotionType(PotionType.STRENGTH);
                item.setItemMeta(meta);
                p.getInventory().addItem(item);
                sender.sendMessage("Gave you a Potion of Strength. Drink it, then check the console / trace.log.");
                return true;
            }
            case "listeners":
                dump(sender);
                sender.sendMessage("Also written to plugins/EffectTrace/trace.log");
                return true;
            case "type": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /effecttrace type <effect name | all>   (now: " + (watched == null ? "all" : name(watched)) + ")");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    watched = null;
                } else {
                    PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(args[1].toLowerCase(Locale.ROOT)));
                    if (t == null) {
                        sender.sendMessage("Unknown effect '" + args[1] + "'. Use the vanilla name, e.g. strength, speed, regeneration.");
                        return true;
                    }
                    watched = t;
                }
                lastSeen.clear();
                sender.sendMessage("Now watching: " + (watched == null ? "all effects" : name(watched)));
                return true;
            }
            default:
                sender.sendMessage("EffectTrace - watching " + (watched == null ? "all effects" : name(watched)) + ". Output: console + plugins/EffectTrace/trace.log");
                sender.sendMessage("/effecttrace potion      get a Potion of Strength to drink");
                sender.sendMessage("/effecttrace test        give yourself Strength through the API");
                sender.sendMessage("/effecttrace listeners   list every plugin that listens to potion events");
                sender.sendMessage("/effecttrace type <name|all>  change which effect is watched");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("potion", "test", "listeners", "type", "status").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        return List.of();
    }
}
