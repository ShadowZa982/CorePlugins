package org.kazamistudio.corePlugin.listener;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.kazamistudio.corePlugin.CorePlugin;

public class OpProtectionListener implements Listener {

    private final CorePlugin plugin;

    public OpProtectionListener(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand().toLowerCase();
        CommandSender sender = event.getSender();

        if (cmd.startsWith("op ")) {
            String[] args = cmd.split(" ");
            if (args.length >= 2) {
                String target = args[1];
                if (!plugin.getOpManager().isLegitOp(target)) {
                    String ip = null;
                    if (Bukkit.getPlayerExact(target) != null) {
                        ip = Bukkit.getPlayerExact(target).getAddress().getAddress().getHostAddress();
                    }

                    if (ip != null) {
                        Bukkit.getBanList(BanList.Type.IP).addBan(ip, "Cố ý OP trái phép", null, "Console");
                        Bukkit.getConsoleSender().sendMessage("§cIP " + ip + " đã bị ban vì cố ý OP trái phép!");

                        // Gửi thông báo Discord
                        if (plugin.getDiscordManager() != null) {
                            plugin.getDiscordManager().sendBanIpEmbed(target, ip);
                        }

                    }

                    event.setCancelled(true);
                }
            }
        }
    }
}