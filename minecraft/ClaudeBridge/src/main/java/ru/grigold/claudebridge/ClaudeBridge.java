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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Пишешь в чат "клауд ..." -> плагин запускает "claude -p" на этом же компьютере,
 * Claude отвечает списком команд, плагин их выполняет. После ответа Claude закрывается.
 * Если в сообщении есть секретный код — фильтр команд отключается.
 */
public final class ClaudeBridge extends JavaPlugin implements Listener, CommandExecutor {

    private static final String TAG = "§d[Claude] §r";

    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** Игроки, от которых ждём ввод секретного кода (ники в нижнем регистре). */
    private final Set<String> awaitingCode = ConcurrentHashMap.newKeySet();

    private SecretCode secret;
    private List<String> names;
    private Set<String> allowedPlayers;
    private Set<String> blockedCommands;
    private List<String> blockedWords;
    private String claudePath;
    private String model;
    private List<String> extraArgs;
    private int timeoutSeconds;
    private int maxCommands;
    private int commandsPerTick;
    private boolean hideOutput;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        secret = new SecretCode(new File(getDataFolder(), "secret.dat"));
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("claude").setExecutor(this);
        getLogger().info("ClaudeBridge включён.");
        // Если плагин включили, когда игрок уже на сервере — сразу просим код
        for (String name : allowedPlayers) {
            Player player = getServer().getPlayerExact(name);
            if (player != null) askForCode(player);
        }
    }

    private void loadSettings() {
        reloadConfig();
        names = new ArrayList<>(lowerSet(getConfig().getStringList("names")));
        // Длинные имена проверяем первыми, чтобы "клауде" не съелось как "клауд"
        names.sort((a, b) -> b.length() - a.length());
        allowedPlayers = lowerSet(getConfig().getStringList("allowed-players"));
        blockedCommands = lowerSet(getConfig().getStringList("blocked-commands"));
        blockedWords = new ArrayList<>(lowerSet(getConfig().getStringList("blocked-words")));
        claudePath = getConfig().getString("claude-path", "claude");
        model = getConfig().getString("model", "");
        extraArgs = getConfig().getStringList("extra-args");
        timeoutSeconds = getConfig().getInt("timeout-seconds", 180);
        maxCommands = getConfig().getInt("max-commands", 1000);
        commandsPerTick = Math.max(1, getConfig().getInt("commands-per-tick", 50));
        hideOutput = getConfig().getBoolean("hide-command-output", true);
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

    // ---------- Секретный код ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isAllowed(player) || secret.isSet()) return;
        // Ждём 2 секунды, чтобы сообщение не потерялось среди приветствий
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) askForCode(player);
        }, 40L);
    }

    private void askForCode(Player player) {
        if (!isAllowed(player) || secret.isSet()) return;
        awaitingCode.add(player.getName().toLowerCase(Locale.ROOT));
        player.sendMessage(TAG + "§eПридумай секретный код и напиши его в чат.");
        player.sendMessage(TAG + "§7Одно слово, минимум 4 символа. Его никто не увидит,");
        player.sendMessage(TAG + "§7а на сервере он хранится только в зашифрованном виде.");
        player.sendMessage(TAG + "§7Потом пиши код в сообщении Claude, чтобы он мог выполнять любые приказы.");
    }

    private void saveCode(Player player, String code) {
        if (code.isEmpty() || code.contains(" ") || code.length() < 4) {
            player.sendMessage(TAG + "§cКод должен быть одним словом, минимум 4 символа. Попробуй ещё раз.");
            return;
        }
        try {
            secret.set(code);
            awaitingCode.clear();
            player.sendMessage(TAG + "§aКод сохранён! Пример: §fклауд " + "*".repeat(code.length()) + " сделай что угодно");
            getLogger().info("Секретный код установлен.");
        } catch (IOException e) {
            player.sendMessage(TAG + "§cНе удалось сохранить код: " + e.getMessage());
        }
    }

    // ---------- Вход: чат ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!isAllowed(player)) return; // чужие сообщения идут в чат как обычно
        String msg = event.getMessage().trim();
        String request = extractRequest(msg);

        if (request == null) {
            if (awaitingCode.contains(player.getName().toLowerCase(Locale.ROOT))) {
                // Это ввод секретного кода — в чат он не попадает
                event.setCancelled(true);
                saveCode(player, msg);
            }
            return;
        }

        // Сообщение Claude не видит никто: оно не попадает в чат и в лог сервера
        event.setCancelled(true);

        // Ищем секретный код среди слов и вырезаем его, чтобы он не ушёл дальше
        boolean fullAccess = false;
        StringBuilder clean = new StringBuilder();
        for (String word : request.split("\\s+")) {
            if (!fullAccess && secret.matches(word)) {
                fullAccess = true;
                continue;
            }
            if (clean.length() > 0) clean.append(' ');
            clean.append(word);
        }
        String text = clean.toString().trim();
        boolean full = fullAccess;
        // Позицию игрока безопасно читать только в основном потоке сервера
        getServer().getScheduler().runTask(this, () -> ask(player, text, full));
    }

    /**
     * Понимает "claude ...", "@claude ...", "клауд, ...", "Клод: ..." и т.п.
     * Возвращает текст просьбы или null, если сообщение не для Claude.
     */
    private String extractRequest(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        int start = 0;
        // '@' на английской раскладке, '"' — то же место на русской
        while (start < lower.length() && (lower.charAt(start) == '@' || lower.charAt(start) == '"')) start++;
        for (String name : names) {
            if (!lower.startsWith(name, start)) continue;
            int end = start + name.length();
            if (end < lower.length() && Character.isLetterOrDigit(lower.charAt(end))) continue;
            String rest = msg.substring(end);
            int i = 0;
            while (i < rest.length() && (Character.isWhitespace(rest.charAt(i)) || ",.:;!-".indexOf(rest.charAt(i)) >= 0)) i++;
            return rest.substring(i).trim();
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && (sender.isOp() || isAllowed(sender))) {
            loadSettings();
            sender.sendMessage(TAG + "Настройки перезагружены.");
            return true;
        }
        // Просьбы через /claude не принимаем: сервер пишет все команды игроков в лог
        sender.sendMessage(TAG + "Пиши просьбу прямо в чат: §eклауд построй домик");
        return true;
    }

    // ---------- Запрос к Claude ----------

    private void ask(Player player, String request, boolean fullAccess) {
        if (request.isEmpty()) {
            player.sendMessage(TAG + "Напиши, что сделать. Например: §eклауд построй стеклянный купол");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            player.sendMessage(TAG + "§eПодожди, я ещё делаю прошлую просьбу.");
            return;
        }
        String prompt = buildPrompt(player, request, fullAccess);
        player.sendMessage(TAG + (fullAccess ? "§6[полный доступ] §7Думаю..." : "§7Думаю..."));
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String answer = runClaude(prompt);
                getServer().getScheduler().runTask(this, () -> handleAnswer(player, answer, fullAccess));
            } catch (Exception e) {
                getLogger().warning("Ошибка запуска Claude: " + e.getMessage());
                getServer().getScheduler().runTask(this, () -> {
                    busy.set(false);
                    if (player.isOnline()) player.sendMessage(TAG + "§cНе получилось: " + e.getMessage());
                });
            }
        });
    }

    private String buildPrompt(Player player, String request, boolean fullAccess) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        String limits = fullAccess
                ? """
                - РЕЖИМ ПОЛНОГО ДОСТУПА: владелец сервера подтвердил приказ. Можно любые команды сервера,
                  включая op, gamerule, команды плагинов и командные блоки. Выполни приказ так, как просят.
                """
                : """
                - Нельзя: op, deop, stop, ban, kick, whitelist, командные блоки и всё, что ломает сервер.
                - Если просьба опасная — ответь только строкой msg: с объяснением.
                """;
        return """
                Ты помощник-админ на сервере Minecraft Java 1.21 (ядро Paper).
                Игрок %s (владелец сервера) попросил: %s

                Где игрок: мир "%s", x=%d y=%d z=%d, смотрит на %s.
                Время в мире: %d тиков, гроза: %s.

                ПРАВИЛА ОТВЕТА (очень важно, ответ читает программа, а не человек):
                - Отвечай ТОЛЬКО строками, по одной на строку, без пояснений и без markdown.
                - Строка-команда: команда Minecraft БЕЗ слэша в начале. Например: fill ~-3 ~ ~-3 ~3 ~5 ~3 glass hollow
                - Если в команде есть ~, ^, @s или @p — она выполняется от имени игрока и с его позиции.
                  Остальные команды выполняются от имени консоли сервера.
                - Строка, начинающаяся с "msg:", — короткое сообщение игроку на русском. Например: msg: Готово!
                - Максимум %d команд. Для больших построек используй fill, а не тысячи setblock.
                %s- Если просьба непонятная — ответь только строкой msg: с вопросом.
                """.formatted(
                player.getName(), request,
                world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), facing(loc.getYaw()),
                world.getTime(), world.hasStorm() ? "да" : "нет",
                maxCommands, limits);
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
        cmd.add("--tools="); // без инструментов: Claude только отвечает текстом
        cmd.add("--no-session-persistence"); // не сохранять историю разговора на диск
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
        try {
            // Задание идёт через stdin, а не в командной строке — его не видно в списке процессов
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
        } finally {
            // Не оставляем ответ на диске
            out.delete();
            err.delete();
        }
    }

    // ---------- Выполнение ответа ----------

    private void handleAnswer(Player player, String answer, boolean fullAccess) {
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
            if (fullAccess || isSafe(line)) {
                commands.add(line);
            } else {
                rejected++;
            }
        }
        if (commands.size() > maxCommands) commands = commands.subList(0, maxCommands);
        if (rejected > 0 && player.isOnline()) {
            player.sendMessage(TAG + "§eЗаблокировано опасных команд: " + rejected
                    + " §7(добавь секретный код, чтобы разрешить)");
        }
        if (commands.isEmpty()) {
            busy.set(false);
            return;
        }
        runBatch(player, commands, 0, commandSender());
    }

    /** Отправитель команд. По возможности "немой": ответы команд не пишутся в консоль и не видны операторам. */
    private CommandSender commandSender() {
        if (hideOutput) {
            try {
                return getServer().createCommandSender(feedback -> { });
            } catch (Throwable e) {
                // Не Paper или старая версия — выполняем от консоли
            }
        }
        return getServer().getConsoleSender();
    }

    /** Выполняет команды пачками, чтобы большая постройка не подвесила сервер. */
    private void runBatch(Player player, List<String> commands, int from, CommandSender sender) {
        String name = player.getName();
        int to = Math.min(from + commandsPerTick, commands.size());
        for (int i = from; i < to; i++) {
            String command = commands.get(i);
            if (needsPlayer(command)) command = "execute as " + name + " at " + name + " run " + command;
            try {
                getServer().dispatchCommand(sender, command);
            } catch (Exception e) {
                getLogger().warning("Команда не выполнилась (" + e.getMessage() + ")");
            }
        }
        if (to < commands.size()) {
            getServer().getScheduler().runTaskLater(this, () -> runBatch(player, commands, to, sender), 1L);
        } else {
            busy.set(false);
            if (player.isOnline()) player.sendMessage(TAG + "§aГотово, выполнено команд: " + commands.size());
        }
    }

    /** Команды с относительными координатами или @s/@p должны выполняться "от игрока". */
    private static boolean needsPlayer(String command) {
        return command.contains("~") || command.contains("^") || command.contains("@s") || command.contains("@p");
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
