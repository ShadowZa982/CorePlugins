package org.kazamistudio.corePlugin.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.kazamistudio.corePlugin.listener.OpAuthListener;
import org.kazamistudio.corePlugin.manager.OpManager;


public class OpPassCommand implements CommandExecutor {
    private final OpAuthListener opAuthListener;
    private final OpManager opManager;

    public OpPassCommand(OpAuthListener opAuthListener, OpManager opManager) {
        this.opAuthListener = opAuthListener;
        this.opManager = opManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
                boolean correct = opManager.checkPassword(args[1]);
                sender.sendMessage(correct ? "✔ Password đúng!" : "✖ Password sai!");
            } else {
                sender.sendMessage("§cLệnh chỉ dành cho OP hoặc console check: /oppass check <pass>");
            }
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Bạn không phải OP!");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Sử dụng: /oppass <mật khẩu>");
            return true;
        }

        String pass = args[0];
        opAuthListener.checkPassword(player, pass);
        return true;
    }
}