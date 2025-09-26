package org.kazamistudio.corePlugin.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class NettyIpProtector implements Listener {

    private static final String HANDLER_NAME = "NettyIpProtector";
    private static volatile boolean injected = false;
    private static Plugin plugin;

    // configuration defaults
    private static int MAX_GLOBAL_CONNECTIONS = 200;
    private static int MAX_SIMULTANEOUS_PER_IP = 5;
    private static int MAX_HANDSHAKE_LENGTH = 2048;
    private static int HANDSHAKE_WINDOW_MS = 10000;
    private static int HANDSHAKE_MAX_PER_WINDOW = 5;
    private static int ATTEMPT_WINDOW_SECONDS = 60;
    private static int ATTEMPT_LIMIT = 10;
    private static int AUTO_BAN_THRESHOLD = 10;
    private static int AUTO_BAN_DAYS = 7;
    private static boolean FORWARDING_ENABLED = false;
    private static String PROXY_TYPE = "none"; // none|bungee|velocity
    private static boolean REQUIRE_PROXY = false;
    private static boolean VALIDATE_SIGNATURE = false;
    private static String SIGNATURE_SECRET = "";

    // runtime state
    private static final AtomicInteger globalConnections = new AtomicInteger(0);
    private static final Map<String, Integer> ipActiveConnections = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> ipHandshakeTimes = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ipReputation = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Fingerprint>> deviceFingerprints = new ConcurrentHashMap<>();

    private static final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    private static final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    private static final Set<String> allowedProxies = ConcurrentHashMap.newKeySet();

    // persistence & logs
    private static File reputationFile;
    private static File logFile;
    private static volatile long lastReputationSave = 0L;
    private static long REPUTATION_SAVE_INTERVAL_MS_DEFAULT = 60 * 1000L;

    private static File configFile;
    private static YamlConfiguration config;

    // injection tracking
    private static final Set<Channel> injectedChannels = ConcurrentHashMap.newKeySet();

    // scheduler
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NettyIpProtector-Sched");
        t.setDaemon(true);
        return t;
    });

    private NettyIpProtector() {}

    /** Initialize and inject into server channels. Call from plugin onEnable() */
    public static synchronized void init(Plugin pl) {
        if (injected) return;
        plugin = pl;

        // load file config riêng
        configFile = new File(pl.getDataFolder(), "netty_protector.yml");
        if (!configFile.exists()) {
            pl.saveResource("netty_protector.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        config = cfg;

        try {
            PROXY_TYPE = cfg.getString("proxy-type", "none").toLowerCase(Locale.ROOT);
            REQUIRE_PROXY = cfg.getBoolean("require-proxy", false);
            FORWARDING_ENABLED = cfg.getBoolean("forwarding-enabled", false);
            VALIDATE_SIGNATURE = cfg.getBoolean("validate-signature", false);
            SIGNATURE_SECRET = cfg.getString("signature-secret", "");
            MAX_HANDSHAKE_LENGTH = cfg.getInt("max-handshake-length", MAX_HANDSHAKE_LENGTH);
            MAX_GLOBAL_CONNECTIONS = cfg.getInt("max-global-connections", MAX_GLOBAL_CONNECTIONS);
            MAX_SIMULTANEOUS_PER_IP = cfg.getInt("max-simultaneous-per-ip", MAX_SIMULTANEOUS_PER_IP);
            HANDSHAKE_WINDOW_MS = cfg.getInt("handshake-window-ms", HANDSHAKE_WINDOW_MS);
            HANDSHAKE_MAX_PER_WINDOW = cfg.getInt("handshake-max-per-window", HANDSHAKE_MAX_PER_WINDOW);
            ATTEMPT_WINDOW_SECONDS = cfg.getInt("attempt-window-seconds", ATTEMPT_WINDOW_SECONDS);
            ATTEMPT_LIMIT = cfg.getInt("attempt-limit", ATTEMPT_LIMIT);
            AUTO_BAN_THRESHOLD = cfg.getInt("auto-ban-threshold", AUTO_BAN_THRESHOLD);
            AUTO_BAN_DAYS = cfg.getInt("auto-ban-days", AUTO_BAN_DAYS);

            allowedProxies.addAll(cfg.getStringList("allowed-proxies"));
            whitelist.addAll(cfg.getStringList("whitelist"));
            blacklist.addAll(cfg.getStringList("blacklist"));

            // reputation save interval (seconds in config) -> ms internal
            long saveIntervalSec = cfg.getLong("reputation-save-interval-sec", 60);
            REPUTATION_SAVE_INTERVAL_MS_DEFAULT = Math.max(5, saveIntervalSec) * 1000L;

        } catch (Throwable t) {
            pl.getLogger().log(Level.WARNING, "[NettyIpProtector] Failed reading netty_protector.yml, using defaults.", t);
        }

        // files
        try {
            if (!pl.getDataFolder().exists()) pl.getDataFolder().mkdirs();
            reputationFile = new File(pl.getDataFolder(), "ip_reputation.properties");
            logFile = new File(pl.getDataFolder(), "netty_protector.log");
            if (!reputationFile.exists()) reputationFile.createNewFile();
            if (!logFile.exists()) logFile.createNewFile();
            loadIpReputation();
            // avoid immediate save on first incr
            lastReputationSave = System.currentTimeMillis();
        } catch (IOException e) {
            pl.getLogger().log(Level.WARNING, "[NettyIpProtector] Failed creating data files.", e);
        }

        // schedule periodic reputation save (seconds -> use config value)
        long saveIntervalSec = config.getLong("reputation-save-interval-sec", 60);
        SCHED.scheduleAtFixedRate(NettyIpProtector::saveIpReputation, saveIntervalSec, saveIntervalSec, TimeUnit.SECONDS);

        // register Bukkit listener for login/quit checks
        Bukkit.getPluginManager().registerEvents(new NettyIpProtector(), pl);

        // attempt injection
        try {
            injectIntoServerChannels();
            injected = true;
            pl.getLogger().info("[NettyIpProtector] Initialized (proxy=" + PROXY_TYPE + ", forwarding=" + FORWARDING_ENABLED + ")");
        } catch (Throwable t) {
            pl.getLogger().log(Level.WARNING, "[NettyIpProtector] Injection failed: " + t.getMessage(), t);
        }
    }


    /** Shutdown (remove injected handlers and save state) */
    public static synchronized void shutdown() {
        try {
            for (Channel ch : new ArrayList<>(injectedChannels)) {
                try {
                    if (ch.pipeline().get(HANDLER_NAME) != null) ch.pipeline().remove(HANDLER_NAME);
                } catch (Throwable ignored) {}
            }
            injectedChannels.clear();
            saveIpReputation();
            SCHED.shutdownNow();
            injected = false;
        } catch (Throwable t) {
            if (plugin != null) plugin.getLogger().warning("[NettyIpProtector] shutdown error: " + t.getMessage());
        }
    }

    // ------------------ Bukkit Listener methods ------------------
    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent ev) {
        String ip = ev.getAddress().getHostAddress();
        if (blacklist.contains(ip)) {
            ev.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "§cIP blocked by server security.");
            appendLog("PreLogin blocked (blacklist): " + ip + " player=" + ev.getName());
            return;
        }
        if (ipReputation.getOrDefault(ip, 0) >= AUTO_BAN_THRESHOLD) {
            ev.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "§cAuto-banned by reputation.");
            appendLog("PreLogin blocked (auto-ban): " + ip + " player=" + ev.getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent ev) {
        try {
            if (ev.getPlayer().getAddress() != null) {
                String ip = ev.getPlayer().getAddress().getAddress().getHostAddress();
                decrementIp(ip);
            } else {
                decrementIp("UNKNOWN");
            }
            decrementGlobal();
        } catch (Throwable ignored) {}
    }


    // ------------------ Netty injection ------------------
    private static void injectIntoServerChannels() throws Exception {
        Object mcServer = tryGetMinecraftServer();
        if (mcServer == null) {
            plugin.getLogger().warning("[NettyIpProtector] MinecraftServer handle not found; abort injection.");
            return;
        }

        Object serverConnection = tryGetFieldOrMethod(mcServer, new String[] {
                "serverConnection", "connection", "getServerConnection", "serverConnectionField", "networkIo"
        });

        if (serverConnection != null) {
            // iterate fields of serverConnection
            for (Field f : serverConnection.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(serverConnection);
                if (val == null) continue;
                if (val instanceof Collection) {
                    for (Object nm : (Collection<?>) val) {
                        Channel ch = extractChannel(nm);
                        if (ch != null) safeInject(ch);
                    }
                } else {
                    Channel ch = extractChannel(val);
                    if (ch != null) safeInject(ch);
                }
            }
            return;
        }

        // fallback: scan mcServer fields
        for (Field f : mcServer.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val = f.get(mcServer);
            if (val == null) continue;
            if (val instanceof io.netty.channel.ChannelFuture) {
                Channel ch = ((ChannelFuture) val).channel();
                safeInject(ch);
            } else if (val instanceof Collection) {
                for (Object o : (Collection<?>) val) {
                    Channel ch = extractChannel(o);
                    if (ch != null) safeInject(ch);
                }
            }
        }
    }

    private static Channel extractChannel(Object nm) {
        if (nm == null) return null;
        try {
            // common field names
            for (String fieldName : new String[]{"channel", "m", "k", "a", "socketChannel"}) {
                try {
                    Field f = nm.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object ch = f.get(nm);
                    if (ch instanceof Channel) return (Channel) ch;
                } catch (NoSuchFieldException ignored) {}
            }
            // channel() method
            try {
                Method m = nm.getClass().getMethod("channel");
                Object ch = m.invoke(nm);
                if (ch instanceof Channel) return (Channel) ch;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            if (plugin != null) plugin.getLogger().warning("[NettyIpProtector] extractChannel error: " + t.getMessage());
        }
        return null;
    }

    private static void safeInject(Channel ch) {
        if (ch == null) return;
        try {
            if (injectedChannels.contains(ch)) return;
            if (ch.pipeline().get(HANDLER_NAME) != null) {
                injectedChannels.add(ch);
                return;
            }
            ch.pipeline().addFirst(HANDLER_NAME, new ProtectorHandler());
            injectedChannels.add(ch);
            plugin.getLogger().info("[NettyIpProtector] Injected into channel: " + ch);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NettyIpProtector] safeInject failed: " + t.getMessage());
        }
    }

    // ------------------ Protector handler ------------------
    private static class ProtectorHandler extends ByteToMessageDecoder {

        private boolean handled = false;

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // ensure counters decrement on channel close
            String ip = remoteIp(ctx);
            decrementIp(ip);
            decrementGlobal();
            super.channelInactive(ctx);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (handled) {
                ctx.fireChannelRead(in.retain());
                return;
            }
            if (!in.isReadable()) return;

            in.markReaderIndex();
            Integer length = readVarIntSafely(in);
            if (length == null) {
                in.resetReaderIndex();
                ctx.fireChannelRead(in.retain());
                return;
            }

            if (length > MAX_HANDSHAKE_LENGTH) {
                kickAndLog(ctx, "Oversized handshake length: " + length);
                return;
            }

            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                ctx.fireChannelRead(in.retain());
                return;
            }

            int packetId = readVarInt(in);
            if (packetId != 0) {
                in.resetReaderIndex();
                ctx.fireChannelRead(in.retain());
                return;
            }

            int protocol = readVarInt(in);
            String serverAddress = readString(in);
            int port = in.readUnsignedShort();
            int nextState = readVarInt(in);

            String socketIp = remoteIp(ctx);

            // checks
            try {
                if (!checkGlobalLimit()) {
                    kickAndLog(ctx, "Global connection limit exceeded");
                    return;
                }
                if (!checkIpLimit(socketIp)) {
                    kickAndLog(ctx, "Too many simultaneous connections from IP");
                    return;
                }
                if (blacklist.contains(socketIp)) {
                    kickAndLog(ctx, "IP blacklisted");
                    return;
                }
                if (!checkHandshakeRate(socketIp)) {
                    incrReputation(socketIp, 2);
                    kickAndLog(ctx, "Handshake spam");
                    return;
                }

                String forwardedIp = null;
                String forwardedUuid = null;
                String username = null;
                String signature = null;

                if (serverAddress != null && serverAddress.contains("\0")) {
                    String[] parts = serverAddress.split("\0");
                    if (parts.length >= 3) {
                        username = parts[0].trim();
                        forwardedIp = parts[1].trim();
                        forwardedUuid = parts[2].trim();
                        if (parts.length >= 4) signature = parts[3].trim();
                    } else {
                        kickAndLog(ctx, "Malformed forwarded host");
                        return;
                    }
                } else {
                    username = serverAddress == null ? "" : serverAddress;
                }

                if (REQUIRE_PROXY && !allowedProxies.contains(socketIp)) {
                    kickAndLog(ctx, "Connections must come via allowed proxy IPs");
                    return;
                }

                if (!"none".equals(PROXY_TYPE)) {
                    if (forwardedIp == null || forwardedUuid == null) {
                        kickAndLog(ctx, "Forwarded data missing (proxy required)");
                        return;
                    }
                    if (!FORWARDING_ENABLED) {
                        kickAndLog(ctx, "Forwarding disabled but forwarded data present");
                        return;
                    }
                    if (!isValidIp(forwardedIp)) {
                        kickAndLog(ctx, "Invalid forwarded IP: " + forwardedIp);
                        return;
                    }
                    if (!isValidUuid(forwardedUuid)) {
                        kickAndLog(ctx, "Invalid forwarded UUID: " + forwardedUuid);
                        return;
                    }
                    if (VALIDATE_SIGNATURE) {
                        if (SIGNATURE_SECRET == null || SIGNATURE_SECRET.isEmpty()) {
                            plugin.getLogger().warning("[NettyIpProtector] Signature enabled but no secret set");
                            kickAndLog(ctx, "Signature not configured");
                            return;
                        }
                        if (signature == null || signature.isEmpty()) {
                            kickAndLog(ctx, "Missing signature");
                            return;
                        }
                        String payload = username + "|" + forwardedIp + "|" + forwardedUuid;
                        if (!verifyHmacSHA256(payload, signature, SIGNATURE_SECRET)) {
                            kickAndLog(ctx, "Signature verification failed");
                            incrReputation(forwardedIp, 3);
                            return;
                        }
                    }
                    if (config != null && config.getBoolean("netty-protector.require-sock-forward-equal", false)) {
                        if (!socketIp.equals(forwardedIp)) {
                            kickAndLog(ctx, "Socket IP != forwarded IP");
                            incrReputation(socketIp, 2);
                            return;
                        }
                    }

                    // fingerprinting (use thread-safe deque)
                    String normUuid = normalizeKey(forwardedUuid);
                    Deque<Fingerprint> q = deviceFingerprints.computeIfAbsent(normUuid, k -> new ConcurrentLinkedDeque<>());
                    long now = System.currentTimeMillis();
                    q.addLast(new Fingerprint(socketIp, serverAddress == null ? "" : serverAddress, now));
                    while (q.size() > 20) q.pollFirst();
                    if (q.size() >= 2) {
                        Fingerprint[] arr = q.toArray(new Fingerprint[0]);
                        Fingerprint prev = arr[arr.length - 2];
                        Fingerprint last = arr[arr.length - 1];
                        if (!prev.ip.equals(last.ip) && last.timestamp - prev.timestamp < 60_000L) {
                            plugin.getLogger().warning("[NettyIpProtector] UUID rapid IP change: " + normUuid + " " + prev.ip + " -> " + last.ip);
                            incrReputation(last.ip, 2);
                        }
                    }
                } else {
                    if (FORWARDING_ENABLED && (forwardedIp == null || forwardedUuid == null)) {
                        kickAndLog(ctx, "Forwarding expected but missing forwarded data");
                        return;
                    }
                }

                // track attempts
                trackAttempt(socketIp, false);

                // OK — mark handled, remove handler and forward bytes untouched
                handled = true;
                in.resetReaderIndex();
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(in.retain());
                return;

            } finally {
                // nothing else here; channelInactive will decrement counts
            }
        }
    }

    // ------------------ helpers & limits ------------------

    private static boolean checkGlobalLimit() {
        if (globalConnections.incrementAndGet() > MAX_GLOBAL_CONNECTIONS) {
            globalConnections.decrementAndGet();
            return false;
        }
        return true;
    }

    private static void decrementGlobal() {
        try { globalConnections.decrementAndGet(); } catch (Throwable ignored) {}
    }

    private static boolean checkIpLimit(String ip) {
        if (ip == null) return true;
        int val = ipActiveConnections.merge(ip, 1, Integer::sum);
        if (val > MAX_SIMULTANEOUS_PER_IP) {
            ipActiveConnections.merge(ip, -1, Integer::sum);
            return false;
        }
        return true;
    }

    private static void decrementIp(String ip) {
        if (ip == null) return;
        ipActiveConnections.computeIfPresent(ip, (k, v) -> {
            int nv = Math.max(0, v - 1);
            return nv == 0 ? null : nv;
        });
    }

    private static boolean checkHandshakeRate(String ip) {
        Deque<Long> q = ipHandshakeTimes.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > HANDSHAKE_WINDOW_MS) q.removeFirst();
            return q.size() <= HANDSHAKE_MAX_PER_WINDOW;
        }
    }

    private static void trackAttempt(String ip, boolean blocked) {
        if (ip == null) ip = "UNKNOWN";
        Deque<Long> q = ipAttempts.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        long cutoff = now - ATTEMPT_WINDOW_SECONDS * 1000L;
        q.addLast(now);
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst();
        if (q.size() >= ATTEMPT_LIMIT) {
            plugin.getLogger().warning("[NettyIpProtector] High rate attempts from " + ip + " (" + q.size() + ")");
            incrReputation(ip, 1);
        }
        if (blocked) {
            int c = ipReputation.merge(ip, 1, Integer::sum);
            if (c >= AUTO_BAN_THRESHOLD) {
                try {
                    Bukkit.getBanList(BanList.Type.IP).addBan(ip, "Auto-banned by NettyIpProtector", new Date(System.currentTimeMillis() + AUTO_BAN_DAYS * 86400L * 1000L), "NettyIpProtector");
                    plugin.getLogger().warning("[NettyIpProtector] Auto-banned IP " + ip + " score=" + c);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[NettyIpProtector] Failed to add ban: " + t.getMessage());
                }
            }
        }
    }

    private static synchronized void incrReputation(String ip, int delta) {
        if (ip == null) return;
        ipReputation.merge(ip, delta, Integer::sum);
        long now = System.currentTimeMillis();
        if (now - lastReputationSave > REPUTATION_SAVE_INTERVAL_MS_DEFAULT) {
            saveIpReputation();
            lastReputationSave = now;
        }
    }

    private static void saveIpReputation() {
        try {
            if (reputationFile == null) return;
            Properties props = new Properties();
            for (Map.Entry<String, Integer> e : ipReputation.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
            try (OutputStream os = new FileOutputStream(reputationFile)) {
                props.store(os, "ip reputation");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[NettyIpProtector] saveIpReputation failed: " + t.getMessage());
        }
    }

    private static void loadIpReputation() {
        try {
            if (reputationFile == null) return;
            Properties props = new Properties();
            try (InputStream is = new FileInputStream(reputationFile)) { props.load(is); } catch (FileNotFoundException ignored) {}
            for (String k : props.stringPropertyNames()) {
                int v = Integer.parseInt(props.getProperty(k, "0"));
                ipReputation.put(k, v);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[NettyIpProtector] loadIpReputation failed: " + t.getMessage());
        }
    }

    private static void kickAndLog(ChannelHandlerContext ctx, String reason) {
        try {
            String ip = remoteIp(ctx);
            ctx.close();
            String msg = "Kicked " + ip + " | reason=" + reason;
            plugin.getLogger().warning("[NettyIpProtector] " + msg);
            appendLog(msg);
            trackAttempt(ip, true);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void appendLog(String msg) {
        try {
            if (logFile == null) return;
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            try (FileWriter fw = new FileWriter(logFile, true)) { fw.write("[" + time + "] " + msg + System.lineSeparator()); }
        } catch (Throwable t) {
            // ignore
        }
    }

    // ------------------ util ------------------
    private static boolean isValidIp(String ip) {
        if (ip == null) return false;
        return ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private static boolean isValidUuid(String uuid) {
        if (uuid == null) return false;
        try {
            String formatted = uuid.contains("-") ? uuid : uuid.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            UUID.fromString(formatted);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeKey(String k) {
        if (k == null) return null;
        return k.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String remoteIp(ChannelHandlerContext ctx) {
        try {
            Object addr = ctx.channel().remoteAddress();
            if (addr instanceof InetSocketAddress) {
                return ((InetSocketAddress) addr).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {}
        return "UNKNOWN";
    }

    // VarInt safe readers
    private static Integer readVarIntSafely(ByteBuf buf) {
        buf.markReaderIndex();
        try {
            int numRead = 0;
            int result = 0;
            while (buf.isReadable()) {
                byte read = buf.readByte();
                int value = read & 0x7F;
                result |= value << (7 * numRead);
                numRead++;
                if (numRead > 5) {
                    buf.resetReaderIndex();
                    return null;
                }
                if ((read & 0x80) == 0) return result;
            }
            buf.resetReaderIndex();
            return null;
        } catch (Throwable t) {
            try { buf.resetReaderIndex(); } catch (Throwable ignored) {}
            return null;
        }
    }

    private static int readVarInt(ByteBuf buf) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buf.readByte();
            int value = read & 0x7F;
            result |= value << (7 * numRead);
            numRead++;
            if (numRead > 5) throw new RuntimeException("VarInt too big");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > 32767) return null;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------ reflection helpers ------------------
    private static Object tryGetMinecraftServer() {
        try {
            Object craft = callMethod(Bukkit.getServer(), "getHandle");
            if (craft != null) {
                Object mc = tryGetFieldOrMethod(craft, new String[]{"server", "getServer", "minecraftServer"});
                if (mc != null) return mc;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object tryGetFieldOrMethod(Object target, String[] names) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        for (String n : names) {
            try {
                Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                try {
                    Method m = cls.getDeclaredMethod(n);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object callMethod(Object target, String name, Object... args) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try { return m.invoke(target, args); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // ------------------ HMAC verify ------------------
    private static boolean verifyHmacSHA256(String payload, String hexSignature, String secret) {
        try {
            byte[] expected = hexToBytes(hexSignature);
            if (expected == null || expected.length == 0) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] got = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, got);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            plugin.getLogger().warning("[NettyIpProtector] Signature verification error: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] hexToBytes(String s) {
        if (s == null) return new byte[0];
        s = s.replaceAll("\\s+", "");
        if (s.length() == 0) return new byte[0];
        if ((s.length() % 2) != 0) {
            // odd length -> invalid
            return new byte[0];
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i+1), 16);
            if (hi == -1 || lo == -1) {
                return new byte[0];
            }
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }

    // ------------------ Fingerprint ------------------
    private static class Fingerprint {
        final String ip;
        final String host;
        final long timestamp;
        Fingerprint(String ip, String host, long ts) { this.ip = ip; this.host = host; this.timestamp = ts; }
    }
}
