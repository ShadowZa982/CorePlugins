package org.kazamistudio.corePlugin.manager;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.kazamistudio.corePlugin.CorePlugin;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.bukkit.Bukkit.getServer;

public class DiscordManager {

    private GatewayDiscordClient client;
    private String channelId;
    private String adminChannelId;
    private String logId;
    private String voteChannelId;
    private String restartChannelId;
    private final CorePlugin plugin;

    private FileConfiguration botConfig;
    private File configFile;

    private static final Logger logger = Logger.getLogger(DiscordManager.class.getName());

    public DiscordManager(CorePlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void startBot(String token, String channelId, String adminChannelId, String logId, String voteChannelId, String restartChannelId) {
        this.channelId = channelId;
        this.adminChannelId = adminChannelId;
        this.logId = logId;
        this.voteChannelId = voteChannelId;
        this.restartChannelId = restartChannelId;

        try {
            client = DiscordClient.create(token).login().block();
            logDiscord(true);
            registerButtonListener();
        } catch (Exception e) {
            e.printStackTrace();
            logDiscord(false);
        }
    }

    private void logDiscord(boolean enable) {
        if (enable) {
            logWithColor("&7[&a✔&7] &7[&l&1Discord&7] &fBot đã kết nối thành công!");
        } else {
            logWithColor("&7[&c✘&7] &7[&l&1Discord&7] &fKhông thể kết nối bot!");
        }
    }

    private void logWithColor(String msg) {
        getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }


    private Optional<MessageChannel> getChannelById(String id) {
        if (id == null || id.isEmpty()) {
            logger.warning("⚠️ Channel ID rỗng hoặc null!");
            return Optional.empty();
        }

        try {
            return client.getChannelById(Snowflake.of(id))
                    .ofType(MessageChannel.class)
                    .blockOptional();
        } catch (Exception e) {
            logger.warning("❌ Không thể lấy channel với ID: " + id);
            return Optional.empty();
        }
    }


    private EmbedCreateSpec buildEmbed(String title, String desc, String colorHex, String thumbnail, String image, String footer) {
        EmbedCreateSpec.Builder builder = EmbedCreateSpec.builder()
                .title(title)
                .description(desc)
                .color(parseColor(colorHex));

        if (thumbnail != null && !thumbnail.isEmpty()) builder.thumbnail(thumbnail);
        if (image != null && !image.isEmpty()) builder.image(image);
        if (footer != null && !footer.isEmpty()) builder.footer(footer, null);

        return builder.build();
    }

    private discord4j.rest.util.Color parseColor(String hex) {
        return discord4j.rest.util.Color.of(Integer.parseInt(hex.replace("#", ""), 16));
    }

    // ------------------- Ban IP -------------------
    public void sendBanIpEmbed(String player, String ip) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("ban-ip-embed.enabled")) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("ban-ip-embed.title", "Ban IP"),
                config.getString("ban-ip-embed.description", "")
                        .replace("%player%", player)
                        .replace("%ip%", ip),
                config.getString("ban-ip-embed.color", "#FF0000"),
                config.getString("ban-ip-embed.thumbnail"),
                config.getString("ban-ip-embed.image"),
                config.getString("ban-ip-embed.footer", "")
        );

        getChannelById(channelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- OP Request -------------------
    public void sendOpRequestEmbed(String playerName, String password) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("op-request-embed.enabled")) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("op-request-embed.title", "Yêu cầu phê duyệt"),
                config.getString("op-request-embed.description", "%player% yêu cầu phê duyệt")
                        .replace("%player%", playerName),
                config.getString("op-request-embed.color", "#FFA500"),
                null, null, null
        );

        getChannelById(adminChannelId).ifPresent(ch ->
                ch.createMessage(MessageCreateSpec.builder()
                        .addEmbed(embed)
                        .addComponent(ActionRow.of(Button.success("verify_op:" + playerName, "Phê duyệt")))
                        .build()
                ).block()
        );
    }

    // ------------------- Server Status -------------------
    public void sendServerStatusEmbed(boolean startup) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("server-status.enabled")) return;

        String prefix = startup ? "startup" : "shutdown";
        EmbedCreateSpec embed = buildEmbed(
                config.getString("server-status." + prefix + ".title"),
                config.getString("server-status." + prefix + ".description"),
                config.getString("server-status." + prefix + ".color", "#FFFFFF"),
                config.getString("server-status." + prefix + ".thumbnail"),
                null,
                config.getString("server-status." + prefix + ".footer", "")
        );

        getChannelById(channelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- Player Join/Quit -------------------
    public void sendPlayerStatusEmbed(String playerName, boolean joined) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("player-status.enabled")) return;

        String prefix = joined ? "join" : "quit";
        EmbedCreateSpec embed = buildEmbed(
                config.getString("player-status." + prefix + ".title"),
                playerName,
                config.getString("player-status." + prefix + ".color", "#FFFFFF"),
                config.getString("player-status." + prefix + ".thumbnail"),
                null,
                config.getString("player-status." + prefix + ".footer", "")
        );

        getChannelById(channelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- Button Listener -------------------
    private void registerButtonListener() {
        client.on(ButtonInteractionEvent.class).subscribe(event -> {
            if (!event.getCustomId().startsWith("verify_op:")) return;
            String targetName = event.getCustomId().split(":")[1];

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayerExact(targetName);
                if (target != null) {
                    plugin.getOpAuthListener().acceptPlayer(target); // an toàn vì đang ở main thread
                    event.reply("✅ Đã phê duyệt tài khoản cho " + targetName).withEphemeral(true).block();
                } else {
                    event.reply("❌ Người chơi không online!").withEphemeral(true).block();
                }
            });
        });
    }


    // ------------------- OP IP Mismatch -------------------
    public void sendOpIpMismatchEmbed(String playerName, String actualIp, String expectedIp) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("op-ip-mismatch-embed.enabled")) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("op-ip-mismatch-embed.title", "⚠ OP IP Mismatch"),
                config.getString("op-ip-mismatch-embed.description",
                                "%player% đang cố đăng nhập với IP không đúng!")
                        .replace("%player%", playerName)
                        .replace("%actual_ip%", actualIp)
                        .replace("%expected_ip%", expectedIp),
                config.getString("op-ip-mismatch-embed.color", "#FF0000"),
                null, null,
                config.getString("op-ip-mismatch-embed.footer", "Hệ thống bảo mật OP")
        );

        getChannelById(adminChannelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // -------------------- VOTE SERVER ------------------
    public void sendVoteReminderEmbed(String message, List<String> links) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("vote-reminder-embed.enabled", true)) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("vote-reminder-embed.title", "Vote Server"),
                config.getString("vote-reminder-embed.description", message),
                config.getString("vote-reminder-embed.color", "#00FF00"),
                null, null, null
        );

        // ==== Build buttons ====
        List<Button> buttons = new ArrayList<>();
        int index = 1;
        for (String link : links) {
            buttons.add(Button.link(link, "Vote #" + index++));
        }

        // ==== Build role mentions từ config ====
        List<String> roleIds = config.getStringList("vote-reminder-embed.roles");
        String roleMentions = roleIds.stream()
                .map(id -> "<@&" + id + ">")
                .reduce((a, b) -> a + " " + b)
                .orElse("");

        // ==== Gửi message ====
        getChannelById(voteChannelId).ifPresent(ch ->
                ch.createMessage(MessageCreateSpec.builder()
                        .content(roleMentions) // ping roles từ config
                        .addEmbed(embed)
                        .addComponent(ActionRow.of(buttons))
                        .build()
                ).block()
        );
    }

    // ------------------- RELOAD SERVER -----------------
    public void sendRestartEmbed(String title, String desc, String colorHex) {
        EmbedCreateSpec embed = buildEmbed(title, desc, colorHex, null, null, null);

        getChannelById(restartChannelId).ifPresent(ch ->
                ch.createMessage(MessageCreateSpec.builder()
                        .addEmbed(embed)
                        .build()
                ).block()
        );
    }

    // ------------------- BROADCAST -------------------
    public void sendEmbedWithRole(String title, String description, String colorHex, String roleId) {
        try {
            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .title(title)
                    .description(description)
                    .color(parseColor(colorHex))
                    .timestamp(Instant.now())
                    .build();

            // Lấy channel từ config (hoặc dùng channelId mặc định)
            getChannelById(channelId).ifPresent(ch -> {
                String mention = roleId != null && !roleId.isEmpty() ? "<@&" + roleId + ">" : "";
                ch.createMessage(MessageCreateSpec.builder()
                        .content(mention) // Ping role
                        .addEmbed(embed)
                        .build()
                ).block();
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[Discord] Lỗi khi gửi embed broadcast: " + e.getMessage());
        }
    }

    // ------------------- OP Approved -------------------
    public void sendOpApprovedEmbed(String playerName) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("op-approved-embed.enabled")) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("op-approved-embed.title", "OP Approved"),
                config.getString("op-approved-embed.description", "%player% đã được phê duyệt!")
                        .replace("%player%", playerName),
                config.getString("op-approved-embed.color", "#00FF00"),
                null, null, null
        );

        getChannelById(adminChannelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- Spoof Alert -------------------
    public void sendSpoofAlertEmbed(String playerName, String socketIp, String spoofedIp, String spoofedUuid, String reason) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("spoof-alert-embed.enabled")) return;

        String targetChannelId = config.getString("spoof-alert-embed.channel-id");
        if (targetChannelId == null || targetChannelId.isEmpty()) {
            logWithColor("&c[Discord] Không tìm thấy channel-id cho spoof-alert-embed!");
            return;
        }

        EmbedCreateSpec embed = buildEmbed(
                config.getString("spoof-alert-embed.title", "🚨 Cảnh báo IP/UUID Spoof"),
                config.getString("spoof-alert-embed.description",
                                "Người chơi **%player%** bị chặn!\n\n" +
                                        "**Lý do:** %reason%\n" +
                                        "**Socket IP:** %socket_ip%\n" +
                                        "**Spoofed IP:** %spoofed_ip%\n" +
                                        "**Spoofed UUID:** %spoofed_uuid%")
                        .replace("%player%", playerName != null ? playerName : "Unknown")
                        .replace("%reason%", reason)
                        .replace("%socket_ip%", socketIp)
                        .replace("%spoofed_ip%", spoofedIp != null ? spoofedIp : "N/A")
                        .replace("%spoofed_uuid%", spoofedUuid != null ? spoofedUuid : "N/A"),
                config.getString("spoof-alert-embed.color", "#FF0000"),
                null, null,
                config.getString("spoof-alert-embed.footer", "Hệ thống bảo mật mạng")
        );

        getChannelById(logId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- REPORT PLAYER -------------------
    public void sendReportEmbed(String reporter, String target, String reason) {
        FileConfiguration config = plugin.getBotDiscordConfig();
        if (!config.getBoolean("report-embed.enabled", true)) return;

        EmbedCreateSpec embed = buildEmbed(
                config.getString("report-embed.title", "🚨 Báo cáo người chơi"),
                config.getString("report-embed.description",
                                "**Người báo cáo:** %reporter%\n" +
                                        "**Người bị báo cáo:** %target%\n" +
                                        "**Lý do:** %reason%")
                        .replace("%reporter%", reporter)
                        .replace("%target%", target)
                        .replace("%reason%", reason),
                config.getString("report-embed.color", "#FF0000"),
                null, null,
                config.getString("report-embed.footer", "Hệ thống Report")
        );

        getChannelById(adminChannelId).ifPresent(ch -> ch.createMessage(embed).block());
    }

    // ------------------- Shutdown -------------------
    public void shutdown() {
        if (client != null) {
            client.logout().block();
        }
    }

    private void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "bot_discord.yml");
        if (!configFile.exists()) {
            plugin.saveResource("bot_discord.yml", false);
        }
        botConfig = YamlConfiguration.loadConfiguration(configFile);
    }

    public void reloadConfig() {
        botConfig = YamlConfiguration.loadConfiguration(configFile);
    }

    public FileConfiguration getBotDiscordConfig() {
        return botConfig;
    }
}
