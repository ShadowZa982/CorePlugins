package org.kazamistudio.corePlugin.manager;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.kazamistudio.corePlugin.CorePlugin;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;

public class OpManager {
    private final CorePlugin plugin;
    private final File file;
    private FileConfiguration data;

    public OpManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ops.yml");

        if (!file.exists()) {
            try {
                file.createNewFile();
                FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                cfg.set("password", "");
                cfg.set("ops", new HashMap<>());
                cfg.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        this.data = YamlConfiguration.loadConfiguration(file);
    }

    // Lấy IP đã lưu của OP
    public String getIp(String playerName) {
        return data.getString("ops." + playerName + ".ip");
    }

    // Lưu IP khi OP được thêm hoặc đổi pass
    public void addOp(Player player, String ip) {
        data.set("ops." + player.getName() + ".ip", ip);
        save();
    }


    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void reload() {
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public void setPassword(String rawPass) {
        data.set("password", hash(rawPass));
        save();
    }

    public boolean checkPassword(String pass) {
        String stored = data.getString("password", "");
        return stored.equals(hash(pass));
    }

    public void addOp(OfflinePlayer player, String ip) {
        data.set("ops." + player.getName() + ".uuid", player.getUniqueId().toString());
        data.set("ops." + player.getName() + ".ip", ip);
        save();
    }

    public boolean isLegitOp(String name) {
        return data.contains("ops." + name);
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public void banIp(String ip, String name, String reason) {
        String ipKey = ip.replace(".", "_");
        data.set("banned." + ipKey + ".name", name);
        data.set("banned." + ipKey + ".reason", reason);
        save();
    }

    public boolean isIpBanned(String ip) {
        String ipKey = ip.replace(".", "_");
        return data.contains("banned." + ipKey);
    }

    public String getBanReason(String ip) {
        String ipKey = ip.replace(".", "_");
        return data.getString("banned." + ipKey + ".reason", "Không rõ");
    }

    public FileConfiguration getData() {
        return data;
    }


}