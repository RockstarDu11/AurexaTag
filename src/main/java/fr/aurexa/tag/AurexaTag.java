package fr.aurexa.tag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AurexaTag extends JavaPlugin implements TabExecutor {

    private static final String PREFIX = ChatColor.GOLD + "AurexaTag " + ChatColor.GOLD + ">> ";
    private static final int MAX_TEXT = 24;

    private TagManager tags;
    private File dataFile;
    private FileConfiguration data;
    private final Map<UUID, String> assigned = new LinkedHashMap<UUID, String>(); // joueur -> texte du tag

    @Override
    public void onEnable() {
        if (!Nms.ok) {
            getLogger().severe("AurexaTag ne fonctionne qu'en 1.8.8 (v1_8_R3) : plugin désactivé.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        tags = new TagManager(this);
        getServer().getPluginManager().registerEvents(tags, this);
        getCommand("tag").setExecutor(this);
        getCommand("tag").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        if (tags != null) tags.shutdown();
    }

    // ------------------------------------------------------------ données (data.yml : tags: uuid: texte)
    private void loadData() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        assigned.clear();
        ConfigurationSection sec = data.getConfigurationSection("tags");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try { assigned.put(UUID.fromString(k), sec.getString(k)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("data.yml : " + ex.getMessage()); }
    }

    /** Texte du tag d'un joueur, ou null s'il n'en a pas. */
    public String tagOf(UUID id) { return assigned.isEmpty() ? null : assigned.get(id); }
    public int tagCount() { return assigned.size(); }

    /** Mode de suivi : "ride" (collé à la tête, comme un nametag) ou "explicit" (hauteur réglable). */
    public String mode() {
        String m = data != null ? data.getString("mode") : null;
        if (m == null) m = getConfig().getString("mode");
        return m == null ? "ride" : m;
    }

    /** Hauteur du tag (blocs au-dessus des pieds) : réglée avec /tag height, sinon celle de config.yml. */
    public double height() {
        if (data != null && data.isSet("height")) return data.getDouble("height");
        return getConfig().getDouble("height", 2.4);
    }

    private void setTag(UUID id, String text) {
        assigned.put(id, text);
        data.set("tags." + id, text);
        saveData();
        tags.apply(id);
    }

    private void removeTag(UUID id) {
        assigned.remove(id);
        data.set("tags." + id, null);
        saveData();
        tags.apply(id);
    }

    // ------------------------------------------------------------ commandes
    private String msg(String key, String def) {
        String s = getConfig().getString("messages." + key);
        return ChatColor.translateAlternateColorCodes('&', s == null ? def : s);
    }

    private static void say(CommandSender s, String text) {
        s.sendMessage(PREFIX + ChatColor.translateAlternateColorCodes('&', text));
    }

    private OfflinePlayer find(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        return op.hasPlayedBefore() ? op : null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("aurexatag.admin")) {
            s.sendMessage(msg("no-permission", "&cPermission refusée."));
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return true; }

        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadData();
            tags.reload();
            s.sendMessage(msg("reloaded", "&6AurexaTag &e>> &aConfiguration rechargée."));
            return true;
        }
        if (a[0].equalsIgnoreCase("mode")) {
            if (a.length < 2) {
                say(s, "&eMode actuel : &f" + mode());
                say(s, "&7/tag mode ride &8- &7collé à la tête comme un vrai nametag (recommandé)");
                say(s, "&7/tag mode explicit &8- &7hauteur réglable avec /tag height (un peu de retard)");
                return true;
            }
            if (a[1].equalsIgnoreCase("reset")) {
                data.set("mode", null);
            } else if (a[1].equalsIgnoreCase("ride") || a[1].equalsIgnoreCase("explicit")) {
                data.set("mode", a[1].toLowerCase());
            } else {
                say(s, "&cUsage : /tag mode <ride|explicit|reset>");
                return true;
            }
            saveData();
            tags.reload();
            say(s, "&aMode : &f" + mode());
            return true;
        }
        if (a[0].equalsIgnoreCase("height")) {
            if (a.length < 2) {
                say(s, "&eHauteur actuelle : &f" + height() + " &7(plus grand = plus haut)");
                say(s, "&7Usage : /tag height <valeur>   ex : &f/tag height 2.4   &7ou &f/tag height reset");
                return true;
            }
            if (a[1].equalsIgnoreCase("reset")) {
                data.set("height", null);
                saveData();
                tags.reload();
                say(s, "&aHauteur remise par défaut : &f" + height());
                return true;
            }
            try {
                double v = Double.parseDouble(a[1].replace(',', '.'));
                if (v < -1 || v > 6) throw new NumberFormatException();
                data.set("height", v);
                saveData();
                tags.reload();
                say(s, "&aHauteur du tag : &f" + v + " &7(retouche-la jusqu'à ce que ce soit bien placé)");
            } catch (NumberFormatException ex) {
                say(s, "&cValeur entre -1 et 6, par exemple &f/tag height 2.4");
            }
            return true;
        }
        if (a[0].equalsIgnoreCase("debug")) {
            if (!(s instanceof Player)) { say(s, "&cÀ faire en jeu."); return true; }
            say(s, "&e--- Diagnostic AurexaTag ---");
            for (String line : tags.debug((Player) s)) say(s, line);
            return true;
        }
        if (a[0].equalsIgnoreCase("list")) {
            if (assigned.isEmpty()) { say(s, "&7Aucun joueur n'a de tag."); return true; }
            say(s, "&eJoueurs avec un tag :");
            for (Map.Entry<UUID, String> e : assigned.entrySet()) {
                String n = Bukkit.getOfflinePlayer(e.getKey()).getName();
                say(s, "&f" + (n == null ? e.getKey().toString() : n) + " &7- &e" + e.getValue());
            }
            return true;
        }

        // /tag <joueur> <texte...>   ou   /tag <joueur> off
        OfflinePlayer target = find(a[0]);
        if (target == null) { say(s, "&cJoueur inconnu : &e" + a[0]); return true; }
        if (a.length < 2) {
            String t = tagOf(target.getUniqueId());
            say(s, "&f" + target.getName() + " &7: " + (t == null ? "&caucun tag" : "&e" + t));
            say(s, "&7Usage : /" + label + " <joueur> <texte>   ou   /" + label + " <joueur> off");
            return true;
        }
        if (a.length == 2 && (a[1].equalsIgnoreCase("off") || a[1].equalsIgnoreCase("remove")
                || a[1].equalsIgnoreCase("retirer"))) {
            if (tagOf(target.getUniqueId()) == null) { say(s, "&e" + target.getName() + " &cn'a pas de tag."); return true; }
            removeTag(target.getUniqueId());
            say(s, "&aTag retiré à &f" + target.getName() + "&a.");
            return true;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < a.length; i++) sb.append(i > 1 ? " " : "").append(a[i]);
        // pas de codes couleur dans le texte : les couleurs se règlent avec base-color / shine-color
        String t = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', sb.toString())).trim();
        if (t.isEmpty() || t.length() > MAX_TEXT) {
            say(s, "&cLe texte doit faire entre 1 et " + MAX_TEXT + " caractères.");
            return true;
        }
        setTag(target.getUniqueId(), t);
        say(s, "&aTag &e" + t + " &aattribué à &f" + target.getName() + "&a.");
        return true;
    }

    private void help(CommandSender s) {
        say(s, "&e/tag <joueur> <texte> &6- donner un tag animé à un joueur");
        say(s, "&e/tag <joueur> off &6- retirer son tag");
        say(s, "&e/tag list &6- joueurs qui ont un tag");
        say(s, "&e/tag reload &6- recharger la configuration");
        say(s, "&e/tag mode <ride|explicit> &6- collé à la tête (défaut) ou hauteur réglable");
        say(s, "&e/tag height <valeur> &6- hauteur du tag (explicit, et sur ton propre écran)");
        say(s, "&e/tag debug &6- diagnostic si le tag ne s'affiche pas");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<String>();
        if (!s.hasPermission("aurexatag.admin")) return out;
        if (a.length == 1) {
            String p = a[0].toLowerCase();
            for (String x : new String[]{"list", "reload", "mode", "height", "debug", "help"}) if (x.startsWith(p)) out.add(x);
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(p)) out.add(pl.getName());
        } else if (a.length == 2) {
            String p = a[1].toLowerCase();
            if (a[0].equalsIgnoreCase("mode")) {
                for (String x : new String[]{"ride", "explicit", "reset"}) if (x.startsWith(p)) out.add(x);
            } else if (a[0].equalsIgnoreCase("height")) {
                for (String x : new String[]{"2.4", "reset"}) if (x.startsWith(p)) out.add(x);
            } else {
                for (String x : new String[]{"Aurexalium", "off"}) if (x.toLowerCase().startsWith(p)) out.add(x);
            }
        }
        return out;
    }
}
