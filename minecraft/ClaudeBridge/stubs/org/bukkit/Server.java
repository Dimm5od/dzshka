package org.bukkit;
public interface Server {
    org.bukkit.plugin.PluginManager getPluginManager();
    org.bukkit.scheduler.BukkitScheduler getScheduler();
    org.bukkit.command.ConsoleCommandSender getConsoleSender();
    org.bukkit.command.CommandSender createCommandSender(java.util.function.Consumer<? super net.kyori.adventure.text.Component> feedback);
    org.bukkit.entity.Player getPlayerExact(String name);
    boolean dispatchCommand(org.bukkit.command.CommandSender s, String cmd);
}
