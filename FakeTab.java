package dev.faketab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class FakeTab extends JavaPlugin implements Listener, TabExecutor {

    /** A verified Minecraft account (real UUID + skin). */
    static final class Entry {
        final String name; final UUID uuid;
        volatile String value, sig; volatile boolean skinTried;
        Entry(String n, UUID u) { name = n; uuid = u; }
    }
    record Resp(int code, String body) {}

    private static final String NAME_RE = "[A-Za-z0-9_]{3,16}";
    private final Gson gson = new Gson();
    private final Map<String, Entry> resolved = new ConcurrentHashMap<>();
    private final Set<String> invalid = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> manual = new LinkedHashMap<>();      // lowercase -> position (0 = alphabetical)
    private final Map<String, String> manualCase = new HashMap<>();         // lowercase -> typed name
    private final List<String> baseNames = new ArrayList<>();
    private final Object resolveLock = new Object();
    private volatile List<Entry> shown = List.of();
    private boolean on, realsHidden;
    private int fullness, lag;
    private char maxLetter;
    private ProtocolManager pm;
    private File cacheFile, manualFile, namesFile;

    // ------------------------------------------------------------ lifecycle
    @Override public void onEnable() {
        saveDefaultConfig();
        namesFile = new File(getDataFolder(), "names.txt");
        if (!namesFile.exists()) saveResource("names.txt", false);
        cacheFile = new File(getDataFolder(), "cache.yml");
        manualFile = new File(getDataFolder(), "manual.yml");
        pm = ProtocolLibrary.getProtocolManager();
        loadAll();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("faketab")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::pingTick, 100L, 160L);
        rebuild();          // shows whatever is already cached
        resolve(null);      // verifies new names with Mojang
    }

    @Override public void onDisable() {
        boolean was = on; on = false; rebuild(); on = was;
    }

    private void loadAll() {
        reloadConfig();
        on = getConfig().getBoolean("enabled", true);
        fullness = clamp(getConfig().getInt("fullness", 100));
        lag = clamp(getConfig().getInt("lag-percent", 20));
        String ml = getConfig().getString("max-letter", "c").toLowerCase();
        maxLetter = ml.isEmpty() ? 'c' : ml.charAt(0);
        baseNames.clear();
        try {
            for (String l : Files.readAllLines(namesFile.toPath(), StandardCharsets.UTF_8)) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) baseNames.add(l);
            }
        } catch (IOException e) { getLogger().warning("Could not read names.txt: " + e.getMessage()); }
        manual.clear(); manualCase.clear();
        if (manualFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(manualFile);
            var sec = y.getConfigurationSection("players");
            if (sec != null) for (String k : sec.getKeys(false)) {
                manual.put(k.toLowerCase(), sec.getInt(k)); manualCase.put(k.toLowerCase(), k);
            }
        }
        resolved.clear(); invalid.clear();
        if (cacheFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cacheFile);
            var sec = y.getConfigurationSection("profiles");
            if (sec != null) for (String k : sec.getKeys(false)) {
                try {
                    Entry e = new Entry(sec.getString(k + ".name"), UUID.fromString(sec.getString(k + ".uuid")));
                    e.value = sec.getString(k + ".value"); e.sig = sec.getString(k + ".sig");
                    e.skinTried = sec.getBoolean(k + ".skinTried");
                    resolved.put(k, e);
                } catch (Exception ignored) {}
            }
            for (String s : y.getStringList("invalid")) invalid.add(s.toLowerCase());
        }
    }

    private synchronized void saveCache() {
        YamlConfiguration y = new YamlConfiguration();
        for (var en : resolved.entrySet()) {
            String p = "profiles." + en.getKey(); Entry e = en.getValue();
            y.set(p + ".name", e.name); y.set(p + ".uuid", e.uuid.toString());
            y.set(p + ".value", e.value); y.set(p + ".sig", e.sig); y.set(p + ".skinTried", e.skinTried);
        }
        y.set("invalid", new ArrayList<>(invalid));
        try { y.save(cacheFile); } catch (IOException ex) { getLogger().warning("cache save failed: " + ex.getMessage()); }
    }
    private void saveManual() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : manual.entrySet()) y.set("players." + manualCase.get(e.getKey()), e.getValue());
        try { y.save(manualFile); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }
    private void saveBase() {
        List<String> out = new ArrayList<>(List.of("# One Minecraft username per line (verified against Mojang)"));
        out.addAll(baseNames);
        try { Files.write(namesFile.toPath(), out, StandardCharsets.UTF_8); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }
    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    // ------------------------------------------------------------ Mojang verification (async)
    private Resp http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "FakeTab/1.0");
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        var in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Resp(code, text);
    }

    private Set<String> wanted() {
        Set<String> s = new LinkedHashSet<>();
        for (String n : baseNames) s.add(n.toLowerCase());
        s.addAll(manual.keySet());
        return s;
    }

    private void resolve(CommandSender notify) {
        Map<String, String> casing = new HashMap<>();
        for (String n : baseNames) casing.put(n.toLowerCase(), n);
        casing.putAll(manualCase);
        List<String> want = new ArrayList<>(wanted());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            synchronized (resolveLock) {
                try { doResolve(want, casing); } catch (Exception ex) { getLogger().log(Level.WARNING, "resolve failed", ex); }
            }
            Bukkit.getScheduler().runTask(this, () -> {
                for (String l : new ArrayList<>(manual.keySet())) {
                    if (invalid.contains(l)) {
                        manual.remove(l); String n = manualCase.remove(l); saveManual();
                        if (notify != null) notify.sendMessage("§c" + n + " is not a real Minecraft account - removed.");
                    } else if (notify != null && resolved.containsKey(l)) {
                        notify.sendMessage("§aVerified " + resolved.get(l).name + " (real account).");
                    }
                }
                rebuild();
            });
        });
    }

    private void doResolve(List<String> want, Map<String, String> casing) throws Exception {
        List<String> need = new ArrayList<>();
        for (String l : want) {
            String n = casing.getOrDefault(l, l);
            if (!n.matches(NAME_RE)) { invalid.add(l); continue; }
            if (!resolved.containsKey(l) && !invalid.contains(l)) need.add(n);
        }
        for (int i = 0; i < need.size(); i += 10) {
            List<String> batch = need.subList(i, Math.min(i + 10, need.size()));
            Resp r = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                r = http("POST", "https://api.mojang.com/profiles/minecraft", gson.toJson(batch));
                if (r.code() != 429) break;
                Thread.sleep(15000);
            }
            if (r == null || r.code() != 200) { getLogger().warning("Mojang lookup failed (HTTP " + (r == null ? "?" : r.code()) + "); will retry on /faketab reload"); break; }
            Set<String> found = new HashSet<>();
            for (JsonElement el : JsonParser.parseString(r.body()).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString().replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
                String nm = o.get("name").getAsString();
                resolved.put(nm.toLowerCase(), new Entry(nm, UUID.fromString(id)));
                found.add(nm.toLowerCase());
            }
            for (String n : batch) if (!found.contains(n.toLowerCase())) { invalid.add(n.toLowerCase()); getLogger().info("Skipping non-existent account: " + n); }
            Thread.sleep(300);
        }
        saveCache();
        Bukkit.getScheduler().runTask(this, this::rebuild);   // show verified names right away, skins come after

        boolean any = false;
        for (String l : want) {
            Entry e = resolved.get(l);
            if (e == null || e.skinTried) continue;
            for (int attempt = 0; attempt < 3; attempt++) {
                Resp r = http("GET", "https://sessionserver.mojang.com/session/minecraft/profile/" + e.uuid.toString().replace("-", "") + "?unsigned=false", null);
                if (r.code() == 429) { Thread.sleep(30000); continue; }
                if (r.code() == 200) {
                    JsonArray props = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("properties");
                    if (props != null) for (JsonElement pe : props) {
                        JsonObject po = pe.getAsJsonObject();
                        if ("textures".equals(po.get("name").getAsString())) {
                            e.value = po.get("value").getAsString();
                            e.sig = po.has("signature") ? po.get("signature").getAsString() : null;
                        }
                    }
                }
                e.skinTried = true; any = true; break;
            }
            Thread.sleep(600);
        }
        if (any) { saveCache(); Bukkit.getScheduler().runTask(this, this::rebuild); }
    }

    // ------------------------------------------------------------ list building
    private int mix(String name, int salt) { return new Random(name.toLowerCase().hashCode() * 31L + salt * 7919L).nextInt(100); }

    private boolean eligible(String name) {
        char c = Character.toLowerCase(name.charAt(0));
        return !Character.isLetter(c) || c <= maxLetter;
    }

    private List<Entry> compute() {
        List<Entry> auto = new ArrayList<>(); Map<Integer, List<Entry>> pinned = new TreeMap<>();
        for (String l : wanted()) {
            Entry e = resolved.get(l);
            if (e == null) continue;
            Integer pos = manual.get(l);
            if (pos == null) { if (!eligible(e.name) || mix(e.name, 1) >= fullness) continue; auto.add(e); }
            else if (pos <= 0) auto.add(e);                 // manual, alphabetical, always shown
            else pinned.computeIfAbsent(pos, k -> new ArrayList<>()).add(e);
        }
        auto.sort((a, b) -> a.name.compareToIgnoreCase(b.name));   // same ordering the client uses
        for (var en : pinned.entrySet())
            for (Entry e : en.getValue()) auto.add(Math.min(en.getKey() - 1, auto.size()), e);
        return auto;
    }

    private int ping(Entry e) {
        Random r = new Random(e.name.toLowerCase().hashCode() + System.currentTimeMillis() / 8000);
        if (mix(e.name, 2) < lag) {
            return switch (1 + mix(e.name, 3) % 3) {       // level 1 = slightly worse ... 3 = worst
                case 1 -> 150 + r.nextInt(140);
                case 2 -> 300 + r.nextInt(280);
                default -> 600 + r.nextInt(380);
            };
        }
        return 18 + r.nextInt(100);                         // full bars (<150ms)
    }

    // ------------------------------------------------------------ packets
    private boolean applies(Player p) { return !p.hasPermission("faketab.bypass"); }
    private void send(Player p, PacketContainer c) {
        try { pm.sendServerPacket(p, c); } catch (Exception ex) { getLogger().log(Level.WARNING, "packet failed", ex); }
    }
    private boolean conflicts(Entry e) { return Bukkit.getPlayerExact(e.name) != null || Bukkit.getPlayer(e.uuid) != null; }

    private PlayerInfoData fakeData(Entry e) {
        WrappedGameProfile gp = new WrappedGameProfile(e.uuid, e.name);
        if (e.value != null) gp.getProperties().put("textures", new WrappedSignedProperty("textures", e.value, e.sig));
        return new PlayerInfoData(e.uuid, ping(e), true, NativeGameMode.SURVIVAL, gp, WrappedChatComponent.fromText(e.name));
    }

    private void sendAdd(Player v, List<Entry> list) {
        List<PlayerInfoData> data = new ArrayList<>();
        for (Entry e : list) if (!conflicts(e)) data.add(fakeData(e));
        if (data.isEmpty()) return;
        PacketContainer c = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
        c.getPlayerInfoActions().write(0, EnumSet.of(PlayerInfoAction.ADD_PLAYER, PlayerInfoAction.UPDATE_LISTED,
                PlayerInfoAction.UPDATE_LATENCY, PlayerInfoAction.UPDATE_GAME_MODE));
        c.getPlayerInfoDataLists().write(1, data);
        send(v, c);
    }
    private void sendRemove(Player v, List<Entry> list) {
        PacketContainer c = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        List<UUID> ids = new ArrayList<>(); for (Entry e : list) ids.add(e.uuid);
        c.getUUIDLists().write(0, ids);
        send(v, c);
    }
    private void sendListed(Player v, Collection<? extends Player> reals, boolean listed) {
        if (reals.isEmpty()) return;
        List<PlayerInfoData> data = new ArrayList<>();
        for (Player r : reals) data.add(new PlayerInfoData(r.getUniqueId(), 0, listed, NativeGameMode.SURVIVAL,
                WrappedGameProfile.fromPlayer(r), WrappedChatComponent.fromText(r.getName())));
        PacketContainer c = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
        c.getPlayerInfoActions().write(0, EnumSet.of(PlayerInfoAction.UPDATE_LISTED));
        c.getPlayerInfoDataLists().write(1, data);
        send(v, c);
    }

    private void pingTick() {
        List<Entry> s = shown;
        if (!on || s.isEmpty()) return;
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!applies(v)) continue;
            List<PlayerInfoData> data = new ArrayList<>();
            for (Entry e : s) if (!conflicts(e)) data.add(fakeData(e));
            PacketContainer c = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            c.getPlayerInfoActions().write(0, EnumSet.of(PlayerInfoAction.UPDATE_LATENCY));
            c.getPlayerInfoDataLists().write(1, data);
            send(v, c);
        }
    }

    /** Teams named ft00001.. force the client's sort order (teams sort before names). */
    private void applyTeams(List<Entry> list) {
        Scoreboard sb = Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams())) if (t.getName().startsWith("ft") && t.getName().length() == 7) t.unregister();
        for (int i = 0; i < list.size(); i++) {
            Team t = sb.registerNewTeam(String.format("ft%05d", i));
            t.addEntry(list.get(i).name);
        }
    }

    private void rebuild() {
        List<Entry> old = shown;
        List<Entry> now = on ? compute() : List.of();
        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();
        if (!old.isEmpty()) for (Player v : viewers) if (applies(v)) sendRemove(v, old);
        shown = now;
        applyTeams(now);
        for (Player v : viewers) {
            if (!applies(v)) continue;
            if (on) { sendListed(v, viewers, false); sendAdd(v, now); }
            else if (realsHidden) sendListed(v, viewers, true);
        }
        realsHidden = on;
    }

    @EventHandler public void onJoin(PlayerJoinEvent ev) {
        Player j = ev.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!on || !j.isOnline()) return;
            for (Player v : Bukkit.getOnlinePlayers()) if (applies(v)) sendListed(v, List.of(j), false);
            if (applies(j)) { sendListed(j, Bukkit.getOnlinePlayers(), false); sendAdd(j, shown); }
        }, 10L);
    }

    // ------------------------------------------------------------ command
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("faketab.admin")) { s.sendMessage("§cNo permission."); return true; }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        switch (sub) {
            case "on", "off" -> {
                on = sub.equals("on"); getConfig().set("enabled", on); saveConfig(); rebuild();
                s.sendMessage(on ? "§aFake tab ON (fake players shown, real players hidden)." : "§eFake tab OFF (real players shown).");
            }
            case "fullness" -> { if (a.length < 2) return msg(s, "§e/ft fullness <0-100>  now: " + fullness);
                try { fullness = clamp(Integer.parseInt(a[1])); } catch (NumberFormatException e) { return msg(s, "§cNumber 0-100."); }
                getConfig().set("fullness", fullness); saveConfig(); rebuild(); s.sendMessage("§aFullness " + fullness + "% -> " + shown.size() + " fake players."); }
            case "lag" -> { if (a.length < 2) return msg(s, "§e/ft lag <0-100>  now: " + lag);
                try { lag = clamp(Integer.parseInt(a[1])); } catch (NumberFormatException e) { return msg(s, "§cNumber 0-100."); }
                getConfig().set("lag-percent", lag); saveConfig(); pingTick(); s.sendMessage("§a" + lag + "% of players now have reduced ping bars."); }
            case "maxletter" -> { if (a.length < 2 || a[1].length() != 1 || !Character.isLetter(a[1].charAt(0))) return msg(s, "§e/ft maxletter <a-z>  now: " + maxLetter);
                maxLetter = a[1].toLowerCase().charAt(0); getConfig().set("max-letter", String.valueOf(maxLetter)); saveConfig(); rebuild();
                s.sendMessage("§aNames now go up to '" + maxLetter + "' -> " + shown.size() + " fake players."); }
            case "add", "move" -> {
                if (a.length < 2) return msg(s, "§e/ft add <name> [position]   (position 1 = very top; omit = alphabetical)");
                String n = a[1]; if (!n.matches(NAME_RE)) return msg(s, "§cInvalid Minecraft username.");
                int pos = 0;
                if (a.length > 2) { try { pos = Integer.parseInt(a[2]); } catch (NumberFormatException e) { return msg(s, "§cPosition must be a number."); } if (pos < 1) return msg(s, "§cPosition starts at 1."); }
                String l = n.toLowerCase(); manual.put(l, pos); manualCase.put(l, n); invalid.remove(l); saveManual();
                s.sendMessage("§7Checking " + n + " with Mojang..."); resolve(s);
            }
            case "remove" -> {
                if (a.length < 2) return msg(s, "§e/ft remove <name>");
                String l = a[1].toLowerCase(); boolean ok = manual.remove(l) != null; manualCase.remove(l); if (ok) saveManual();
                if (baseNames.removeIf(x -> x.equalsIgnoreCase(a[1]))) { saveBase(); ok = true; }
                rebuild(); s.sendMessage(ok ? "§aRemoved " + a[1] : "§cNot in the list.");
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder("§7"); int i = 1;
                for (Entry e : shown) sb.append(i++).append('.').append(e.name).append("  ");
                s.sendMessage("§6Tab order (" + shown.size() + "):"); s.sendMessage(sb.toString());
            }
            case "reload" -> { loadAll(); rebuild(); resolve(s); s.sendMessage("§aReloaded; re-verifying names."); }
            case "status" -> {
                long pending = wanted().stream().filter(l -> !resolved.containsKey(l) && !invalid.contains(l)).count();
                s.sendMessage("§6FakeTab: §f" + (on ? "ON" : "OFF") + " | shown " + shown.size() + " | fullness " + fullness + "% | lag " + lag + "% | max letter " + maxLetter
                        + " | verified " + resolved.size() + " | pending " + pending + " | rejected " + invalid.size());
            }
            default -> s.sendMessage("§6/ft on|off | fullness <0-100> | lag <0-100> | maxletter <a-z> | add <name> [pos] | remove <name> | list | status | reload");
        }
        return true;
    }
    private boolean msg(CommandSender s, String m) { s.sendMessage(m); return true; }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("on", "off", "fullness", "lag", "maxletter", "add", "remove", "move", "list", "status", "reload");
        return List.of();
    }
}
