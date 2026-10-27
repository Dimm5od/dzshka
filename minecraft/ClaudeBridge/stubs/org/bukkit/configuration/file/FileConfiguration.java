package org.bukkit.configuration.file;
import java.util.List;
public abstract class FileConfiguration {
    public String getString(String p, String d) { return d; }
    public List<String> getStringList(String p) { return null; }
    public int getInt(String p, int d) { return d; }
}
