package org.kazamistudio.corePlugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.kazamistudio.corePlugin.CorePlugin;

public class ReportCommand implements CommandExecutor {

    private final CorePlugin plugin;

    public ReportCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Chỉ người chơi mới có thể dùng lệnh này!");
            return true;
        }

        Player reporter = (Player) sender;

        if (args.length < 2) {
            reporter.sendMessage(ChatColor.YELLOW + "❌ Cú pháp: /report <người chơi> <lý do>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            reporter.sendMessage(ChatColor.RED + "❌ Người chơi '" + targetName + "' không online!");
            return true;
        }

        // Ghép lý do
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            reasonBuilder.append(args[i]).append(" ");
        }
        String reason = reasonBuilder.toString().trim();

        // Gửi confirm cho người báo cáo
        reporter.sendMessage(ChatColor.GREEN + "✅ Bạn đã báo cáo " + target.getName() + " vì: " + reason);

        // Gửi report sang Discord
        plugin.getDiscordManager().sendReportEmbed(reporter.getName(), target.getName(), reason);

        return true;
    }
}