package org.kazamistudio.corePlugin.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.kazamistudio.corePlugin.CorePlugin;

public class OpNotifierListener implements Listener {

    private final CorePlugin plugin;

    public OpNotifierListener(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerOp(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int onlinePlayers = Bukkit.getOnlinePlayers().size();
            double tps = getServerTps();
            long usedRam = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
            long maxRam = Runtime.getRuntime().maxMemory() / 1024 / 1024;

            player.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "Server Status" + ChatColor.GOLD + " =====");
            player.sendMessage(ChatColor.GREEN + "Player Online: " + ChatColor.WHITE + onlinePlayers);
            player.sendMessage(ChatColor.GREEN + "Server TPS: " + ChatColor.WHITE + String.format("%.2f", tps));
            player.sendMessage(ChatColor.GREEN + "RAM Usage: " + ChatColor.WHITE + usedRam + "MB / " + maxRam + "MB");
            player.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "Server Status" + ChatColor.GOLD + " =====");

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }, 60L);
    }

    private double getServerTps() {
        try {
            Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) minecraftServer.getClass().getField("recentTps").get(minecraftServer);
            return recentTps[0];
        } catch (Exception e) {
            return -1;
        }
    }
}

