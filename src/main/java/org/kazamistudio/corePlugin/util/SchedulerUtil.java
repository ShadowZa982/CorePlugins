package org.kazamistudio.corePlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerUtil {
    private static final boolean folia;

    static {
        boolean foliaDetected;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            foliaDetected = true;
        } catch (ClassNotFoundException e) {
            foliaDetected = false;
        }
        folia = foliaDetected;
    }

    public static void runAsync(CorePlugin plugin, Runnable task) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static void runRegion(JavaPlugin plugin, Player player, Runnable task) {
        if (folia) {
            Bukkit.getRegionScheduler().run(plugin, player.getLocation(), scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}

