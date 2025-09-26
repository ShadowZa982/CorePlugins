package org.kazamistudio.corePlugin.manager;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public WhitelistManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/whitelist.db");

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS whitelist_commands (command TEXT PRIMARY KEY)");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Set<String> getAllCommands() {
        Set<String> set = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT command FROM whitelist_commands")) {
            while (rs.next()) {
                set.add(rs.getString("command").toLowerCase());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return set;
    }

    public boolean addCommand(String command) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO whitelist_commands (command) VALUES (?)")) {
            ps.setString(1, command.toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean removeCommand(String command) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM whitelist_commands WHERE command = ?")) {
            ps.setString(1, command.toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
