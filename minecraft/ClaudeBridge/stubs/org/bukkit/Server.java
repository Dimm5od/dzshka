package org.bukkit;
public interface Server {
    org.bukkit.plugin.PluginManager getPluginManager();
    org.bukkit.scheduler.BukkitScheduler getScheduler();
    org.bukkit.command.ConsoleCommandSender getConsoleSender();
    boolean dispatchCommand(org.bukkit.command.CommandSender s, String cmd);
}
