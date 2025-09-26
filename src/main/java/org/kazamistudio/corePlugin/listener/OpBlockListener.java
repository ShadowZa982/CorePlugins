package org.kazamistudio.corePlugin.listener;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.kazamistudio.corePlugin.CorePlugin;

public class OpBlockListener implements Listener {

    private final CorePlugin plugin;

    public OpBlockListener(CorePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isOpCommand(String cmd) {
        String lower = cmd.trim().toLowerCase();
        if (lower.startsWith("/")) lower = lower.substring(1);

        return lower.startsWith("op ") || lower.startsWith("minecraft:op ");
    }

    private void banAndKick(Player p, String reason) {
        String ip = p.getAddress().getAddress().getHostAddress();
        plugin.getOpManager().banIp(ip, p.getName(), reason);
        p.kickPlayer(
                "\n§c§lBẠN ĐÃ BỊ CẤM TRUY CẬP\n" +
                        "§7Lý do: §e" + reason + "\n" +
                        "§7Thời hạn: §cVĩnh viễn\n" +
                        "§7Người ban: §aHệ thống bảo mật\n" +
                        "\n§fNếu bạn nghĩ đây là nhầm lẫn, hãy liên hệ: §bdiscord.gg/ViaEcoSmp"
        );
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (isOpCommand(event.getMessage())) {
            Player p = event.getPlayer();
            banAndKick(p, "Cố ý sử dụng lệnh OP trái phép");
            Bukkit.getConsoleSender().sendMessage("§c" + p.getName() + " đã bị ban IP vì dùng /op!");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        if (plugin.getOpManager().isIpBanned(ip)) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    "\n§c§lBẠN ĐÃ BỊ CẤM TRUY CẬP\n" +
                            "§7Lý do: §e" + plugin.getOpManager().getBanReason(ip) + "\n" +
                            "§7Thời hạn: §cVĩnh viễn\n" +
                            "§7Người ban: §aHệ thống bảo mật\n" +
                            "\n§fNếu bạn nghĩ đây là nhầm lẫn, hãy liên hệ: §bdiscord.gg/ViaEcoSmp"
            );
        }
    }

    @EventHandler
    public void onConsoleCommand(ServerCommandEvent event) {
        if (isOpCommand(event.getCommand())) {
            event.setCancelled(true);
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            console.sendMessage("§cLệnh /op đã bị vô hiệu hóa!");

            String[] args = event.getCommand().split(" ");
            if (args.length >= 2) {
                String targetName = args[1];
                Player target = Bukkit.getPlayerExact(targetName);
                if (target != null) {
                    banAndKick(target, "Cố ý sử dụng lệnh OP trái phép (từ console)");
                    if (target.isOp()) {
                        target.setOp(false);
                    }
                    console.sendMessage("§c" + target.getName() + " đã bị ban IP vì bị OP từ console!");
                }
            }
        }
    }
}
