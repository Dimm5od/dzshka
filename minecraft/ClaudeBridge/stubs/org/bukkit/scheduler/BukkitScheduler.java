package org.bukkit.scheduler;
import org.bukkit.plugin.Plugin;
public interface BukkitScheduler {
    BukkitTask runTask(Plugin p, Runnable r);
    BukkitTask runTaskLater(Plugin p, Runnable r, long delay);
    BukkitTask runTaskAsynchronously(Plugin p, Runnable r);
}
