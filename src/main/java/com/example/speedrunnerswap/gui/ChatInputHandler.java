package com.example.speedrunnerswap.gui;

import com.example.speedrunnerswap.SpeedrunnerSwap;
import com.example.speedrunnerswap.task.TaskDifficulty;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatInputHandler implements Listener {
    private final SpeedrunnerSwap plugin;
    private final Map<UUID, InputState> activeInputs = new HashMap<>();

    private static class InputState {
        enum Type {
            TASK_ID, TASK_DESCRIPTION, TASK_DIFFICULTY, CONFIG_STRING, CONFIG_LIST_ADD
        }

        final Type type;
        String taskId; // Store task ID when collecting description
        String taskDescription;
        String configPath; // Path for config edits

        InputState(Type type) {
            this.type = type;
        }
    }

    public ChatInputHandler(SpeedrunnerSwap plugin) {
        this.plugin = plugin;
        // Try to register Paper's AsyncChatEvent handler via reflection (no
        // compile-time dependency)
        registerPaperAsyncChatHook();
    }

    public void expectTaskId(Player player) {
        activeInputs.put(player.getUniqueId(), new InputState(InputState.Type.TASK_ID));
    }

    public void expectTaskDescription(Player player, String taskId) {
        InputState state = new InputState(InputState.Type.TASK_DESCRIPTION);
        state.taskId = taskId;
        activeInputs.put(player.getUniqueId(), state);
    }

    public void expectTaskDescription(Player player) {
        // For when we already have the task ID stored
        activeInputs.put(player.getUniqueId(), new InputState(InputState.Type.TASK_DESCRIPTION));
    }

    public void expectConfigString(Player player, String path) {
        InputState st = new InputState(InputState.Type.CONFIG_STRING);
        st.configPath = path;
        activeInputs.put(player.getUniqueId(), st);
    }

    public void expectConfigListAdd(Player player, String path) {
        InputState st = new InputState(InputState.Type.CONFIG_LIST_ADD);
        st.configPath = path;
        activeInputs.put(player.getUniqueId(), st);
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!activeInputs.containsKey(uuid))
            return;
        event.setCancelled(true);
        String msg = event.getMessage();
        // Defer to main thread and share logic with Paper event
        plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, msg));
    }

    // Clear any pending chat prompts when a player disconnects to prevent stale
    // state
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeInputs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        activeInputs.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Shared input handling used by both legacy and Paper chat events (executed on
     * main thread).
     */
    private void handleInput(Player player, String rawMessage) {
        UUID uuid = player.getUniqueId();
        InputState state = activeInputs.remove(uuid);
        if (state == null)
            return;
        String msg = rawMessage == null ? "" : rawMessage.trim();
        switch (state.type) {
            case TASK_ID -> {
                if ("cancel".equalsIgnoreCase(msg)) {
                    player.sendMessage("§c任务创建已取消。");
                    return;
                }
                if (msg.isEmpty()) {
                    player.sendMessage("§c任务ID不能为空。请重试。");
                    expectTaskId(player);
                    return;
                }
                String id = msg.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
                if (id.isBlank()) {
                    player.sendMessage("§c任务ID必须包含字母或数字。请重试。");
                    expectTaskId(player);
                    return;
                }
                var mode = plugin.getTaskManagerMode();
                if (mode != null && mode.isTask(id)) {
                    player.sendMessage("§c已存在该ID的任务。请使用不同的ID。");
                    expectTaskId(player);
                    return;
                }
                expectTaskDescription(player, id);
                player.sendMessage("§6请输入任务 §e" + id + " §6的§b描述§6。输入 §cCancel §6取消。");
            }
            case TASK_DESCRIPTION -> {
                if ("cancel".equalsIgnoreCase(msg)) {
                    player.sendMessage("§c任务创建已取消。");
                    return;
                }
                String id = state.taskId != null && !state.taskId.isBlank()
                        ? state.taskId.trim()
                        : ("custom_" + System.currentTimeMillis());
                if (msg.isEmpty()) {
                    player.sendMessage("§c描述不能为空。请输入描述。");
                    expectTaskDescription(player, id);
                    return;
                }
                InputState next = new InputState(InputState.Type.TASK_DIFFICULTY);
                next.taskId = id;
                next.taskDescription = msg;
                activeInputs.put(uuid, next);
                player.sendMessage("§6请选择 §e" + id + " §6的难度。输入 §aEasy§6、§eMedium§6 或 §cHard§6。输入 §cCancel §6取消。");
            }
            case TASK_DIFFICULTY -> {
                if ("cancel".equalsIgnoreCase(msg)) {
                    player.sendMessage("§c任务创建已取消。");
                    return;
                }
                TaskDifficulty difficulty;
                try {
                    difficulty = TaskDifficulty.valueOf(msg.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    player.sendMessage("§c无效的难度。请输入 Easy、Medium 或 Hard。");
                    InputState retry = new InputState(InputState.Type.TASK_DIFFICULTY);
                    retry.taskId = state.taskId;
                    retry.taskDescription = state.taskDescription;
                    activeInputs.put(uuid, retry);
                    return;
                }
                String id = state.taskId != null && !state.taskId.isBlank()
                        ? state.taskId.trim()
                        : ("custom_" + System.currentTimeMillis());
                String description = state.taskDescription != null ? state.taskDescription : "";
                var mode = plugin.getTaskManagerMode();
                if (mode == null) {
                    player.sendMessage("§c任务管理器未初始化。请稍后再试。");
                    return;
                }
                mode.addCustomTask(id, description, difficulty);
                player.sendMessage("§a已添加自定义任务 §e" + id + " §7("
                        + difficulty.name().toLowerCase(java.util.Locale.ROOT) + ")§a。");
                plugin.getGuiManager().openTaskManagerMenu(player);
            }
            case CONFIG_STRING -> {
                if ("__dynamic__".equals(state.configPath)) {
                    if ("cancel".equalsIgnoreCase(msg)) {
                        player.sendMessage("§c配置编辑已取消。");
                        return;
                    }
                    int idx = msg.indexOf('=');
                    if (idx <= 0 || idx == msg.length() - 1) {
                        player.sendMessage("§c用法：路径=值（示例：swap.interval=75）");
                        expectConfigString(player, "__dynamic__");
                        return;
                    }
                    String rawPath = msg.substring(0, idx).trim();
                    String rawValue = msg.substring(idx + 1).trim();
                    Object parsed = parseValue(rawValue);
                    plugin.getConfig().set(rawPath, parsed);
                    plugin.saveConfig();
                    player.sendMessage("§a已将 §e" + rawPath + " §a更新为 §f" + rawValue + "§a。");
                    return;
                }
                if ("cancel".equalsIgnoreCase(msg)) {
                    player.sendMessage("§c配置编辑已取消。");
                    return;
                }
                String value = msg;
                if ("clear".equalsIgnoreCase(msg)) {
                    value = "";
                }
                plugin.getConfig().set(state.configPath, value);
                plugin.saveConfig();
                player.sendMessage("§a已更新 §e" + state.configPath + "§a。");
            }
            case CONFIG_LIST_ADD -> {
                String value = msg;
                if ("safe_swap.dangerous_blocks".equalsIgnoreCase(state.configPath)) {
                    value = msg.toUpperCase(java.util.Locale.ROOT).trim();
                    org.bukkit.Material material = org.bukkit.Material.matchMaterial(value);
                    if (material == null) {
                        player.sendMessage("§c未知的方块 '" + msg + "'。添加已取消。");
                        return;
                    }
                    java.util.Set<org.bukkit.Material> cache = plugin.getConfigManager().getDangerousBlocks();
                    if (!cache.add(material)) {
                        player.sendMessage("§e" + material.name() + " §c已在黑名单中。");
                        plugin.getGuiManager().openDangerousBlocksMenu(player);
                        return;
                    }
                    value = material.name();
                }
                java.util.List<String> list = plugin.getConfig().getStringList(state.configPath);
                if (!list.contains(value)) {
                    list.add(value);
                }
                plugin.getConfig().set(state.configPath, list);
                plugin.saveConfig();
                player.sendMessage("§a已添加到 §e" + state.configPath + "§a。");
                if ("safe_swap.dangerous_blocks".equalsIgnoreCase(state.configPath)) {
                    plugin.getGuiManager().openDangerousBlocksMenu(player);
                }
            }
        }
    }

    private Object parseValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    /**
     * Register Paper's AsyncChatEvent using reflection for cross-platform support.
     */
    @SuppressWarnings({ "unchecked" })
    private void registerPaperAsyncChatHook() {
        try {
            final Class<?> asyncChatCls = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            // Create an EventExecutor that extracts player and message via reflection
            org.bukkit.plugin.EventExecutor exec = (listener, event) -> {
                if (!asyncChatCls.isInstance(event))
                    return;
                Object ev = event;
                org.bukkit.entity.Player playerTmp;
                String msgTmp;
                try {
                    java.lang.reflect.Method getPlayer = asyncChatCls.getMethod("getPlayer");
                    playerTmp = (org.bukkit.entity.Player) getPlayer.invoke(ev);
                } catch (Throwable t) {
                    return; // can't proceed
                }
                try {
                    // message() returns net.kyori.adventure.text.Component
                    java.lang.reflect.Method messageM = asyncChatCls.getMethod("message");
                    Object component = messageM.invoke(ev);
                    // Reflectively serialize to plain text to avoid compile dependency
                    Class<?> serCls = Class
                            .forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                    java.lang.reflect.Method plainText = serCls.getMethod("plainText");
                    Object serializer = plainText.invoke(null);
                    java.lang.reflect.Method serialize = serializer.getClass().getMethod("serialize",
                            Class.forName("net.kyori.adventure.text.Component"));
                    Object s = serialize.invoke(serializer, component);
                    msgTmp = s != null ? String.valueOf(s) : "";
                } catch (Throwable t) {
                    msgTmp = ""; // fallback
                }
                final org.bukkit.entity.Player player = playerTmp;
                final String msg = msgTmp;
                // Only process if this player has a pending chat input prompt
                if (!activeInputs.containsKey(player.getUniqueId()))
                    return;
                // Cancel the Paper chat event
                try {
                    java.lang.reflect.Method setCancelled = asyncChatCls.getMethod("setCancelled", boolean.class);
                    setCancelled.invoke(ev, true);
                } catch (Throwable ignored) {
                }
                // Delegate to main thread shared handler
                plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, msg));
            };
            // Register the event executor
            plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends org.bukkit.event.Event>) asyncChatCls,
                    this,
                    org.bukkit.event.EventPriority.NORMAL,
                    exec,
                    plugin,
                    true);
            plugin.getLogger().info("Paper AsyncChatEvent hook enabled.");
        } catch (ClassNotFoundException e) {
            // Not running on Paper (or older versions) - nothing to do
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to enable Paper AsyncChatEvent hook: " + t.getMessage());
        }
    }

    // Removed unused handler methods after inlining logic in onChat() to reduce
    // lints.

    public void clearInput(Player player) {
        activeInputs.remove(player.getUniqueId());
    }
}
