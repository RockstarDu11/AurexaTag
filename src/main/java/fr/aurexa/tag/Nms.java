package fr.aurexa.tag;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Accès NMS 1.8.8 (v1_8_R3) par réflexion. Les armor stands du titre n'existent JAMAIS côté serveur :
 * ils sont envoyés par paquets aux joueurs, donc aucun risque de bloquer une téléportation.
 */
public final class Nms {

    public static final class Stand {
        final Object nms;
        final int id;
        final Object dw;
        Stand(Object nms, int id, Object dw) { this.nms = nms; this.id = id; this.dw = dw; }
    }

    /**
     * Flags de l'armor stand (octet 10) : 1 = petit, 2 = sans gravité, 8 = sans socle, 16 = marker (aucune hitbox).
     * RIDE : petit stand posé sur la tête du joueur (collé comme un nametag).
     * MARKER : stand sans hitbox, placé à une position précise.
     */
    public static final byte RIDE = 1 | 2 | 8;
    public static final byte MARKER = 2 | 8 | 16;

    public static boolean ok;
    private static Method getPlayerHandle, getWorldHandle, sendPacket, setPos, setInvisible, setCustomName,
            setCustomNameVisible, getId, getDataWatcher, watch;
    private static Field pingField;
    private static Field connField;
    private static Constructor<?> standCtor, spawnCtor, teleportCtor, attachCtor, metaCtor, destroyCtor;

    static {
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            String nms = "net.minecraft.server." + v + ".";
            String cb = "org.bukkit.craftbukkit." + v + ".";
            getPlayerHandle = Class.forName(cb + "entity.CraftPlayer").getMethod("getHandle");
            getWorldHandle = Class.forName(cb + "CraftWorld").getMethod("getHandle");
            Class<?> entity = Class.forName(nms + "Entity");
            Class<?> entityPlayer = Class.forName(nms + "EntityPlayer");
            Class<?> world = Class.forName(nms + "World");
            Class<?> stand = Class.forName(nms + "EntityArmorStand");
            Class<?> packet = Class.forName(nms + "Packet");
            Class<?> dw = Class.forName(nms + "DataWatcher");

            connField = entityPlayer.getField("playerConnection");
            sendPacket = connField.getType().getMethod("sendPacket", packet);
            standCtor = stand.getConstructor(world);
            setPos = entity.getMethod("setPosition", double.class, double.class, double.class);
            setInvisible = entity.getMethod("setInvisible", boolean.class);
            setCustomName = entity.getMethod("setCustomName", String.class);
            setCustomNameVisible = entity.getMethod("setCustomNameVisible", boolean.class);
            getId = entity.getMethod("getId");
            getDataWatcher = entity.getMethod("getDataWatcher");

            spawnCtor = Class.forName(nms + "PacketPlayOutSpawnEntityLiving")
                    .getConstructor(Class.forName(nms + "EntityLiving"));
            teleportCtor = Class.forName(nms + "PacketPlayOutEntityTeleport").getConstructor(entity);
            attachCtor = Class.forName(nms + "PacketPlayOutAttachEntity").getConstructor(int.class, entity, entity);
            metaCtor = Class.forName(nms + "PacketPlayOutEntityMetadata").getConstructor(int.class, dw, boolean.class);
            destroyCtor = Class.forName(nms + "PacketPlayOutEntityDestroy").getConstructor(int[].class);
            ok = true;

            try { watch = dw.getMethod("watch", int.class, Object.class); } catch (Throwable ignored) { watch = null; }
            try { pingField = entityPlayer.getField("ping"); } catch (Throwable ignored) { pingField = null; }
        } catch (Throwable t) {
            ok = false;
        }
    }

    private Nms() {}

    /** Connexion réseau d'un joueur (à mettre en cache). */
    public static Object connection(Player p) {
        try { return connField.get(getPlayerHandle.invoke(p)); } catch (Throwable t) { return null; }
    }

    public static void send(Object conn, Object packet) {
        if (conn == null || packet == null) return;
        try { sendPacket.invoke(conn, packet); } catch (Throwable ignored) { }
    }

    /** Ping du joueur en ms (0 si indisponible). */
    public static int ping(Player p) {
        if (pingField == null) return 0;
        try { return pingField.getInt(getPlayerHandle.invoke(p)); } catch (Throwable t) { return 0; }
    }

    /** Crée un faux armor stand invisible avec un nom visible ; flags = RIDE ou MARKER. */
    public static Stand create(World w, String name, byte flags) {
        try {
            Object s = standCtor.newInstance(getWorldHandle.invoke(w));
            setInvisible.invoke(s, true);
            setCustomNameVisible.invoke(s, true);
            setCustomName.invoke(s, name);
            Object dw = getDataWatcher.invoke(s);
            if (watch != null) {
                try { watch.invoke(dw, 10, Byte.valueOf(flags)); } catch (Throwable ignored) { }
            }
            return new Stand(s, (Integer) getId.invoke(s), dw);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setName(Stand s, String name) {
        try { setCustomName.invoke(s.nms, name); } catch (Throwable ignored) { }
    }

    public static Object spawn(Stand s, double x, double y, double z) {
        try {
            setPos.invoke(s.nms, x, y, z);
            return spawnCtor.newInstance(s.nms);
        } catch (Throwable t) { return null; }
    }

    /** Colle le stand sur le joueur côté client : il suit sa tête sans aucun retard. */
    public static Object attach(Stand s, Player host) {
        try { return attachCtor.newInstance(0, s.nms, getPlayerHandle.invoke(host)); } catch (Throwable t) { return null; }
    }

    /** Paquet qui déplace le stand à cette position (envoyé aux spectateurs quand le joueur bouge). */
    public static Object moveTo(Stand s, double x, double y, double z) {
        try {
            setPos.invoke(s.nms, x, y, z);
            return teleportCtor.newInstance(s.nms);
        } catch (Throwable t) { return null; }
    }

    public static Object meta(Stand s) {
        try { return metaCtor.newInstance(s.id, s.dw, true); } catch (Throwable t) { return null; }
    }

    public static Object destroy(Stand s) {
        try { return destroyCtor.newInstance(new Object[]{new int[]{s.id}}); } catch (Throwable t) { return null; }
    }
}
