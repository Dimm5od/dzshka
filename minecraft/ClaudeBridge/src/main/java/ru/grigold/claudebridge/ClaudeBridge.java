package ru.grigold.claudebridge;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Пишешь в чат "@claude ..." -> плагин запускает "claude -p" на этом же компьютере,
 * Claude отвечает списком команд, плагин их выполняет. После ответа Claude закрывается.
 */
public final class ClaudeBridge extends JavaPlugin implements Listener, CommandExecutor {

    private static final String TAG = "§d[Claude] §r";

    private final AtomicBoolean busy = new AtomicBoolean(false);

    private String trigger;
    private Set<String> allowedPlayers;
    private Set<String> blockedCommands;
    private List<String> blockedWords;
    private String claudePath;
    private String model;
    private List<String> extraArgs;
    private int timeoutSeconds;
    private int maxCommands;
    private int commandsPerTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("claude").setExecutor(this);
        getLogger().info("ClaudeBridge включён. Разрешённые игроки: " + allowedPlayers);
    }

    private void loadSettings() {
        reloadConfig();
        trigger = getConfig().getString("trigger", "@claude").toLowerCase(Locale.ROOT);
        allowedPlayers = lowerSet(getConfig().getStringList("allowed-players"));
        blockedCommands = lowerSet(getConfig().getStringList("blocked-commands"));
        blockedWords = new ArrayList<>(lowerSet(getConfig().getStringList("blocked-words")));
        claudePath = getConfig().getString("claude-path", "claude");
        model = getConfig().getString("model", "");
        extraArgs = getConfig().getStringList("extra-args");
        timeoutSeconds = getConfig().getInt("timeout-seconds", 180);
        maxCommands = getConfig().getInt("max-commands", 1000);
        commandsPerTick = Math.max(1, getConfig().getInt("commands-per-tick", 50));
    }

    private static Set<String> lowerSet(List<String> list) {
        Set<String> set = new HashSet<>();
        if (list != null) {
            for (String s : list) set.add(s.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private boolean isAllowed(CommandSender sender) {
        return allowedPlayers.contains(sender.getName().toLowerCase(Locale.ROOT));
    }

    // ---------- Вход: чат и команда /claude ----------

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage().trim();
        if (!msg.toLowerCase(Locale.ROOT).startsWith(trigger)) return;
        Player player = event.getPlayer();
        if (!isAllowed(player)) return; // чужие сообщения просто идут в чат как обычно
        event.setCancelled(true);
        String request = msg.substring(trigger.length()).trim();
        // Позицию игрока безопасно читать только в основном потоке сервера
        getServer().getScheduler().runTask(this, () -> ask(player, request));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.isOp()) {
            loadSettings();
            sender.sendMessage(TAG + "Настройки перезагружены.");
            return true;
        }
        if (!(sender instanceof Player player) || !isAllowed(sender)) {
            sender.sendMessage(TAG + "§cТебе нельзя звать Claude.");
            return true;
        }
        ask(player, String.join(" ", args).trim());
        return true;
    }

    // ---------- Запрос к Claude ----------

    private void ask(Player player, String request) {
        if (request.isEmpty()) {
            player.sendMessage(TAG + "Напиши, что сделать. Например: §e" + trigger + " построй стеклянный купол");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            player.sendMessage(TAG + "§eПодожди, я ещё делаю прошлую просьбу.");
            return;
        }
        String prompt = buildPrompt(player, request);
        player.sendMessage(TAG + "§7Думаю...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String answer = runClaude(prompt);
                getServer().getScheduler().runTask(this, () -> handleAnswer(player, answer));
            } catch (Exception e) {
                getLogger().warning("Ошибка запуска Claude: " + e.getMessage());
                getServer().getScheduler().runTask(this, () -> {
                    busy.set(false);
                    if (player.isOnline()) player.sendMessage(TAG + "§cНе получилось: " + e.getMessage());
                });
            }
        });
    }

    private String buildPrompt(Player player, String request) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        return """
                Ты помощник-админ на сервере Minecraft Java 1.21 (ядро Paper).
                Игрок %s попросил: %s

                Где игрок: мир "%s", x=%d y=%d z=%d, смотрит на %s.
                Время в мире: %d тиков, гроза: %s.

                ПРАВИЛА ОТВЕТА (очень важно, ответ читает программа, а не человек):
                - Отвечай ТОЛЬКО строками, по одной на строку, без пояснений и без markdown.
                - Строка-команда: команда Minecraft БЕЗ слэша в начале. Например: fill ~-3 ~ ~-3 ~3 ~5 ~3 glass hollow
                - Каждая команда выполняется от имени игрока и с его позиции, так что ~ ~ ~ — это там, где он стоит.
                - Строка, начинающаяся с "msg:", — короткое сообщение игроку на русском. Например: msg: Готово!
                - Максимум %d команд. Для больших построек используй fill, а не тысячи setblock.
                - Нельзя: op, deop, stop, ban, kick, whitelist, командные блоки и всё, что ломает сервер.
                - Не используй инструменты и не читай файлы — просто напиши ответ.
                - Если просьба непонятная или опасная — ответь только строкой msg: с объяснением.
                """.formatted(
                player.getName(), request,
                world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), facing(loc.getYaw()),
                world.getTime(), world.hasStorm() ? "да" : "нет",
                maxCommands);
    }

    private static String facing(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y < 45 || y >= 315) return "юг (+z)";
        if (y < 135) return "запад (-x)";
        if (y < 225) return "север (-z)";
        return "восток (+x)";
    }

    /** Запускает "claude -p", отдаёт задание через stdin, ждёт ответ. После ответа процесс сам закрывается. */
    private String runClaude(String prompt) throws IOException, InterruptedException {
        File work = new File(getDataFolder(), "work");
        work.mkdirs();
        File out = new File(work, "answer.txt");
        File err = new File(work, "errors.txt");

        List<String> cmd = new ArrayList<>();
        String lower = claudePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            // На Windows .cmd-файлы (установка через npm) запускаются только через cmd
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(claudePath);
        cmd.add("-p");
        cmd.add("Follow the instructions given on stdin.");
        if (!model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (extraArgs != null) cmd.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(work)
                .redirectOutput(out)
                .redirectError(err);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("не нашёл программу Claude Code (" + claudePath
                    + "). Укажи полный путь в config.yml -> claude-path");
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Claude не ответил за " + timeoutSeconds + " сек.");
        }
        String answer = Files.readString(out.toPath(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            String error = Files.readString(err.toPath(), StandardCharsets.UTF_8).trim();
            if (error.isEmpty()) error = answer.trim();
            if (error.length() > 200) error = error.substring(0, 200) + "...";
            throw new IOException("Claude Code завершился с ошибкой: " + error);
        }
        return answer;
    }

    // ---------- Выполнение ответа ----------

    private void handleAnswer(Player player, String answer) {
        List<String> commands = new ArrayList<>();
        int rejected = 0;
        for (String raw : answer.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("```") || line.startsWith("#")) continue;
            if (line.toLowerCase(Locale.ROOT).startsWith("msg:")) {
                if (player.isOnline()) player.sendMessage(TAG + line.substring(4).trim());
                continue;
            }
            if (line.startsWith("/")) line = line.substring(1);
            if (isSafe(line)) {
                commands.add(line);
            } else {
                rejected++;
                getLogger().warning("Заблокирована команда от Claude: " + line);
            }
        }
        if (commands.size() > maxCommands) commands = commands.subList(0, maxCommands);
        if (rejected > 0 && player.isOnline()) {
            player.sendMessage(TAG + "§eЗаблокировано опасных команд: " + rejected);
        }
        if (commands.isEmpty()) {
            busy.set(false);
            return;
        }
        runBatch(player, commands, 0);
    }

    /** Выполняет команды пачками, чтобы большая постройка не подвесила сервер. */
    private void runBatch(Player player, List<String> commands, int from) {
        String name = player.getName();
        int to = Math.min(from + commandsPerTick, commands.size());
        for (int i = from; i < to; i++) {
            String command = "execute as " + name + " at " + name + " run " + commands.get(i);
            try {
                getServer().dispatchCommand(getServer().getConsoleSender(), command);
            } catch (Exception e) {
                getLogger().warning("Команда не выполнилась: " + command + " (" + e.getMessage() + ")");
            }
        }
        if (to < commands.size()) {
            getServer().getScheduler().runTaskLater(this, () -> runBatch(player, commands, to), 1L);
        } else {
            busy.set(false);
            if (player.isOnline()) player.sendMessage(TAG + "§aГотово, выполнено команд: " + commands.size());
        }
    }

    /** Проверяет и саму команду, и всё, что спрятано за "execute ... run". */
    private boolean isSafe(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String word : blockedWords) {
            if (lower.contains(word)) return false;
        }
        String[] tokens = lower.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (i == 0 || tokens[i - 1].equals("run")) {
                String name = tokens[i];
                int colon = name.indexOf(':');
                if (colon >= 0) name = name.substring(colon + 1); // minecraft:op -> op
                if (blockedCommands.contains(name)) return false;
            }
        }
        return true;
    }
}
