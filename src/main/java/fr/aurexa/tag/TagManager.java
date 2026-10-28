package fr.aurexa.tag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tag animé au-dessus de la tête des joueurs qui en ont un (/tag <joueur> <texte>).
 *
 * - Les AUTRES joueurs voient un petit armor stand collé sur la tête du joueur (mode "ride") : il le suit
 *   exactement, comme un vrai nametag, sans aucun retard.
 * - Le joueur lui-même voit un stand placé à "height" blocs (avec une petite prédiction pour compenser
 *   le ping) : le coller sur lui bloquerait ses clics, car le jeu y verrait un objet entre lui et sa cible.
 * - Le pseudo normal reste visible dessous.
 */
public final class TagManager implements Listener {

    private static final class Host {
        final String text;
        Nms.Stand stand;                    // vu par les autres joueurs
        Nms.Stand self;                     // vu par le joueur lui-même (null si show-self: false)
        Set<UUID> viewers = new HashSet<UUID>();
        boolean selfShown;
        double x, y, z;                     // dernière position de 'stand' (mode explicit)
        double sx, sy, sz;                  // dernière position de 'self'
        double px, py, pz, vx, vy, vz;      // position précédente + vitesse lissée (prédiction)
        Host(String text) { this.text = text; }
    }

    private final AurexaTag plugin;
    private final Map<UUID, Host> hosts = new HashMap<UUID, Host>();
    private final Map<UUID, Object> conns = new HashMap<UUID, Object>();
    private final Set<UUID> dirty = new HashSet<UUID>();
    private final List<String> disabledWorlds = new ArrayList<String>();

    private boolean running, flushScheduled, showSelf, rtl, ride, predict;
    private String base, edge, shine;
    private int speed, width, pause, frame;
    private double viewDistance, height, sneakDrop;
    private BukkitTask task, moveTask;

    public TagManager(AurexaTag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        shutdown();
        FileConfiguration c = plugin.getConfig();
        String b = c.getString("base-color");
        base = b == null ? "&6&l" : b;
        String e = c.getString("edge-color");
        edge = e == null ? "&e&l" : e;
        String s = c.getString("shine-color");
        shine = s == null ? "&f&l" : s;
        speed = Math.max(1, c.getInt("speed-ticks", 3));
        width = Math.max(1, c.getInt("shine-width", 3));
        pause = Math.max(0, c.getInt("pause", 6));
        String d = c.getString("direction");
        rtl = d != null && (d.equalsIgnoreCase("rtl") || d.equalsIgnoreCase("right-to-left"));
        ride = !"explicit".equalsIgnoreCase(plugin.mode());
        viewDistance = Math.max(4, c.getInt("view-distance", 32));
        showSelf = c.getBoolean("show-self", true);
        predict = c.getBoolean("self-prediction", true);
        height = plugin.height();
        sneakDrop = c.getDouble("sneak-drop", 0.15);
        disabledWorlds.clear();
        disabledWorlds.addAll(c.getStringList("disabled-worlds"));
        frame = 0;

        if (c.getBoolean("enabled", true)) {
            running = true;
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 20L, speed);
            moveTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { move(); }
            }, 20L, 1L);
        }
    }

    /** Arrête l'animation et retire les tags chez tous les joueurs. */
    public void shutdown() {
        running = false;
        if (task != null) { task.cancel(); task = null; }
        if (moveTask != null) { moveTask.cancel(); moveTask = null; }
        for (Map.Entry<UUID, Host> e : hosts.entrySet()) removeHost(e.getKey(), e.getValue());
        hosts.clear();
        dirty.clear();
    }

    /** Appelé quand le tag d'un joueur est ajouté, changé ou retiré : on repart de zéro pour lui. */
    public void apply(UUID id) {
        if (!running) return;
        Host h = hosts.remove(id);
        if (h != null) removeHost(id, h);
        sync();
    }

    // ------------------------------------------------------------ animation (couleurs)
    private void tick() {
        frame++;
        if (frame % Math.max(1, 20 / speed) == 0) sync(); // ~1x / seconde : qui voit quoi
        if (hosts.isEmpty()) return;

        Map<String, String> names = new HashMap<String, String>(); // un calcul par texte et par image
        for (Map.Entry<UUID, Host> e : hosts.entrySet()) {
            Host h = e.getValue();
            String n = names.get(h.text);
            if (n == null) { n = build(h.text, frame); names.put(h.text, n); }
            if (!h.viewers.isEmpty() && h.stand != null) {
                Nms.setName(h.stand, n);
                Object pkt = Nms.meta(h.stand); // même paquet pour tous les spectateurs de ce joueur
                for (UUID vid : h.viewers) Nms.send(conns.get(vid), pkt);
            }
            if (h.selfShown && h.self != null) {
                Nms.setName(h.self, n);
                Nms.send(conns.get(e.getKey()), Nms.meta(h.self));
            }
        }
    }

    /**
     * Texte de base (doré) avec une fenêtre de "shine-width" lettres qui traverse le mot :
     * le centre en blanc, les bords en jaune. Ex. avec 3 lettres : &6Aurex&e&la&f&ll&e&li&6&lum
     */
    private String build(String text, int f) {
        int n = text.length();
        int cycle = n + width + pause;
        int pos = (f % cycle) - width;           // première lettre de la fenêtre (négatif = elle entre, > n = pause)
        int center = pos + width / 2;
        StringBuilder sb = new StringBuilder();
        String cur = null;
        for (int i = 0; i < n; i++) {
            int j = rtl ? n - 1 - i : i;         // sens droite -> gauche
            String col = base;
            if (j >= pos && j < pos + width) col = (j == center) ? shine : edge;
            if (!col.equals(cur)) { sb.append(col); cur = col; }
            sb.append(text.charAt(i));
        }
        String out = ChatColor.translateAlternateColorCodes('&', sb.toString());
        return out.length() > 64 ? out.substring(0, 64) : out; // limite Minecraft pour un nom
    }

    // ------------------------------------------------------------ position
    /** Chaque tick : suit les mouvements (stand du joueur lui-même, et stands explicites des autres). */
    private void move() {
        if (hosts.isEmpty()) return;
        for (Map.Entry<UUID, Host> e : hosts.entrySet()) {
            Host h = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Location l = p.getLocation();

            // vitesse lissée (blocs / tick), pour compenser le retard du réseau sur sa propre vue
            double dx = l.getX() - h.px, dy = l.getY() - h.py, dz = l.getZ() - h.pz;
            if (dx * dx + dy * dy + dz * dz > 16) { h.vx = 0; h.vy = 0; h.vz = 0; }
            else { h.vx = h.vx * 0.5 + dx * 0.5; h.vy = h.vy * 0.5 + dy * 0.5; h.vz = h.vz * 0.5 + dz * 0.5; }
            h.px = l.getX(); h.py = l.getY(); h.pz = l.getZ();

            double drop = p.isSneaking() ? sneakDrop : 0.0;

            if (!ride && h.stand != null && !h.viewers.isEmpty()) {
                double y = l.getY() + height - drop;
                if (Math.abs(l.getX() - h.x) + Math.abs(y - h.y) + Math.abs(l.getZ() - h.z) >= 0.01) {
                    h.x = l.getX(); h.y = y; h.z = l.getZ();
                    Object pkt = Nms.moveTo(h.stand, h.x, h.y, h.z);
                    for (UUID vid : h.viewers) Nms.send(conns.get(vid), pkt);
                }
            }

            if (h.selfShown && h.self != null) {
                double lead = predict ? Math.min(8.0, Nms.ping(p) / 50.0 + 1.5) : 0.0;
                double x = l.getX() + h.vx * lead;
                double y = l.getY() + height - drop + h.vy * lead;
                double z = l.getZ() + h.vz * lead;
                if (Math.abs(x - h.sx) + Math.abs(y - h.sy) + Math.abs(z - h.sz) >= 0.01) {
                    h.sx = x; h.sy = y; h.sz = z;
                    Nms.send(conns.get(e.getKey()), Nms.moveTo(h.self, x, y, z));
                }
            }
        }
    }

    private boolean eligible(Player p) {
        return plugin.tagOf(p.getUniqueId()) != null
                && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR
                && !p.hasPotionEffect(PotionEffectType.INVISIBILITY)
                && !disabledWorlds.contains(p.getWorld().getName());
    }

    /** Recalcule qui voit le tag de qui : envoie / retire les faux stands si besoin. */
    private void sync() {
        if (!running) return;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        Set<UUID> eligible = new HashSet<UUID>();
        for (Player p : online) if (eligible(p)) eligible.add(p.getUniqueId());
        if (eligible.isEmpty() && hosts.isEmpty()) return; // personne n'a de tag : rien à faire

        conns.clear();
        Map<UUID, Location> pos = new HashMap<UUID, Location>();
        for (Player p : online) {
            Object c = Nms.connection(p);
            if (c != null) conns.put(p.getUniqueId(), c);
            pos.put(p.getUniqueId(), p.getLocation());
        }

        // joueurs sans tag / inéligibles / dont le texte a changé : on retire leur tag
        Iterator<Map.Entry<UUID, Host>> it = hosts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Host> e = it.next();
            String wanted = plugin.tagOf(e.getKey());
            if (!eligible.contains(e.getKey()) || wanted == null || !wanted.equals(e.getValue().text)) {
                removeHost(e.getKey(), e.getValue());
                it.remove();
            }
        }

        double r2 = viewDistance * viewDistance;
        for (Player h : online) {
            UUID hid = h.getUniqueId();
            if (!eligible.contains(hid)) continue;
            Location hl = pos.get(hid);
            double drop = h.isSneaking() ? sneakDrop : 0.0;

            Host host = hosts.get(hid);
            if (host == null) {
                String text = plugin.tagOf(hid);
                String name = build(text, frame);
                Nms.Stand st = Nms.create(h.getWorld(), name, ride ? Nms.RIDE : Nms.MARKER);
                if (st == null) continue;
                host = new Host(text);
                host.stand = st;
                if (showSelf) host.self = Nms.create(h.getWorld(), name, Nms.MARKER);
                host.x = hl.getX(); host.y = hl.getY() + height - drop; host.z = hl.getZ();
                host.sx = host.x; host.sy = host.y; host.sz = host.z;
                host.px = hl.getX(); host.py = hl.getY(); host.pz = hl.getZ();
                hosts.put(hid, host);
            }

            // --- les autres joueurs
            Set<UUID> now = new HashSet<UUID>();
            for (Player v : online) {
                if (v == h || !conns.containsKey(v.getUniqueId())) continue;
                Location vl = pos.get(v.getUniqueId());
                if (vl.getWorld() != hl.getWorld() || hl.distanceSquared(vl) > r2) continue;
                if (!v.canSee(h)) continue;
                now.add(v.getUniqueId());
            }
            Set<UUID> gone = new HashSet<UUID>(host.viewers);
            gone.removeAll(now);
            destroyFor(host.stand, gone);

            Object spawn = null, attach = null;
            for (UUID vid : now) {
                if (host.viewers.contains(vid)) continue;
                if (spawn == null) {
                    Nms.setName(host.stand, build(host.text, frame));
                    spawn = ride ? Nms.spawn(host.stand, hl.getX(), hl.getY() + 1.35, hl.getZ())
                                 : Nms.spawn(host.stand, host.x, host.y, host.z);
                    if (ride) attach = Nms.attach(host.stand, h);
                    if (spawn == null || (ride && attach == null)) break;
                }
                Object c = conns.get(vid);
                Nms.send(c, spawn);
                if (ride) Nms.send(c, attach); // collé à la tête : suit le joueur sans retard
            }
            host.viewers = now;

            // --- le joueur lui-même
            Object selfConn = conns.get(hid);
            if (host.self != null && selfConn != null && !host.selfShown) {
                Nms.setName(host.self, build(host.text, frame));
                Object sp = Nms.spawn(host.self, host.sx, host.sy, host.sz);
                if (sp != null) { Nms.send(selfConn, sp); host.selfShown = true; }
            }
        }
    }

    private void destroyFor(Nms.Stand stand, Set<UUID> viewers) {
        if (stand == null || viewers.isEmpty()) return;
        Object pkt = Nms.destroy(stand);
        for (UUID vid : viewers) Nms.send(conns.get(vid), pkt);
    }

    /** Retire le tag d'un joueur chez tout le monde (autres joueurs + lui-même). */
    private void removeHost(UUID id, Host h) {
        destroyFor(h.stand, h.viewers);
        if (h.selfShown && h.self != null) Nms.send(conns.get(id), Nms.destroy(h.self));
    }

    /** Infos pour /tag debug : où ça bloque si le tag ne s'affiche pas. */
    public List<String> debug(Player p) {
        List<String> l = new ArrayList<String>();
        l.add("&7NMS 1.8.8 : " + (Nms.ok ? "&aOK" : "&cERREUR"));
        l.add("&7Animation : " + (running ? "&aactive" : "&cdésactivée (enabled: false ?)"));
        l.add("&7Mode : &f" + (ride ? "ride (collé à la tête)" : "explicit (hauteur réglable)")
                + " &7- hauteur : &f" + height + " &7(/tag mode, /tag height)");
        l.add("&7Tags enregistrés : &f" + plugin.tagCount() + " &7- tags actifs : &f" + hosts.size());
        String t = plugin.tagOf(p.getUniqueId());
        l.add("&7Ton tag : " + (t == null ? "&caucun &7(/tag " + p.getName() + " Aurexalium)" : "&e" + t));
        if (t != null) l.add("&7Éligible : " + (eligible(p) ? "&aoui"
                : "&cnon &7(mort, spectateur, invisible ou monde désactivé)"));
        Host h = hosts.get(p.getUniqueId());
        l.add("&7Ton tag est envoyé à : &f" + (h == null ? 0 : h.viewers.size()) + " &7autre(s) joueur(s)"
                + (h != null && h.selfShown ? " &7+ toi" : ""));
        int near = 0;
        double r2 = viewDistance * viewDistance;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != p && o.getWorld() == p.getWorld() && o.getLocation().distanceSquared(p.getLocation()) <= r2) near++;
        }
        l.add("&7Autres joueurs à moins de &f" + (int) viewDistance + " &7blocs : &f" + near);
        return l;
    }

    // ------------------------------------------------------------ événements
    /** Après une téléportation lointaine, un changement de monde, une mort ou un respawn : on repart de zéro. */
    private void markDirty(Player p) {
        if (!running) return;
        dirty.add(p.getUniqueId());
        if (flushScheduled) return;
        flushScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { flush(); }
        }, 3L);
    }

    private void flush() {
        flushScheduled = false;
        if (!running) return;
        for (UUID id : dirty) {
            Host h = hosts.remove(id);
            if (h != null) removeHost(id, h);
            for (Host other : hosts.values()) other.viewers.remove(id); // son client a perdu les tags des autres
        }
        dirty.clear();
        sync();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location a = e.getFrom(), b = e.getTo();
        if (b == null || a.getWorld() != b.getWorld() || a.distanceSquared(b) > 24 * 24) markDirty(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { markDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { markDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { markDirty(e.getEntity()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Host h = hosts.remove(id);
        if (h != null) removeHost(id, h);
        for (Host other : hosts.values()) other.viewers.remove(id);
        conns.remove(id);
        dirty.remove(id);
    }
}
