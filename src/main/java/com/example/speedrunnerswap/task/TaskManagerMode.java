package com.example.speedrunnerswap.task;

import com.example.speedrunnerswap.SpeedrunnerSwap;
import com.example.speedrunnerswap.utils.BukkitCompat;
import com.example.speedrunnerswap.utils.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles Task Manager mode: assigns tasks and tracks per-player state.
 */
public class TaskManagerMode {
    private final SpeedrunnerSwap plugin;

    // Per-player assigned task id
    private final Map<UUID, String> assignments = new HashMap<>();
    // Progress maps for complex tasks
    final Map<UUID, EnumSet<org.bukkit.DyeColor>> sheepKilledWithIronShovel = new HashMap<>();
    // Bed exploder attribution per world
    final Map<String, UUID> lastBedExploderPerWorld = new HashMap<>();
    private final Map<UUID, Integer> rerollsUsed = new HashMap<>();
    private final Set<UUID> completedFirstTurn = new HashSet<>();
    private long roundStartedAtMs = 0L;

    // Task registry: id -> definition
    private final Map<String, TaskDefinition> registry = new LinkedHashMap<>();
    private final Set<String> customTaskIds = new HashSet<>();
    // Difficulty filter and progression gates
    private TaskDifficulty difficultyFilter = TaskDifficulty.MEDIUM;
    private boolean netherReached = false;
    private boolean endReached = false;

    public TaskManagerMode(SpeedrunnerSwap plugin) {
        this.plugin = plugin;
        loadTasks();
        // Load difficulty filter from config (default MEDIUM)
        try {
            String diff = plugin.getConfig().getString("task_manager.difficulty", "MEDIUM");
            this.difficultyFilter = TaskDifficulty.valueOf(diff.toUpperCase());
        } catch (Throwable ignored) {
        }
        // Load any persisted runtime assignments (if present)
        loadAssignmentsFromConfig();
    }

    /** Heuristic category inference for built-in tasks to support gating. */
    private void postProcessDefinitions() {
        for (var e : new java.util.ArrayList<>(registry.entrySet())) {
            TaskDefinition d = e.getValue();
            java.util.List<String> cats = d.categories();
            if (cats == null || cats.isEmpty()) {
                java.util.List<String> inferred = new java.util.ArrayList<>();
                String s = (d.id() + " " + d.description()).toLowerCase(java.util.Locale.ROOT);
                if (s.contains("nether"))
                    inferred.add("nether");
                if (s.contains(" ender ") || s.contains(" the end") || s.contains(" end ") || s.contains("dragon")
                        || s.contains("shulker") || s.contains("elytra"))
                    inferred.add("end");
                if (inferred.isEmpty())
                    inferred.add("overworld");
                TaskDefinition nd = new TaskDefinition(d.id(), d.description(), d.type(), d.params(),
                        d.difficulty() != null ? d.difficulty() : TaskDifficulty.MEDIUM, inferred, d.enabled());
                registry.put(e.getKey(), nd);
            }
        }
    }

    public String getAssignedTask(Player p) {
        return assignments.get(p.getUniqueId());
    }

    public void assignAndAnnounceTasks(List<Player> players) {
        assignAndAnnounceTasks(players, true);
    }

    public void assignAdditionalTasks(List<Player> players) {
        assignAndAnnounceTasks(players, false);
    }

    private void assignAndAnnounceTasks(List<Player> players, boolean resetExistingAssignments) {
        if (players == null || players.isEmpty()) {
            return;
        }

        if (resetExistingAssignments) {
            assignments.clear();
            sheepKilledWithIronShovel.clear();
            lastBedExploderPerWorld.clear();
            rerollsUsed.clear();
            completedFirstTurn.clear();
            if (!plugin.getGameManager().isGameRunning()) {
                roundStartedAtMs = 0L;
            }
            resetProgressGates();
        }

        List<String> candidates = getAssignableTaskIds();
        if (candidates.isEmpty()) {
            handleEmptyTaskPool(players);
            return;
        }

        List<String> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, new Random());
        int idx = 0;
        for (Player p : players) {
            if (p == null) {
                continue;
            }
            String taskId = pickTaskForPlayer(p.getUniqueId(), shuffled, idx++);
            if (taskId == null) {
                continue;
            }
            assignments.put(p.getUniqueId(), taskId);
            TaskDefinition def = registry.get(taskId);
            announceTask(p, def);
        }
        saveAssignmentsToConfig();
    }

    private void handleEmptyTaskPool(List<Player> players) {
        Msg.broadcast("§c[任务管理器] 无法分配任务 — 没有启用的任务。回合已取消。");
        plugin.getLogger().warning("任务管理器分配中止：当前设置下没有启用的任务。");

        if (plugin.getGameManager() != null && plugin.getGameManager().isGameRunning()) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getGameManager().stopGame());
        }

        for (Player p : players) {
            if (p != null && p.isOnline()) {
                p.sendMessage("§e请在开始此模式前在 /swap gui → 任务管理器 中启用任务。");
            }
        }
    }

    private List<String> getAssignableTaskIds() {
        List<String> candidates = getCandidateTaskIds();
        if (!candidates.isEmpty()) {
            return candidates;
        }
        return registry.values().stream()
                .filter(TaskDefinition::enabled)
                .map(TaskDefinition::id)
                .toList();
    }

    private String pickTaskForPlayer(UUID playerId, List<String> shuffledCandidates, int fallbackIndex) {
        if (shuffledCandidates == null || shuffledCandidates.isEmpty()) {
            return null;
        }

        Set<String> taken = new HashSet<>();
        for (Map.Entry<UUID, String> entry : assignments.entrySet()) {
            if (!entry.getKey().equals(playerId)) {
                taken.add(entry.getValue());
            }
        }

        for (String candidate : shuffledCandidates) {
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }

        return shuffledCandidates.get(fallbackIndex % shuffledCandidates.size());
    }

    private void announceTask(Player p, TaskDefinition def) {
        if (p == null || def == null)
            return;

        BukkitCompat.showTitle(p, "§6§l你的秘密任务", "§e" + def.description(), 10, 160, 20);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BukkitCompat.showTitle(p, "§a§l手动完成", "§b使用：/swap complete confirm", 10, 120, 20);
            p.sendMessage("§b提醒：§e使用 /swap complete confirm §b当你完成任务时。");
        }, 60L);

        p.sendMessage("§6§l[任务管理器] 你的秘密任务已分配！");
        p.sendMessage("§e → " + def.description());
        p.sendMessage("");
        p.sendMessage("§a§l完成方式：");
        p.sendMessage("§7• §f某些任务在检测到时自动完成");
        p.sendMessage("§7• §f手动完成：§e/swap complete confirm");
        if (isTaskRerollEnabled()) {
            p.sendMessage("§7• §f一次重掷机会：§e/swap complete reroll confirm");
        }
        p.sendMessage("§7• §f查看你的任务：§e/swap complete");
        p.sendMessage("");
        p.sendMessage("§6⚠ 手动完成将立即赢得游戏！");
        p.sendMessage("§7只有在你实际完成任务时才使用它。");
        p.sendMessage("§6" + "=".repeat(45));
    }

    /** Call when a player has completed their task */
    public void complete(Player p) {
        if (p == null)
            return;
        String taskId = assignments.get(p.getUniqueId());
        if (taskId == null)
            return; // not assigned
        sheepKilledWithIronShovel.remove(p.getUniqueId());
        TaskDefinition completedTask = registry.get(taskId);
        String description = completedTask != null ? completedTask.description() : taskId;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                plugin.getGameManager().endTaskCompetitionRound(p, description);
            } catch (Throwable ignored) {
            }
        });
    }

    public Map<UUID, String> getAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public void beginRound(List<Player> players) {
        roundStartedAtMs = System.currentTimeMillis();
        rerollsUsed.clear();
        completedFirstTurn.clear();
        if (players != null) {
            for (Player player : players) {
                if (player != null) {
                    rerollsUsed.put(player.getUniqueId(), 0);
                }
            }
        }
    }

    public void markFirstTurnCompleted(Player player) {
        if (player == null) {
            return;
        }
        completedFirstTurn.add(player.getUniqueId());
    }

    public boolean isTaskRerollEnabled() {
        return plugin.getConfig().getBoolean("task_manager.reroll.enabled", true)
                && plugin.getConfig().getInt("task_manager.reroll.uses_per_player", 1) > 0;
    }

    public int getRemainingRerolls(Player player) {
        if (player == null) {
            return 0;
        }
        int maxUses = Math.max(0, plugin.getConfig().getInt("task_manager.reroll.uses_per_player", 1));
        int used = rerollsUsed.getOrDefault(player.getUniqueId(), 0);
        return Math.max(0, maxUses - used);
    }

    public String getAssignedTaskDescription(Player player) {
        if (player == null) {
            return null;
        }
        String taskId = assignments.get(player.getUniqueId());
        if (taskId == null) {
            return null;
        }
        TaskDefinition definition = registry.get(taskId);
        return definition != null ? definition.description() : taskId;
    }

    public String getRerollUnavailableReason(Player player) {
        if (player == null) {
            return "未找到玩家。";
        }
        if (!isTaskRerollEnabled()) {
            return "任务重掷已禁用。";
        }
        if (!assignments.containsKey(player.getUniqueId())) {
            return "你还没有被分配任务。";
        }
        if (getRemainingRerolls(player) <= 0) {
            return "你已经使用了任务重掷机会。";
        }

        boolean gameRunning = plugin.getGameManager() != null && plugin.getGameManager().isGameRunning();
        if (!gameRunning) {
            if (!plugin.getConfig().getBoolean("task_manager.reroll.allow_before_start", true)) {
                return "回合开始前任务重掷已禁用。";
            }
            return null;
        }

        if (!plugin.getConfig().getBoolean("task_manager.reroll.allow_during_first_turn", true)) {
            return "回合开始后任务重掷已禁用。";
        }

        if (plugin.isParallelTaskMode()) {
            int windowSeconds = Math.max(0, plugin.getConfig().getInt("task_manager.reroll.task_race_window_seconds", 60));
            if (windowSeconds <= 0) {
                return "任务竞赛模式回合开始后重掷已禁用。";
            }
            if (roundStartedAtMs <= 0L) {
                return "重掷窗口尚未准备好，请稍后再试。";
            }
            long deadline = roundStartedAtMs + (windowSeconds * 1000L);
            if (System.currentTimeMillis() > deadline) {
                return "你的任务竞赛重掷窗口已过期。";
            }
            return null;
        }

        if (completedFirstTurn.contains(player.getUniqueId())) {
            return "你的第一回合已经结束。";
        }
        return null;
    }

    public TaskDefinition rerollTask(Player player) {
        String blocked = getRerollUnavailableReason(player);
        if (blocked != null || player == null) {
            return null;
        }

        String currentTask = assignments.get(player.getUniqueId());
        List<String> candidates = new ArrayList<>(getAssignableTaskIds());
        candidates.remove(currentTask);
        if (candidates.isEmpty()) {
            return null;
        }

        Set<String> assignedToOthers = new HashSet<>();
        for (Map.Entry<UUID, String> entry : assignments.entrySet()) {
            if (!entry.getKey().equals(player.getUniqueId())) {
                assignedToOthers.add(entry.getValue());
            }
        }

        List<String> preferred = candidates.stream()
                .filter(candidate -> !assignedToOthers.contains(candidate))
                .toList();
        List<String> pool = preferred.isEmpty() ? candidates : preferred;
        String taskId = pool.get(new Random().nextInt(pool.size()));
        assignments.put(player.getUniqueId(), taskId);
        rerollsUsed.merge(player.getUniqueId(), 1, Integer::sum);
        sheepKilledWithIronShovel.remove(player.getUniqueId());
        saveAssignmentsToConfig();

        TaskDefinition definition = registry.get(taskId);
        announceTask(player, definition);
        return definition;
    }

    /** Broadcast the current assignments to the whole server. */
    public void broadcastAssignments() {
        if (assignments.isEmpty()) {
            return;
        }
        Msg.broadcast("§6§l[" + (plugin.isDualBodyTaskMode() ? "多人任务大师模式" : "任务大师") + "] 当前分配");
        for (var entry : assignments.entrySet()) {
            UUID uuid = entry.getKey();
            String taskId = entry.getValue();
            TaskDefinition def = registry.get(taskId);
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName())
                    .orElse(uuid.toString().substring(0, 8));
            String description = def != null ? def.description() : taskId;
            Msg.broadcast("§e• §f" + name + " §7→ §b" + description);
        }
    }

    public void saveAssignmentsToConfig() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (var e : assignments.entrySet()) {
                map.put(e.getKey().toString(), e.getValue());
            }
            plugin.getConfig().set("task_manager.runtime.assignments", map);
            plugin.saveConfig();
        } catch (Throwable ignored) {
        }
    }

    public void loadAssignmentsFromConfig() {
        try {
            Object raw = plugin.getConfig().get("task_manager.runtime.assignments");
            if (raw instanceof Map<?, ?> m) {
                assignments.clear();
                for (var e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    String v = String.valueOf(e.getValue());
                    try {
                        UUID uuid = UUID.fromString(k);
                        if (isTask(v))
                            assignments.put(uuid, v);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public TaskDefinition getTask(String id) {
        return registry.get(id);
    }

    public boolean isTask(String id) {
        return registry.containsKey(id);
    }

    /**
     * Attribute a bed explosion in a world to a player for a short time. Key is
     * world name.
     */
    public void markBedExploder(Player p) {
        if (p == null || p.getWorld() == null)
            return;
        String worldName = p.getWorld().getName();
        lastBedExploderPerWorld.put(worldName, p.getUniqueId());
        // Clear after a few seconds (use captured worldName to avoid race condition)
        Bukkit.getScheduler().runTaskLater(plugin, () -> lastBedExploderPerWorld.remove(worldName), 20L * 5);
    }

    public UUID getRecentBedExploder(String worldName) {
        return lastBedExploderPerWorld.get(worldName);
    }

    /** Load tasks from tasks.yml and built-in defaults */
    private void loadTasks() {
        registry.clear();
        customTaskIds.clear();
        // First attempt to load from tasks.yml if available
        boolean loadedFromFile = loadFromTasksYml();
        // If none loaded, optionally include built-in defaults
        if (!loadedFromFile && plugin.getConfig().getBoolean("task_manager.include_default_tasks", true)) {
            registerDefaults();
        }
        // Also load custom tasks from config for backward compatibility
        loadCustomTasks();
        // Ensure we have at least some tasks
        if (registry.isEmpty()) {
            plugin.getLogger().warning("No tasks loaded! Loading default tasks as fallback.");
            registerDefaults();
        }
        // Post-process to infer categories for gating if not provided
        postProcessDefinitions();
        sheepKilledWithIronShovel.clear();
        lastBedExploderPerWorld.clear();
        assignments.clear();
        resetProgressGates();
    }

    /** Load custom tasks from config */
    private void loadCustomTasks() {
        var customTasks = plugin.getConfig().getList("task_manager.custom_tasks");
        if (customTasks == null)
            return;

        for (Object obj : customTasks) {
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> taskMap = (Map<String, Object>) obj;
                String id = String.valueOf(taskMap.get("id"));
                String description = String.valueOf(taskMap.get("description"));
                TaskDifficulty difficulty = TaskDifficulty.MEDIUM;
                Object rawDiff = taskMap.get("difficulty");
                if (rawDiff != null) {
                    try {
                        difficulty = TaskDifficulty.valueOf(String.valueOf(rawDiff).toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        difficulty = TaskDifficulty.MEDIUM;
                    }
                }

                if (id != null && !id.equals("null") && description != null && !description.equals("null")) {
                    register(new TaskDefinition(id, description, TaskType.COMPLEX_TASK, difficulty));
                    customTaskIds.add(id);
                    plugin.getLogger().info("Loaded custom task: " + id);
                }
            }
        }
    }

    /** Add a custom task and save to config */
    public void addCustomTask(String id, String description, TaskDifficulty difficulty) {
        if (id == null || id.isBlank()) {
            id = "custom_" + System.currentTimeMillis();
        }
        if (description == null)
            description = "";
        if (difficulty == null)
            difficulty = TaskDifficulty.MEDIUM;

        // Add to registry
        register(new TaskDefinition(id, description, TaskType.COMPLEX_TASK, difficulty));
        customTaskIds.add(id);

        // Save to config
        List<?> rawList = plugin.getConfig().getList("task_manager.custom_tasks");
        List<Object> customTasks = new ArrayList<>();
        if (rawList != null)
            customTasks.addAll(rawList);

        Map<String, Object> taskMap = new HashMap<>();
        taskMap.put("id", id);
        taskMap.put("description", description);
        taskMap.put("difficulty", difficulty.name());
        customTasks.add(taskMap);

        plugin.getConfig().set("task_manager.custom_tasks", customTasks);
        plugin.saveConfig();
    }

    /** Backward compatibility overload */
    public void addCustomTask(String id, String description) {
        addCustomTask(id, description, TaskDifficulty.MEDIUM);
    }

    /** Remove a custom task and save to config */
    public boolean removeCustomTask(String id) {
        // Check if it's a custom task (not built-in)
        var customTasks = plugin.getConfig().getList("task_manager.custom_tasks");
        if (customTasks == null)
            return false;

        boolean removed = false;
        Iterator<?> iter = customTasks.iterator();
        while (iter.hasNext()) {
            Object obj = iter.next();
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> taskMap = (Map<String, Object>) obj;
                if (id.equals(taskMap.get("id"))) {
                    iter.remove();
                    removed = true;
                    break;
                }
            }
        }

        if (removed) {
            // Remove from registry
            registry.remove(id);
            customTaskIds.remove(id);

            // Save config
            plugin.getConfig().set("task_manager.custom_tasks", customTasks);
            plugin.saveConfig();

            // Reload tasks to ensure consistency
            loadTasks();
        }

        return removed;
    }

    /** Get all custom task IDs */
    public List<String> getCustomTaskIds() {
        return new ArrayList<>(customTaskIds);
    }

    public Map<TaskDifficulty, Integer> getTaskCounts(boolean enabledOnly) {
        EnumMap<TaskDifficulty, Integer> counts = new EnumMap<>(TaskDifficulty.class);
        for (TaskDifficulty diff : TaskDifficulty.values())
            counts.put(diff, 0);
        for (TaskDefinition def : registry.values()) {
            if (enabledOnly && !def.enabled())
                continue;
            TaskDifficulty diff = def.difficulty() != null ? def.difficulty() : TaskDifficulty.MEDIUM;
            counts.put(diff, counts.get(diff) + 1);
        }
        return counts;
    }

    /** Reload tasks from config */
    public void reloadTasks() {
        plugin.reloadConfig();
        loadTasks();
    }

    private void registerDefaults() {
        // === 超高难度多步骤任务 (15) ===
        register(new TaskDefinition("die_on_bedrock_fall",
                "从地面掉落到基岩并因摔落伤害死在基岩上", TaskType.DIE_ON_BEDROCK_FALL));
        register(new TaskDefinition("kill_golem_nether_bed", "在下界使用床爆炸杀死铁傀儡",
                TaskType.KILL_GOLEM_NETHER_BED));
        register(new TaskDefinition("kill_all_sheep_iron_shovel", "用铁锹杀死每种颜色的羊各一只",
                TaskType.KILL_ALL_SHEEP_IRON_SHOVEL));
        register(new TaskDefinition("sleep_nether_fortress", "在下界要塞中放置床并睡觉",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_wither_skeleton_wooden_sword",
                "仅用木剑杀死凋灵骷髅", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_creeper_explosion_point_blank",
                "在近距离苦力怕爆炸中存活", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_enderman_with_snowballs", "仅用雪球杀死末影人",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_in_end_void_holding_elytra", "手持鞘翅死在末地虚空中",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_phantom_melee_only",
                "仅用近战攻击杀死幻翼（不能使用弓/弩）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("tame_wolf_using_rotten_flesh", "仅用腐肉驯服狼",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_piglin_brute_leather_armor",
                "穿着全套皮革盔甲杀死猪灵蛮兵", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_lava_swim_30_seconds", "在岩浆中连续游泳存活30秒",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_elder_guardian_stone_sword", "用石剑杀死远古守卫者",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("complete_raid_wooden_tools", "仅用木制工具完成袭击",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_ender_dragon_punch",
                "用拳头对末影龙造成最后一击", TaskType.COMPLEX_TASK));

        // === 极限地下挑战 (10) ===
        register(new TaskDefinition("mine_obsidian_wooden_pickaxe",
                "用木镐破坏黑曜石（不会掉落，只是破坏它）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("find_diamond_y_minus_50", "在Y=-50或更低处找到并挖掘钻石",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_by_suffocation_gravel", "被沙砾或沙子窒息而死",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_lava_pool_underground", "在Y=5或更低处创建3x3岩浆池",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("find_stronghold_no_eyes", "不使用末影之眼找到要塞",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("mine_50_ancient_debris", "挖掘5个远古残骸方块", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_beacon_underground", "在Y=0以下激活信标",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_mob_fall_damage_mine", "在废弃矿井中用摔落伤害杀死任何生物",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("place_water_nether", "在下界放置水桶（它会蒸发）",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("dig_to_void_pickaxe_only",
                "仅用镐从地面挖到Y=0（不能使用TNT/其他工具）", TaskType.COMPLEX_TASK));

        // === 致命下界挑战 (10) ===
        register(new TaskDefinition("kill_ghast_melee_attack",
                "用近战攻击杀死恶魂（不能反射火球）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("swim_lava_ocean_100_blocks", "在下界岩浆海洋中游泳100格",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_hoglin_no_armor", "不穿盔甲杀死疣猪兽",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_soul_sand_magma_cube",
                "在岩浆怪攻击你时收集灵魂沙", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_house_basalt_deltas", "在玄武岩三角洲生物群系建造5x5房屋",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_blaze_water_bucket", "用水桶杀死烈焰人（溅射伤害）",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_crying_obsidian_ruined_portal",
                "从废弃传送门收集10个哭泣的黑曜石", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_piglin_their_own_crossbow", "用猪灵自己的弩杀死猪灵",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_surrounded_by_fire",
                "在完全被火包围的情况下存活10秒", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_by_wither_effect_nether", "在下界因凋零效果而死",
                TaskType.COMPLEX_TASK));

        // === 不可能的末地挑战 (10) ===
        register(new TaskDefinition("kill_enderman_staring_contest",
                "盯着末影人看5秒后杀死它", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("break_end_crystal_fist", "用拳头破坏末地水晶",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_dragon_breath_10_seconds",
                "在龙息中站立存活10秒", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_shulker_their_own_bullet", "用潜影贝自己的导弹杀死潜影贝",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_chorus_fruit_levitation",
                "在漂浮效果影响下收集紫颂果", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_tower_end_spawn", "在末地重生平台上建造20格高的塔",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_endermite_end_dimension", "在末地维度中杀死末影螨",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_dragon_egg_no_piston", "不使用活塞收集龙蛋",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_void_damage_elytra", "受到虚空伤害并用鞘翅存活",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("punch_ender_dragon_100_times", "用拳头击打末影龙20次",
                TaskType.COMPLEX_TASK));

        // === 极限战斗挑战 (10) ===
        register(new TaskDefinition("kill_iron_golem_cactus", "用仙人掌伤害杀死铁傀儡",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_ravager_fishing_rod", "仅用钓鱼竿杀死劫掠兽",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_warden_no_sound", "不发出任何声音杀死监守者",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_skeleton_army_10",
                "在10个以上骷髅同时攻击下存活", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_witch_their_own_potion", "用女巫自己的溅射药水杀死女巫",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_vindicator_their_axe", "用卫道士自己的斧头杀死卫道士",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_creeper_chain_explosion", "在5个以上苦力怕的连锁爆炸中存活",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_zombie_pigman_gold_sword", "用金剑杀死僵尸猪灵",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_spider_jockey_separately",
                "分别杀死蜘蛛骑士的蜘蛛和骷髅", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_wither_boss_melee", "仅用近战攻击在与凋灵的战斗中存活",
                TaskType.COMPLEX_TASK));

        // === 致命建筑挑战 (10) ===
        register(new TaskDefinition("build_bridge_lava_lake", "在岩浆湖上建造桥梁（至少20格）",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_house_monster_spawner", "建造一个内部有怪物刷笼的房屋",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_floating_island_void", "在末地虚空上方创建浮空岛",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_tower_lightning_storm", "在雷暴天气中建造50格高的塔",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_pixel_art_nether_roof",
                "在下界顶部创建像素画（至少10x10）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_underwater_base_no_doors",
                "建造水下基地，不使用门或气穴", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_redstone_trap_works", "创建能杀死生物的红石陷阱",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_castle_desert_temple", "在沙漠神殿顶部建造城堡",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_farm_end_island", "在末地小岛上创建农场",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_house_one_chunk", "建造恰好占一个区块（16x16）的房屋",
                TaskType.COMPLEX_TASK));

        // === 疯狂生存挑战 (10) ===
        register(new TaskDefinition("survive_day_half_heart", "以半颗心存活整个昼夜循环",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_drowning_air_pocket",
                "在水下找到气穴避免溺水", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("eat_only_poisonous_food_day",
                "一天只吃有毒食物（蜘蛛眼等）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_desert_no_water",
                "在沙漠中10分钟不喝水存活", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_cave_no_torches",
                "在洞穴系统中5分钟不放置火把存活", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_blizzard_powder_snow",
                "被困在细雪中存活30秒", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_hunger_games_no_food", "饥饿值为空时存活2分钟",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_fall_water_bucket_clutch",
                "从50格以上高处坠落用水桶落地存活", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_mob_spawner_room_1_minute", "在怪物刷笼房间中存活1分钟",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_on_single_block_void",
                "在虚空上方单格方块上存活2分钟", TaskType.COMPLEX_TASK));

        // === 极限收集挑战 (15) ===
        register(new TaskDefinition("collect_stack_rotten_flesh_zombies",
                "仅通过杀死僵尸收集64个腐肉", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_armor_trims", "收集5种不同的盔甲纹饰模板",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_every_enchanted_book", "收集10种不同的附魔书",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_gunpowder_creepers", "仅通过杀死苦力怕收集64个火药",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_pottery_sherds", "收集5种不同的陶片",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_bones_skeletons", "仅通过杀死骷髅收集64根骨头",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_every_music_disc", "收集5种不同的音乐唱片",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_string_spiders", "仅通过杀死蜘蛛收集64根线",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_banner_patterns", "收集5种不同的旗帜图案",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_slimeballs_slimes", "仅通过杀死史莱姆收集64个粘液球",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_every_horse_armor", "收集皮革、铁、金和钻石马铠",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_blaze_rods_blazes", "仅通过杀死烈焰人收集64根烈焰棒",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_smithing_templates", "收集3种不同的锻造模板",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_stack_ender_pearls_endermen",
                "仅通过杀死末影人收集64个末影珍珠", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_every_suspicious_stew", "收集5种不同的迷之炖菜",
                TaskType.COMPLEX_TASK));

        // === 最终不可能挑战 (10) ===
        register(new TaskDefinition("kill_every_hostile_mob_type", "杀死游戏中每种敌对生物各一个",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("complete_all_advancements_hour", "完成20个不同的进度",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_max_level_enchant_table",
                "创建满级附魔台设置（15个书架）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("defeat_three_bosses", "击败末影龙、凋灵和远古守卫者",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_every_potion_type", "酿造10种不同类型的药水",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("max_out_villager_trades", "将5种不同村民职业的交易升到满级",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_automatic_farm_system",
                "创建3种不同的自动农场（作物、生物等）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_hardcore_week", "连续存活7个游戏日不死亡",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_nether_highway_1000", "建造跨越1000格的下界高速公路",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("become_minecraft_god", "达到最高等级（技术上不可能 - 30级以上）",
                TaskType.COMPLEX_TASK));
        register(
                new TaskDefinition("fill_chest_ores", "用每种矿石各一个填满箱子", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("explode_50_tnt", "引爆50个TNT方块", TaskType.COMPLEX_TASK));
        register(
                new TaskDefinition("dig_to_void", "挖穿基岩掉入虚空", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_obsidian_room", "建造一个完全由黑曜石制成的3x3x3房间",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("find_spawner_break", "找到并破坏怪物刷笼", TaskType.COMPLEX_TASK));

        // === 战斗与生物挑战 (10) ===
        register(new TaskDefinition("kill_50_mobs", "杀死50个敌对生物", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_zombie_gold_sword", "用金剑杀死10个僵尸",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_creeper_no_explosion", "杀死5个苦力怕且不让它们爆炸",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_to_baby_zombie", "被小僵尸杀死", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_skeleton_own_arrow", "用骷髅自己的箭杀死骷髅",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_5_creeper_explosions", "在5次苦力怕爆炸中存活",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_enderman_water", "用水杀死末影人", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("tame_wolf_kill_sheep", "驯服狼并让它杀死10只羊",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_witch_potion", "用溅射药水杀死女巫", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("ride_spider_jockey", "找到并杀死蜘蛛骑士", TaskType.COMPLEX_TASK));

        // === 下界挑战 (10) ===
        register(new TaskDefinition("bridge_lava_lake", "在下界岩浆湖上建造桥梁",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_16_glowstone", "收集16个荧石粉", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_ghast_fireball", "通过反射火球杀死恶魂",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("find_fortress_chest", "找到并掠夺下界要塞箱子",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_10_magma_cream", "收集10个岩浆膏", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("trade_16_gold", "用16个金锭与猪灵交易", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_20_piglin", "杀死20个猪灵或僵尸猪灵", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("strider_cross_lava", "骑炽足兽穿过岩浆海洋", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("brew_fire_resistance", "酿造抗火药水", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_wither_skeleton", "被凋灵骷髅杀死", TaskType.COMPLEX_TASK));

        // === 合成与建筑挑战 (10) ===
        register(new TaskDefinition("craft_full_diamond_armor", "合成一整套钻石盔甲",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_50_high_tower", "建造50格高的塔", TaskType.COMPLEX_TASK));
        register(
                new TaskDefinition("create_auto_farm", "用红石建造自动农场", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("craft_10_paintings", "合成并放置10幅画", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_nether_portal_overworld",
                "在主世界建造2个不同的下界传送门", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("craft_enchanting_table", "合成并放置附魔台并配15个书架",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_infinite_water", "创建5个无限水源", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_house_5_rooms", "建造至少5个房间的房屋",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("craft_100_items", "总共合成100个物品（任何物品）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("make_map_wall", "创建3x3地图墙", TaskType.COMPLEX_TASK));

        // === 食物与农业挑战 (10) ===
        register(new TaskDefinition("breed_20_animals", "繁殖20只动物（任何类型）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("cook_64_meat", "烤熟64块肉（任何类型）", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("grow_100_wheat", "收获100个小麦", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_bee_farm", "创建包含3个蜂箱的养蜂场", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("tame_10_wolves", "驯服10只狼", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_flowers", "收集每种花各一朵", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("make_cake", "合成并放置蛋糕", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("eat_25_foods", "吃25种不同的食物", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("poison_self_5_times", "中毒5次", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("max_saturation", "用金胡萝卜达到最大饱和度",
                TaskType.COMPLEX_TASK));

        // === 交通挑战 (10) ===
        register(new TaskDefinition("travel_1000_blocks", "从重生点向任何方向移动1000格",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("ride_minecart_500", "乘坐矿车行驶500格", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("fly_elytra_1000", "用鞘翅飞行1000格", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("boat_cross_ocean", "乘船穿越海洋生物群系", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("ride_pig_100", "骑猪移动100格", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_ice_road", "建造50格长的冰路", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("horse_jump_5_blocks", "骑马跳过5格高", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("swim_500_blocks", "游泳500格", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_bubble_column", "创建30格高的气泡柱电梯",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("ender_pearl_100", "用末影珍珠移动100格", TaskType.COMPLEX_TASK));

        // === 收集挑战 (10) ===
        register(new TaskDefinition("collect_64_bones", "收集64根骨头", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_32_ender_pearls", "收集32个末影珍珠", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_16_music_discs", "收集任意音乐唱片", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_100_xp_levels", "达到30级经验", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("fill_inventory", "用不同物品完全填满背包",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_64_string", "收集64根线", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_32_gunpowder", "收集32个火药", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_all_dyes", "收集每种颜色的染料各一个", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_10_saddles", "找到并收集2个鞍", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_totem", "找到不死图腾", TaskType.COMPLEX_TASK));

        // === 交易与村民挑战 (10) ===
        register(new TaskDefinition("trade_with_5_villagers", "与5个不同的村民交易",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("max_villager_trade", "将村民交易升到满级（交易直到锁定）",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("cure_zombie_villager", "治愈僵尸村民", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_iron_golem", "建造并生成铁傀儡", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("raid_victory", "击败袭击", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("collect_64_emeralds", "收集64个绿宝石", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("build_villager_breeder", "建造村民繁殖系统",
                TaskType.COMPLEX_TASK));
        register(
                new TaskDefinition("transport_villager_500", "运送村民500格", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("get_hero_village", "获得村庄英雄效果", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("trade_enchanted_book", "从图书管理员处交易附魔书",
                TaskType.COMPLEX_TASK));

        // === 独特/特殊挑战 (10) ===
        register(new TaskDefinition("sleep_100_times", "在床上睡觉10次", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("die_5_different_ways", "以5种不同的方式死亡", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("screenshot_sunset", "在Y=100处观看日落", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("create_snow_golem_army", "创建10个雪傀儡", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("burn_diamond", "将钻石扔进岩浆", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("drown_with_respiration", "戴着水下呼吸III头盔溺水",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("kill_yourself_tnt", "用自己的TNT杀死自己", TaskType.COMPLEX_TASK));
        register(new TaskDefinition("reach_world_border", "向一个方向移动10000格",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("survive_50_hearts_damage", "在不死亡的情况下承受50颗心的伤害",
                TaskType.COMPLEX_TASK));
        register(new TaskDefinition("place_1000_blocks", "放置1000个方块", TaskType.COMPLEX_TASK));
    }

    private void register(TaskDefinition def) {
        registry.put(def.id(), def);
    }

    /**
     * Load tasks from tasks.yml if present. Returns true if any tasks were loaded.
     */
    private boolean loadFromTasksYml() {
        try {
            var tcfg = plugin.getTaskConfigManager();
            if (tcfg == null)
                return false;
            org.bukkit.configuration.file.FileConfiguration cfg = tcfg.getConfig();
            java.util.List<?> list = cfg.getList("tasks");
            if (list == null)
                return false;
            int count = 0;
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m))
                    continue;
                String id = String.valueOf(m.get("id"));
                if (id == null || id.equals("null"))
                    continue;
                String desc = m.containsKey("description") ? String.valueOf(m.get("description")) : id;
                String typeStr = m.containsKey("type") ? String.valueOf(m.get("type")) : "COMPLEX_TASK";
                TaskType type;
                try {
                    type = TaskType.valueOf(typeStr.toUpperCase());
                } catch (Throwable t) {
                    type = TaskType.COMPLEX_TASK;
                }
                java.util.List<String> params = new java.util.ArrayList<>();
                Object p = m.get("params");
                if (p instanceof java.util.List<?> lp)
                    for (Object e : lp)
                        params.add(String.valueOf(e));
                String diffStr = m.containsKey("difficulty") ? String.valueOf(m.get("difficulty")) : "MEDIUM";
                TaskDifficulty diff;
                try {
                    diff = TaskDifficulty.valueOf(diffStr.toUpperCase());
                } catch (Throwable t) {
                    diff = TaskDifficulty.MEDIUM;
                }
                java.util.List<String> cats = new java.util.ArrayList<>();
                Object c = m.get("categories");
                if (c instanceof java.util.List<?> lc)
                    for (Object e : lc)
                        cats.add(String.valueOf(e).toLowerCase());
                boolean enabled = Boolean
                        .parseBoolean(String.valueOf(m.containsKey("enabled") ? m.get("enabled") : "true"));
                register(new TaskDefinition(id, desc, type, params, diff, cats, enabled));
                count++;
            }
            return count > 0;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to load tasks.yml: " + t.getMessage());
            return false;
        }
    }

    /** Return current difficulty filter. */
    public TaskDifficulty getDifficultyFilter() {
        return difficultyFilter;
    }

    /** Set and persist difficulty filter. */
    public void setDifficultyFilter(TaskDifficulty d) {
        if (d == null)
            d = TaskDifficulty.MEDIUM;
        this.difficultyFilter = d;
        try {
            plugin.getConfig().set("task_manager.difficulty", d.name());
            plugin.saveConfig();
        } catch (Throwable ignored) {
        }
    }

    /** Mark progression flags. */
    public void notifyEnteredNether() {
        this.netherReached = true;
    }

    public void notifyEnteredEnd() {
        this.endReached = true;
    }

    public void resetProgressGates() {
        this.netherReached = false;
        this.endReached = false;
    }

    /** Candidate tasks matching enabled + difficulty + gating. */
    public java.util.List<String> getCandidateTaskIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (TaskDefinition d : registry.values()) {
            if (!d.enabled())
                continue;
            if (d.difficulty() != null && d.difficulty().ordinal() != difficultyFilter.ordinal()) {
                // Exact-match filter for now per request (E/M/H buckets)
                if (d.difficulty() != difficultyFilter)
                    continue;
            }
            java.util.List<String> cats = d.categories() != null ? d.categories() : java.util.List.of();
            boolean needsNether = cats.stream().anyMatch(s -> s.equalsIgnoreCase("nether"));
            boolean needsEnd = cats.stream().anyMatch(s -> s.equalsIgnoreCase("end"));
            if (needsEnd && !endReached)
                continue;
            if (needsNether && !netherReached)
                continue;
            out.add(d.id());
        }
        return out;
    }

    public int getCandidateCount() {
        return getCandidateTaskIds().size();
    }

    /** Enable/disable a task and persist to tasks.yml if present. */
    public boolean setTaskEnabled(String id, boolean enabled) {
        TaskDefinition d = registry.get(id);
        if (d == null)
            return false;
        registry.put(id, new TaskDefinition(d.id(), d.description(), d.type(), d.params(), d.difficulty(),
                d.categories(), enabled));
        try {
            var tcfg = plugin.getTaskConfigManager();
            if (tcfg != null) {
                var cfg = tcfg.getConfig();
                java.util.List<?> list = cfg.getList("tasks");
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        Object o = list.get(i);
                        if (o instanceof java.util.Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> mm = (java.util.Map<String, Object>) o;
                            if (id.equals(String.valueOf(mm.get("id")))) {
                                mm.put("enabled", enabled);
                            }
                        }
                    }
                    cfg.set("tasks", list);
                    tcfg.saveConfig();
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Reload only from tasks.yml, preserving defaults if file absent. */
    public void reloadTasksFromFile() {
        plugin.getTaskConfigManager().reloadConfig();
        loadTasks();
    }

    /** Expose a read-only view of task definitions for admin commands. */
    public java.util.Map<String, TaskDefinition> getAllDefinitions() {
        return java.util.Collections.unmodifiableMap(registry);
    }

}
