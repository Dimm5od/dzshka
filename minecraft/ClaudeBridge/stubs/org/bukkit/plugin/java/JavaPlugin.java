package org.bukkit.plugin.java;
import java.io.File;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
    public void onEnable() {}
    public void onDisable() {}
    public FileConfiguration getConfig() { return null; }
    public void saveDefaultConfig() {}
    public void reloadConfig() {}
    public Logger getLogger() { return null; }
    public File getDataFolder() { return null; }
    public Server getServer() { return null; }
    public PluginCommand getCommand(String name) { return null; }
}
