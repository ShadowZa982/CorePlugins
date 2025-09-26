package org.kazamistudio.corePlugin;

import org.bukkit.*;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.kazamistudio.corePlugin.anticheat.AntiCheatManager;
import org.kazamistudio.corePlugin.anti.AntiKillAuraListener;
import org.kazamistudio.corePlugin.anti.AntiXrayCommand;
import org.kazamistudio.corePlugin.anti.AntiXrayListener;
import org.kazamistudio.corePlugin.auth.*;
import org.kazamistudio.corePlugin.boardcast.BroadcastManager;
import org.kazamistudio.corePlugin.commands.*;
import org.kazamistudio.corePlugin.itemshow.ItemShowListener;
import org.kazamistudio.corePlugin.listener.*;
import org.kazamistudio.corePlugin.manager.*;
import org.kazamistudio.corePlugin.servercore.AutoRestartTask;
import org.kazamistudio.corePlugin.vote.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;


public class CorePlugin extends JavaPlugin {

    // -------- Static / Singleton --------
    private static CorePlugin instance;
    public static CorePlugin getInstance() { return instance; }

    // -------- Managers / Services --------
    private MuteManager muteManager;
    private DiscordManager discordManager;
    private OpManager opManager;
    private BroadcastManager broadcastManager;

    // Auth
    private AuthDatabaseManager authDb;
    private AuthManager authManager;
    private EmailService emailService;

    private File spawnFile;
    private FileConfiguration spawnConfig;

    private File lastLocFile;
    private FileConfiguration lastLocConfig;

    // OpAuth
    private OpAuthListener opAuthListener;

    // Auto-Restart
    private AutoRestartTask autoRestartTask;

    // Vote
    private VoteServer voteServer;
    private int voteBroadcastTaskId = -1;

    // Anti systems
    private AntiCheatManager antiCheatManager;
    private AntiXrayListener antiXrayListener;
    private AntiKillAuraListener antiKillAuraListener;

    // Discord config file
    private FileConfiguration botDiscordConfig;

    // OpAuth config file
    private File opAuthFile;
    private FileConfiguration opAuthConfig;

    // Misc
    public final List<Map<String, String>> chatMessages = new CopyOnWriteArrayList<>();

    // =====================================================
    //                    LIFECYCLE
    // =====================================================
    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        loadSpawnConfig();
        loadLastLocConfig();
        saveConfig();


        // Init core managers & files
        initManagers();
        initFiles();

        // Khởi động các hệ thống theo config
        initSubsystems();

        // Đăng ký command & listener nền tảng
        registerCommands();
        registerBaseListeners();

        // Discord status (nếu đang bật)
        if (discordManager != null) {
            safeRun(() -> discordManager.sendServerStatusEmbed(true), "Gửi Discord status bật");
        }


        // Packet IP check (BungeeCord/Velocity forwarding)
        boolean forwarding = getConfig().getBoolean("ip-forwarding", false);
        PacketIpCheck packetIpCheck = new PacketIpCheck(this, forwarding);
        packetIpCheck.register();
        getServer().getPluginManager().registerEvents(packetIpCheck, this);

        logStartupMessage(true);
    }

    @Override
    public void onDisable() {
        // Gửi Discord status tắt (trước khi đóng bot)
        if (discordManager != null) {
            safeRun(() -> discordManager.sendServerStatusEmbed(false), "Gửi Discord status tắt");
        }

        // Tắt tất cả subsystem sạch sẽ
        shutdownSubsystems();

        logStartupMessage(false);
    }

    // =====================================================
    //                 INIT / SHUTDOWN GROUPS
    // =====================================================
    private void initManagers() {
        this.opAuthListener = new OpAuthListener(this);
        this.muteManager   = new MuteManager(this);
        this.opManager     = new OpManager(this);
        this.emailService  = new EmailService(this);
        // Không đăng ký AntiXray tại đây để tránh đăng ký đôi. Sẽ làm trong startAntiXray().
    }

    private void initFiles() {
        loadOpAuthConfig();
        loadBotDiscordConfig();
    }

    private void initSubsystems() {
        // Auto-Restart
        if (getConfig().getBoolean("auto-restart.enabled", true)) startAutoRestart();

        // Discord
        if (getConfig().getBoolean("discord.enabled", false)) startDiscordBot();

        // Login/Auth
        if (getConfig().getBoolean("login.enabled", true)) startLoginSystem();

        // Vote
        if (getConfig().getBoolean("vote.enabled", true)) startVoteSystem();

        // Broadcast (core broadcast tự động)
        if (getConfig().getBoolean("broadcast.enabled", true)) startBroadcastSystem();

        // Anti features
        if (getConfig().getBoolean("anti-xray.enabled", false)) startAntiXray();
        if (getConfig().getBoolean("anti-killaura.enabled", false)) startAntiKillAura();
        if (getConfig().getBoolean("anticheat.enabled", true)) startAntiCheat();
    }

    private void shutdownSubsystems() {
        stopVoteSystem();
        stopBroadcastSystem();
        stopLoginSystem();
        stopAutoRestart();
        stopAntiKillAura();
        stopAntiXray();
        stopAntiCheat();
        stopDiscordBot();
    }

    // =====================================================
    //                     COMMANDS
    // =====================================================
    private void registerCommands() {
        // Core & reload
        setExecutorSafe("corereload", new ReloadCommand(this, muteManager));

        // OP tools
        setExecutorSafe("coreopnew", new CoreOpNewCommand(opManager));
        setExecutorSafe("coreop", new CoreOpCommand(opManager));
        setExecutorSafe("coreunbanip", new CoreUnbanIpCommand(this, opManager));
        setExecutorSafe("opaccept", new OpAcceptCommand(opAuthListener));
        setExecutorSafe("oppass", new OpPassCommand(opAuthListener, opManager));
        setExecutorSafe("antixray", new AntiXrayCommand(this, ensureAntiXrayListener()));

        setExecutorSafe("coresetspawn", new CoreSetSpawnCommand(this));

        // Vote
        setExecutorSafe("vote", new VoteCommand(this));
        setExecutorSafe("report", new ReportCommand(this));

        setExecutorSafe("coreipchange", new CoreCommand(opManager));
        setExecutorSafe("coredeop", new CoreCommand(opManager));

        // Login commands (nếu bật)
        if (getConfig().getBoolean("login.enabled", true)) {
            setExecutorSafe("login", new LoginCommand(this, authManager));
            setExecutorSafe("register", new RegisterCommand(this, authManager));
            setExecutorSafe("rspw", new ResetPwRequestCommand(this, authManager));
            setExecutorSafe("rscode", new ResetCodeCommand(this, authManager));
            setExecutorSafe("addmail", new AddMailCommand(this, authManager));
            setExecutorSafe("pwrm", new AdminPwResetCommand(this, authManager));
        }
    }

    private void setExecutorSafe(String name, Object executor) {
        var cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("[Core] Lệnh '" + name + "' không có trong plugin.yml! Bỏ qua.");
            return;
        }

        if (executor instanceof CommandExecutor ce) {
            cmd.setExecutor(ce);
        }
        if (executor instanceof org.bukkit.command.TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    // =====================================================
    //                     LISTENERS
    // =====================================================
    private void registerBaseListeners() {
        PluginManager pm = getServer().getPluginManager();

        pm.registerEvents(opAuthListener, this);
        pm.registerEvents(new ChatListener(this, muteManager), this);
        pm.registerEvents(new OpProtectionListener(this), this);
        pm.registerEvents(new OpBlockListener(this), this);
        pm.registerEvents(new OpNotifierListener(this), this);
        pm.registerEvents(new PlayerStatusListener(this), this);
        pm.registerEvents(new ItemShowListener(), this);
        pm.registerEvents(new RespawnListener(this), this);


    }

    // =====================================================
    //                 DISCORD BOT SUBSYSTEM
    // =====================================================
    public void startDiscordBot() {
        String token = getConfig().getString("discord.token", "");
        String channelId = getConfig().getString("discord.channel-id", "");
        String adminChannelId = getConfig().getString("discord.admin-channel-id", "");
        String logId = getConfig().getString("discord.log-band-id");
        String voteChannelId = getConfig().getString("discord.vote-chanel-id");
        String restartChannelId = getConfig().getString("discord.restart-channel-id");

        getLogger().info("[Core] Bật Discord Bot (channel=" + channelId + ")");

        this.discordManager = new DiscordManager(this);
        safeRun(() -> discordManager.startBot(token, channelId, adminChannelId, logId, voteChannelId, restartChannelId),
                "Khởi động Discord Bot");
    }

    public void stopDiscordBot() {
        getLogger().info("[Core] Tắt Discord Bot");
        if (discordManager != null) {
            safeRun(discordManager::shutdown, "Tắt Discord Bot");
            discordManager = null;
        }
    }

    // =====================================================
    //                   LOGIN / AUTH SUBSYSTEM
    // =====================================================
    public void startLoginSystem() {
        String dbType = getConfig().getString("login.database.type", "sqlite").toLowerCase();
        String enc = getConfig().getString("login.encryption", "bcrypt");
        boolean checkIp = getConfig().getBoolean("login.check-ip", true);

        getLogger().info("[Core] Bật Login System (db=" + dbType + ", enc=" + enc + ", check-ip=" + checkIp + ")");

        try {
            this.authDb = new AuthDatabaseManager(this);
            this.authDb.init(); // mở connection + tạo bảng

            if (!this.authDb.isConnected()) {
                throw new IllegalStateException("Database connection chưa sẵn sàng (null hoặc closed).");
            }

            this.authManager = new AuthManager(this, authDb);

            getServer().getPluginManager().registerEvents(
                    new AuthPreLoginListener(this, authManager),
                    this
            );

            logAuthLogin(true);
        } catch (Exception e) {
            getLogger().severe("[Core] Không thể khởi động Login System: " + e.getMessage());
            e.printStackTrace();
            logAuthLogin(false);
            stopLoginSystem();
        }
    }

    public void stopLoginSystem() {
        getLogger().info("[Core] Tắt Login System");

        if (authDb != null) {
            safeRun(() -> {
                try {
                    authDb.close(); // đã implement AutoCloseable
                } catch (Exception e) {
                    getLogger().warning("[Core] Lỗi khi đóng Auth DB: " + e.getMessage());
                }
            }, "Đóng kết nối Auth DB");
        }

        authManager = null;
        authDb = null;
    }
    // Hook sau login/register cho op
    public void triggerOpAuthIfNeeded(Player player) {
        if (player == null) return;
        if (player.isOp() && getOpAuthListener() != null) {
            safeRun(() -> getOpAuthListener().startOpAuth(player), "Trigger OpAuth sau login");
        }
    }

    // =====================================================
    //                     VOTE SUBSYSTEM
    // =====================================================
    public void startVoteSystem() {
        boolean useVotifier = getConfig().getBoolean("vote.use-votifier", true);
        int port = getConfig().getInt("vote.port", 8192);

        getLogger().info("[Core] Bật Vote System (useVotifier=" + useVotifier + ", port=" + port + ")");

        VoteManager voteManager = new VoteManager(this);

        if (useVotifier) {
            getServer().getPluginManager().registerEvents(new VoteListener(this, voteManager), this);
            getLogger().info("[Vote] Đang sử dụng Votifier để nhận vote.");
        } else {
            this.voteServer = new VoteServer(this, port, voteManager);
            safeRun(voteServer::start, "Khởi động Vote HTTP server");
            getLogger().info("[Vote] Đang sử dụng HTTP server để nhận vote.");
        }

        // Broadcast reminder theo config
        int interval = Math.max(1, getConfig().getInt("vote.broadcast-interval", 30)); // phút
        cancelVoteBroadcastTask();
        this.voteBroadcastTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            String bc = colorize(getConfig().getString("vote.broadcast-message", "&a[Vote] Hãy vote cho server để nhận quà!"));
            Bukkit.broadcastMessage(bc);

            String soundName = getConfig().getString("vote.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, 1f, 1f);
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("[Vote] Âm thanh '" + soundName + "' không tồn tại! Hãy kiểm tra lại.");
            }

            // Gửi thông báo lên Discord (nếu bot đang bật)
            if (getDiscordManager() != null) {
                List<String> links = getBotDiscordConfig().getStringList("vote-reminder-embed.links");
                safeRun(() -> getDiscordManager().sendVoteReminderEmbed(bc, links), "Gửi vote reminder lên Discord");
            }
        }, 20L, 20L * 60L * interval).getTaskId();
    }

    private void cancelVoteBroadcastTask() {
        if (voteBroadcastTaskId != -1) {
            Bukkit.getScheduler().cancelTask(voteBroadcastTaskId);
            voteBroadcastTaskId = -1;
        }
    }

    public void stopVoteSystem() {
        getLogger().info("[Core] Tắt Vote System");
        cancelVoteBroadcastTask();
        if (voteServer != null) {
            safeRun(voteServer::stop, "Dừng Vote HTTP server");
            voteServer = null;
        }
        // Votifier listener sẽ được Bukkit cleanup khi disable plugin / reload.
    }

    // =====================================================
    //                   BROADCAST SUBSYSTEM
    // =====================================================
    public void startBroadcastSystem() {
        if (broadcastManager == null) broadcastManager = new BroadcastManager(this);
        safeRun(() -> {
            broadcastManager.loadConfig();
            broadcastManager.start();
        }, "Bật Auto Broadcast");
        getLogger().info("[Core] Bật Broadcast");
    }

    public void stopBroadcastSystem() {
        getLogger().info("[Core] Tắt Broadcast");
        if (broadcastManager != null) {
            safeRun(broadcastManager::stop, "Dừng Auto Broadcast");
        }
    }

    // =====================================================
    //                 AUTO RESTART SUBSYSTEM
    // =====================================================
    public void startAutoRestart() {
        getLogger().info("[Core] Bật Auto-Restart");
        if (autoRestartTask == null) autoRestartTask = new AutoRestartTask(this);
        safeRun(autoRestartTask::start, "Bật Auto-Restart task");
    }

    public void stopAutoRestart() {
        getLogger().info("[Core] Tắt Auto-Restart");
        if (autoRestartTask != null) {
            safeRun(autoRestartTask::stop, "Dừng Auto-Restart task");
        }
    }

    // =====================================================
    //                  ANTI SYSTEMS
    // =====================================================
    public void startAntiXray() {
        int banTime = getConfig().getInt("anti-xray.ban-time", 600);
        int maxOres = getConfig().getInt("anti-xray.max-ores", 15);
        getLogger().info("[Core] Bật Anti-Xray (banTime=" + banTime + "s, maxOres=" + maxOres + ")");

        if (antiXrayListener == null) antiXrayListener = new AntiXrayListener(this);
        getServer().getPluginManager().registerEvents(antiXrayListener, this);
    }

    public void stopAntiXray() {
        getLogger().info("[Core] Tắt Anti-Xray");
        if (antiXrayListener != null) {
            unregisterListener(antiXrayListener);
            antiXrayListener = null;
        }
    }

    public void startAntiKillAura() {
        int banTime = getConfig().getInt("anti-killaura.ban-time", 600);
        int maxHits = getConfig().getInt("anti-killaura.max-hits-per-second", 15);
        getLogger().info("[Core] Bật Anti-KillAura (banTime=" + banTime + "s, maxHits=" + maxHits + ")");

        if (antiKillAuraListener == null) antiKillAuraListener = new AntiKillAuraListener(this);
        getServer().getPluginManager().registerEvents(antiKillAuraListener, this);
    }

    public void stopAntiKillAura() {
        getLogger().info("[Core] Tắt Anti-KillAura");
        if (antiKillAuraListener != null) {
            unregisterListener(antiKillAuraListener);
            antiKillAuraListener = null;
        }
    }

    public void startAntiCheat() {
        String severity = getConfig().getString("anticheat.severity", "warn");
        getLogger().info("[Core] Bật AntiCheat (severity=" + severity + ")");
        // Giữ tham chiếu để tuỳ chọn stop về sau
        if (this.antiCheatManager == null) this.antiCheatManager = new AntiCheatManager(this);
    }

    public void stopAntiCheat() {
        getLogger().info("[Core] Tắt AntiCheat");
        // Nếu AntiCheatManager có API dừng, gọi tại đây.
        this.antiCheatManager = null;
    }

    // =====================================================
    //                RELOAD (2 biến thể)
    // =====================================================
    public void reloadCore(boolean sendMessage, CommandSender sender) {
        long start = System.currentTimeMillis();

        // reload config.yml
        reloadConfig();

        // reload mute data
        if (getMuteManager() != null) getMuteManager().reload();

        // reload broadcast.yml
        if (broadcastManager != null) {
            stopBroadcastSystem();
            startBroadcastSystem();
        }

        // reload bot_discord.yml
        if (discordManager != null) {
            loadBotDiscordConfig();
            safeRun(() -> discordManager.reloadConfig(), "Reload Discord bot config");
        }

        // thông báo
        if (sendMessage && sender != null) {
            long took = System.currentTimeMillis() - start;
            sender.sendMessage(colorize(
                    getConfig().getString("messages.reload-success",
                            "&a✅ Reload thành công trong &e" + took + "ms")));
        }
    }

    // =====================================================
    //                    LOGGING / UTILS
    // =====================================================
    private void logAuthLogin(boolean enable) {
        if (enable) {
            logWithColor("&7[&a✔&7] &l&6Auth &fĐã bật");
        } else {
            logWithColor("&7[&c✘&7] &l&5Auth &fKhông thể khởi tạo hệ thống đăng nhập");
        }
    }

    private void logStartupMessage(boolean enable) {
        if (enable) {
            logWithColor("&b╔════════════════════════════════════╗");
            logWithColor("&b║        &f⚡  &9Kazami Studio &f⚡          &b║");
            logWithColor("&b╠════════════════════════════════════╣");
            logWithColor("&7┃ &a✔ &fPlugin: &b" + getDescription().getName());
            logWithColor("&7┃ &a✔ &fVersion: &a" + getDescription().getVersion());
            logWithColor("&7┃ &a✔ &fAuthor: &eKazami Studio");
            logWithColor("&7┃ &a✔ &fDiscord: &9https://discord.gg/kQsg6JyT");
            logWithColor("&7┃ &a✔ &6Core &ađã được bật!");
            logWithColor("&b╚════════════════════════════════════╝");
        } else {
            logWithColor("&c[&6Core&c] Plugin đã tắt.");
        }
    }

    private void logWithColor(String msg) {
        getServer().getConsoleSender().sendMessage(colorize(msg));
    }

    private void safeRun(Runnable r, String actionName) {
        try {
            r.run();
        } catch (Throwable t) {
            getLogger().warning("[Core] Lỗi khi thực hiện: " + actionName + " -> " + t.getMessage());
        }
    }

    private void unregisterListener(Listener listener) {
        try {
            HandlerList.unregisterAll(listener);
        } catch (Throwable t) {
            getLogger().warning("[Core] unregister listener lỗi: " + t.getMessage());
        }
    }

    // =====================================================
    //                     FILES / CONFIG
    // =====================================================
    public void loadBotDiscordConfig() {
        File file = new File(getDataFolder(), "bot_discord.yml");
        if (!file.exists()) {
            saveResource("bot_discord.yml", false);
        }
        botDiscordConfig = YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration getBotDiscordConfig() {
        return botDiscordConfig;
    }

    public void loadOpAuthConfig() {
        opAuthFile = new File(getDataFolder(), "op_auth.yml");
        if (!opAuthFile.exists()) {
            saveResource("op_auth.yml", false);
        }
        opAuthConfig = YamlConfiguration.loadConfiguration(opAuthFile);
    }

    public FileConfiguration getOpAuthConfig() {
        if (opAuthConfig == null) loadOpAuthConfig();
        return opAuthConfig;
    }

    public void saveOpAuthConfig() {
        if (opAuthConfig == null || opAuthFile == null) return;
        try {
            opAuthConfig.save(opAuthFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =====================================================
    //                    GETTERS / HELPERS
    // =====================================================
    public MuteManager getMuteManager() { return muteManager; }
    public OpManager getOpManager() { return opManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public OpAuthListener getOpAuthListener() { return opAuthListener; }
    public AuthManager getAuthManager() { return authManager; }
    public BroadcastManager getBroadcastManager() { return broadcastManager; }
    public EmailService getEmailService() { return emailService; }

    // Helper để translate color code
    private String colorize(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }

    // =============== Helpers nội bộ cho AntiXray command ===============
    private AntiXrayListener ensureAntiXrayListener() {
        if (antiXrayListener == null) {
            antiXrayListener = new AntiXrayListener(this);
        }
        return antiXrayListener;
    }

    public void loadSpawnConfig() {
        spawnFile = new File(getDataFolder(), "spawn.yml");
        if (!spawnFile.exists()) saveResource("spawn.yml", false);
        spawnConfig = YamlConfiguration.loadConfiguration(spawnFile);
    }
    public void saveSpawnConfig() {
        try { spawnConfig.save(spawnFile); } catch (Exception e) { e.printStackTrace(); }
    }
    public FileConfiguration getSpawnConfig() { return spawnConfig; }

    public void loadLastLocConfig() {
        lastLocFile = new File(getDataFolder(), "lastlocations.yml");
        if (!lastLocFile.exists()) {
            try { lastLocFile.createNewFile(); } catch (Exception ignored) {}
        }
        lastLocConfig = YamlConfiguration.loadConfiguration(lastLocFile);
    }
    public void saveLastLocConfig() {
        try { lastLocConfig.save(lastLocFile); } catch (Exception e) { e.printStackTrace(); }
    }
    public FileConfiguration getLastLocConfig() { return lastLocConfig; }
}
