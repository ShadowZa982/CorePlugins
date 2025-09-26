package org.kazamistudio.corePlugin.listener;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.kazamistudio.corePlugin.CorePlugin;
import org.kazamistudio.corePlugin.util.ColorUtil;

import java.util.HashMap;
import java.util.Map;

public class OpAuthListener implements Listener {

    private final CorePlugin plugin;
    private final Map<Player, Boolean> opLocked = new HashMap<>();
    private final Map<Player, Integer> failedAttempts = new HashMap<>();
    private final Map<Player, String> pendingPasswords = new HashMap<>();
    private final Map<Player, Integer> loginTimeoutTask = new HashMap<>();

    private final int TIMEOUT_SECONDS = 60;

    public OpAuthListener(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // ✅ Nếu chưa login thì không cho auth OP ngay
        if (plugin.getAuthManager() != null && !plugin.getAuthManager().isLoggedIn(player)) {
            return;
        }

        if (!player.isOp()) return;

        // Bắt đầu quy trình OP Auth
        startOpAuth(player);
    }

    // --- Tách phần logic khởi động auth OP ra hàm riêng ---
    public void startOpAuth(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String savedIp = plugin.getOpManager().getIp(player.getName());
            String currentIp = player.getAddress().getAddress().getHostAddress();

            if (savedIp != null && !savedIp.equals(currentIp)) {
                Bukkit.getBanList(BanList.Type.IP).addBan(currentIp,
                        "§cKhông đúng IP đăng nhập OP! Liên hệ admin nếu cần.", null, null);

                if (plugin.getDiscordManager() != null) {
                    plugin.getDiscordManager().sendOpIpMismatchEmbed(player.getName(), currentIp, savedIp);
                }
                logOpIpMismatch(player, currentIp, savedIp);

                player.kickPlayer("§cIP của bạn không được phép đăng nhập với quyền OP!");
                return;
            }

            opLocked.put(player, true);
            failedAttempts.put(player, 0);

            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, TIMEOUT_SECONDS * 20, 1, false, false, false));
            player.sendTitle(ChatColor.RED + "Xác thực OP!",
                    ChatColor.YELLOW + "Nhập /oppass <mật khẩu> để mở khóa",
                    10, 70, 10);

            // Countdown timeout
            int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
                int timeLeft = TIMEOUT_SECONDS;

                @Override
                public void run() {
                    if (!opLocked.getOrDefault(player, false)) {
                        Integer taskId = loginTimeoutTask.remove(player);
                        if (taskId != null) {
                            Bukkit.getScheduler().cancelTask(taskId);
                        }
                        return;
                    }

                    if (timeLeft <= 0) {
                        player.kickPlayer("§cHết thời gian đăng nhập! Vui lòng thử lại.");
                        Integer taskId = loginTimeoutTask.remove(player);
                        if (taskId != null) {
                            Bukkit.getScheduler().cancelTask(taskId);
                        }
                        return;
                    }

                    player.spigot().sendMessage(
                            ChatMessageType.ACTION_BAR,
                            new TextComponent(ChatColor.YELLOW + "Bạn còn " + timeLeft + " giây để nhập /oppass!")
                    );

                    timeLeft--;
                }
            }, 0L, 20L);
            loginTimeoutTask.put(player, taskId);
        });
    }



    // Chặn các lệnh khác ngoài /oppass
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Nếu đang bị lock OP
        if (opLocked.getOrDefault(player, false)) {
            String msg = event.getMessage().toLowerCase();

            // Chặn nếu không phải /oppass
            if (!msg.startsWith("/oppass")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Chỉ có thể sử dụng lệnh /oppass trong khi chờ xác thực OP!");
                return;
            }

            // ✅ Check thêm login
            if (plugin.getAuthManager() != null && !plugin.getAuthManager().isLoggedIn(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Bạn phải đăng nhập trước khi dùng lệnh /oppass!");
            }
        }
    }



    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (opLocked.getOrDefault(player, false) && !event.getFrom().equals(event.getTo())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && opLocked.getOrDefault(player, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager && opLocked.getOrDefault(damager, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (opLocked.getOrDefault(player, false)) event.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (opLocked.getOrDefault(player, false)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        opLocked.remove(player);
        failedAttempts.remove(player);
        pendingPasswords.remove(player);

        Integer taskId = loginTimeoutTask.remove(player);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

    }

    // --- Xác thực mật khẩu OP ---
    public boolean checkPassword(Player player, String password) {
        if (!player.isOp()) return false;

        // ✅ Nếu chưa login thì không cho dùng oppass
        if (plugin.getAuthManager() != null && !plugin.getAuthManager().isLoggedIn(player)) {
            player.sendMessage(ChatColor.RED + "Bạn phải đăng nhập trước khi sử dụng /oppass!");
            return false;
        }

        // Nếu không bị khóa thì coi như đã xác thực rồi
        if (!opLocked.getOrDefault(player, false)) return true;

        pendingPasswords.put(player, password);

        if (plugin.getDiscordManager() != null) {
            // Discord bật → gửi thông báo
            plugin.getDiscordManager().sendOpRequestEmbed(player.getName(), password);
        } else {
            // Discord tắt → thông báo qua console
            plugin.getLogger().info(
                    ColorUtil.translateColors("&2[OP Request] &6" + player.getName() + " &2yêu cầu quyền truy cập. Mật khẩu: " + password)
            );

            player.sendMessage(ChatColor.YELLOW + "Admin sẽ duyệt qua console.");
        }

        player.sendTitle(ChatColor.YELLOW + "Chờ duyệt OP",
                ChatColor.GRAY + "Vui lòng đợi admin xác nhận",
                10, 70, 10);
        player.sendMessage(ChatColor.YELLOW + "Đang chờ admin duyệt.");
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);

        return false;
    }

    public void acceptPlayer(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String pass = pendingPasswords.remove(player);
            if (pass == null) return;

            player.removePotionEffect(PotionEffectType.BLINDNESS);

            opLocked.put(player, false);
            failedAttempts.remove(player);

            player.sendTitle(ChatColor.GREEN + "Đã được xác thực OP!",
                    ChatColor.WHITE + "Bạn đã xác minh thành công",
                    10, 50, 10);
            player.sendMessage(ChatColor.GREEN + "Admin đã xác nhận quyền OP của bạn!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

            Integer taskId = loginTimeoutTask.remove(player);
            if (taskId != null) {
                Bukkit.getScheduler().cancelTask(taskId);
            }

            // Ghi log OP attempt
            logOpAttempt(player, true, pass);

            // Thông báo qua Discord nếu bật
            if (plugin.getDiscordManager() != null) {
                plugin.getDiscordManager().sendOpApprovedEmbed(player.getName());
            } else {
                plugin.getLogger().info(
                        ColorUtil.translateColors("&2[OP Approved] &6" + player.getName() + " &2đã được admin xác nhận OP.")
                );
            }
        });
    }



    // Log OP attempt
    public void logOpAttempt(Player player, boolean success, String password) {
        FileConfiguration config = plugin.getOpAuthConfig();
        if (config == null) return;
        String path = player.getName() + "." + System.currentTimeMillis();
        config.set(path + ".success", success);
        config.set(path + ".ip", player.getAddress().getAddress().getHostAddress());
        config.set(path + ".password", success ? "*****" : password);
        plugin.saveOpAuthConfig();
    }

    public void logOpIpMismatch(Player player, String actualIp, String expectedIp) {
        FileConfiguration config = plugin.getOpAuthConfig();
        if (config == null) return;

        String path = player.getName() + "." + System.currentTimeMillis();
        config.set(path + ".success", false);
        config.set(path + ".reason", "IP không đúng");
        config.set(path + ".actual_ip", actualIp);
        config.set(path + ".expected_ip", expectedIp);
        config.set(path + ".password", "N/A");
        plugin.saveOpAuthConfig();
    }


    public String getPendingPassword(Player player) {
        return pendingPasswords.get(player);
    }
}
