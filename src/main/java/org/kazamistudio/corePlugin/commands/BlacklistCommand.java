package org.kazamistudio.corePlugin.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.kazamistudio.corePlugin.manager.BlacklistManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BlacklistCommand implements CommandExecutor, TabCompleter {

    private final BlacklistManager blacklistManager;

    public BlacklistCommand(BlacklistManager blacklistManager) {
        this.blacklistManager = blacklistManager;

        // preload mặc định nếu DB trống
        if (blacklistManager.getAllCommands().isEmpty()) {
            blacklistManager.addCommand("op");
            blacklistManager.addCommand("minecraft:op");
            blacklistManager.addCommand("bukkit:op");
            blacklistManager.addCommand("pl");
            blacklistManager.addCommand("plugins");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền dùng lệnh này!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Sử dụng: /blacklistcmd <list|add|remove> [command]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(ChatColor.GREEN + "Các lệnh bị CẤM:");
                for (String cmd : blacklistManager.getAllCommands()) {
                    sender.sendMessage(" - " + cmd);
                }
            }
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Sử dụng: /blacklistcmd add <command>");
                    return true;
                }
                String cmdToAdd = args[1].toLowerCase();
                if (blacklistManager.addCommand(cmdToAdd)) {
                    sender.sendMessage(ChatColor.GREEN + "Đã thêm vào blacklist: " + cmdToAdd);
                } else {
                    sender.sendMessage(ChatColor.RED + "Lệnh đã tồn tại hoặc lỗi khi thêm!");
                }
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Sử dụng: /blacklistcmd remove <command>");
                    return true;
                }
                String cmdToRemove = args[1].toLowerCase();
                if (blacklistManager.removeCommand(cmdToRemove)) {
                    sender.sendMessage(ChatColor.GREEN + "Đã xóa khỏi blacklist: " + cmdToRemove);
                } else {
                    sender.sendMessage(ChatColor.RED + "Không tìm thấy trong blacklist!");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Lệnh không hợp lệ! Sử dụng: /blacklistcmd <list|add|remove>");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.add("list");
            suggestions.add("add");
            suggestions.add("remove");
            return filterSuggestions(suggestions, args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("remove")) {
                suggestions.addAll(blacklistManager.getAllCommands());
                return filterSuggestions(suggestions, args[1]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterSuggestions(List<String> options, String current) {
        String lower = current.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(lower)) {
                result.add(opt);
            }
        }
        return result;
    }
}
