package org.kazamistudio.corePlugin.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.kazamistudio.corePlugin.CorePlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.*;

public class PacketIpCheck implements Listener {

    private final CorePlugin plugin;
    private final ProtocolManager protocolManager;
    private final boolean ipForwardingEnabled;

    private final File uuidPlayersFile;
    private final File uuidLogFile;

    public PacketIpCheck(CorePlugin plugin, boolean ipForwardingEnabled) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.ipForwardingEnabled = ipForwardingEnabled;

        File uuidFolder = new File(plugin.getDataFolder(), "uuid");
        if (!uuidFolder.exists()) uuidFolder.mkdirs();

        this.uuidPlayersFile = new File(uuidFolder, "uuid_players.yml");
        this.uuidLogFile = new File(uuidFolder, "uuid_log.yml");

        try {
            if (!uuidPlayersFile.exists()) {
                uuidPlayersFile.createNewFile();
                YamlConfiguration yml = new YamlConfiguration();
                yml.set("players", new HashMap<String, Object>());
                yml.save(uuidPlayersFile);
            }
            if (!uuidLogFile.exists()) {
                uuidLogFile.createNewFile();
                YamlConfiguration yml = new YamlConfiguration();
                yml.set("logs", new ArrayList<String>());
                yml.save(uuidLogFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void register() {
        // Đăng ký packet listener (chặn spoof)
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Handshake.Client.SET_PROTOCOL) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    PacketContainer packet = event.getPacket();

                    String socketIp = ((InetSocketAddress) event.getPlayer().getAddress())
                            .getAddress().getHostAddress();
                    String hostname = packet.getStrings().read(0);

                    if (hostname.contains("\0")) {
                        String[] parts = hostname.split("\0");
                        if (parts.length >= 3) {
                            String forwardedIp = parts[1].trim();
                            String forwardedUuid = parts[2].trim();

                            if (ipForwardingEnabled) {
                                if (!isValidIp(forwardedIp)) {
                                    block(event, "Forwarded IP không hợp lệ!", socketIp, forwardedIp, null);
                                    return;
                                }
                                if (!isValidUuid(forwardedUuid)) {
                                    block(event, "Forwarded UUID không hợp lệ!", socketIp, forwardedIp, forwardedUuid);
                                    return;
                                }
                            } else {
                                block(event, "Forwarding không được bật nhưng client gửi forwarded data!",
                                        socketIp, forwardedIp, forwardedUuid);
                            }
                        }
                    } else {
                        if (ipForwardingEnabled) {
                            block(event, "Bật forwarding nhưng không có forwarded IP!", socketIp, "N/A", null);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // Đăng ký Bukkit event (lưu UUID + log)
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        String uuid = event.getUniqueId().toString();
        String ip = event.getAddress().getHostAddress();

        saveAndCheckPlayerUuid(playerName, uuid, ip);
    }

    private void block(PacketEvent event, String reason, String socketIp, String spoofedIp, String spoofedUuid) {
        event.setCancelled(true);
        event.getPlayer().kickPlayer("§cIP/UUID spoof detected!\n§7" + reason);
        Bukkit.getLogger().warning("[AntiSpoof] " + event.getPlayer().getName()
                + " bị chặn. Lý do: " + reason
                + " | Socket IP: " + socketIp
                + " | SpoofedIP: " + spoofedIp
                + " | SpoofedUUID: " + (spoofedUuid != null ? spoofedUuid : "N/A"));

        if (plugin.getConfig().getBoolean("discord.enabled", false)
                && plugin.getDiscordManager() != null) {
            plugin.getDiscordManager().sendSpoofAlertEmbed(
                    event.getPlayer().getName(),
                    socketIp,
                    spoofedIp,
                    (spoofedUuid != null ? spoofedUuid : "N/A"),
                    reason
            );
        }

        addLog("BLOCK", event.getPlayer().getName(),
                (spoofedUuid != null ? spoofedUuid : "N/A"),
                spoofedIp, reason);
    }

    private boolean isValidIp(String ip) {
        return ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private boolean isValidUuid(String uuid) {
        try {
            UUID.fromString(uuid.replaceAll(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                    "$1-$2-$3-$4-$5"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveAndCheckPlayerUuid(String playerName, String uuid, String ip) {
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(uuidPlayersFile);
            String path = "players." + playerName;
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            if (!yml.contains(path)) {
                // lần đầu join
                yml.set(path + ".uuid", uuid);
                yml.set(path + ".firstJoin", now);
                yml.set(path + ".lastJoin", now);
                yml.set(path + ".ip", ip);
                yml.save(uuidPlayersFile);

                addLog("NEW", playerName, uuid, ip, "First join");
                return;
            }

            String storedUuid = yml.getString(path + ".uuid");
            if (storedUuid != null && !storedUuid.equalsIgnoreCase(uuid)) {
                addLog("UUID_MISMATCH", playerName, uuid, ip, "UUID không khớp (expected " + storedUuid + ")");
                return;
            }

            yml.set(path + ".lastJoin", now);
            yml.set(path + ".ip", ip);
            yml.save(uuidPlayersFile);

            addLog("JOIN", playerName, uuid, ip, "Đăng nhập lại");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addLog(String type, String player, String uuid, String ip, String detail) {
        try {
            YamlConfiguration logYml = YamlConfiguration.loadConfiguration(uuidLogFile);
            List<String> logs = logYml.getStringList("logs");
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            logs.add("[" + time + "] [" + type + "] Player=" + player
                    + " | UUID=" + uuid
                    + " | IP=" + ip
                    + " | Detail=" + detail);

            logYml.set("logs", logs);
            logYml.save(uuidLogFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
