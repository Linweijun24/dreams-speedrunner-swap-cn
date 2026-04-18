package com.example.speedrunnerswap.commands;

import com.example.speedrunnerswap.SpeedrunnerSwap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SwapCommand implements CommandExecutor, TabCompleter {
    
    private final SpeedrunnerSwap plugin;
    
    public SwapCommand(SpeedrunnerSwap plugin) {
        this.plugin = plugin;
    }

    private String modeName(com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode mode) {
        return switch (mode) {
            case DREAM -> "dream";
            case SAPNAP -> "sapnap";
            case TASK -> "task";
            case TASK_DUEL -> "taskduel";
            case TASK_RACE -> "taskrace";
        };
    }

    private boolean handleInterval(CommandSender sender, String[] rest) {
        if (!sender.hasPermission("speedrunnerswap.admin")) { sender.sendMessage("§c你没有权限执行此命令。"); return true; }
        if (rest.length < 1) { sender.sendMessage("§c用法: /swap interval <秒数>"); return false; }
        try {
            int sec = Integer.parseInt(rest[0]);
            plugin.getConfigManager().setSwapInterval(sec);
            plugin.getGameManager().refreshSwapSchedule();
            sender.sendMessage("§a交换间隔已设置为 §f"+plugin.getConfigManager().getSwapInterval()+"秒");
            return true;
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§c无效的数字: " + rest[0]);
            return false;
        }
    }

    private boolean handleRandomize(CommandSender sender, String[] rest) {
        if (!sender.hasPermission("speedrunnerswap.admin")) { sender.sendMessage("§c你没有权限执行此命令。"); return true; }
        if (rest.length < 1) { sender.sendMessage("§c用法: /swap randomize <on|off>"); return false; }
        String opt = rest[0].toLowerCase();
        boolean val = opt.startsWith("on") || opt.equals("true");
        if (!(opt.equals("on") || opt.equals("off") || opt.equals("true") || opt.equals("false"))) {
            sender.sendMessage("§c用法: /swap randomize <on|off>");
            return false;
        }
        plugin.getConfigManager().setSwapRandomized(val);
        plugin.getGameManager().refreshSwapSchedule();
        sender.sendMessage("§e随机交换: " + (val ? "§a开启" : "§c关闭"));
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        sender.sendMessage("§6§lSpeedrunnerSwap 帮助");
        sender.sendMessage("§e/swap gui §7打开菜单");
        sender.sendMessage("§e/swap start|stop|pause|resume §7控制游戏");
        sender.sendMessage("§e/swap interval <秒数> §7设置基础交换间隔");
        sender.sendMessage("§e/swap randomize <on|off> §7切换随机交换");
        sender.sendMessage("§e/swap mode <dream|sapnap|task|taskduel|taskrace> §7设置模式");
        sender.sendMessage("§7追猎交换模式可以可选地使用共享猎人身体（从GUI/配置中设置）。");
        sender.sendMessage("§7多人任务大师模式是双共享身体的任务变体。");
        sender.sendMessage("§e/swap tasks list §7列出任务难度和启用状态");
        sender.sendMessage("§e/swap tasks enable|disable <id> §7切换任务");
        sender.sendMessage("§e/swap tasks difficulty <easy|medium|hard> §7设置难度池");
        sender.sendMessage("§e/swap tasks reload §7重新加载tasks.yml");
        sender.sendMessage("§e/swap complete reroll confirm §7如果符合条件，使用你的一次性任务重掷");
        return true;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) {
                return handleMainCommand(sender);
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "start":
                    return handleStart(sender);
                case "stop":
                    return handleStop(sender);
                case "pause":
                    return handlePause(sender);
                case "resume":
                    return handleResume(sender);
                case "status":
                    return handleStatus(sender);
                case "creator":
                    return handleCreator(sender);
                case "setrunners":
                    return handleSetRunners(sender, Arrays.copyOfRange(args, 1, args.length));
                case "sethunters":
                    return handleSetHunters(sender, Arrays.copyOfRange(args, 1, args.length));
                case "reload":
                    return handleReload(sender);
                case "gui":
                    return handleMainCommand(sender);
                case "mode":
                    return handleMode(sender, Arrays.copyOfRange(args, 1, args.length));
                case "clearteams":
                    return handleClearTeams(sender);
                case "tasks":
                    return handleTasks(sender, Arrays.copyOfRange(args, 1, args.length));
                case "complete":
                    return handleTaskComplete(sender, Arrays.copyOfRange(args, 1, args.length));
                case "interval":
                    return handleInterval(sender, Arrays.copyOfRange(args, 1, args.length));
                case "randomize":
                    return handleRandomize(sender, Arrays.copyOfRange(args, 1, args.length));
                case "help":
                    return handleHelp(sender);
                default:
                    sender.sendMessage("§c未知子命令。使用 /swap 查看帮助。");
                    return false;
            }
        } catch (Exception e) {
            // Catch any unexpected errors so Bukkit doesn't show the generic message without a stacktrace
            sender.sendMessage("§c执行该命令时发生内部错误。请查看服务器日志获取详细信息。");
            plugin.getLogger().log(Level.SEVERE, "Unhandled exception while executing /swap by " + (sender == null ? "UNKNOWN" : sender.getName()), e);
            return false;
        }
    }

    private boolean handleMode(CommandSender sender, String[] rest) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限更改模式。");
            return false;
        }

        if (rest.length == 0) {
            sender.sendMessage("§e当前模式: §f" + modeName(plugin.getCurrentMode()));
            sender.sendMessage("§7用法: /swap mode <dream|sapnap|task|taskduel|taskrace> [--force]");
            return true;
        }

        boolean force = false;
        String targetArg = null;
        for (String token : rest) {
            if ("--force".equalsIgnoreCase(token) || "-f".equalsIgnoreCase(token) || "force".equalsIgnoreCase(token)) {
                force = true;
            } else if (targetArg == null) {
                targetArg = token;
            }
        }

        if (targetArg == null) {
            sender.sendMessage("§c指定要切换的模式 (dream, sapnap, task, taskduel, taskrace)。");
            return false;
        }

        String mode = targetArg.toLowerCase(Locale.ROOT);

        if ("default".equals(mode)) {
            sender.sendMessage("§e启动默认模式现在在config.yml中设置 (game.default_mode)。");
            return true;
        }

        com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode target = switch (mode) {
            case "dream", "hunters", "manhunt" -> com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.DREAM;
            case "sapnap", "control", "multi", "multirunner", "runners" -> com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.SAPNAP;
            case "task", "taskmaster", "task-manager", "taskmanager" -> com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.TASK;
            case "taskduel", "task_duel", "task-duel", "taskduo", "task_duo", "task-duo", "taskteams",
                    "task_teams", "task-teams" -> com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.TASK_DUEL;
            case "taskrace", "task_race", "task-race", "noswap", "paralleltask", "taskparallel", "task_parallel" ->
                com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.TASK_RACE;
            default -> null;
        };

        if (target == null) {
            sender.sendMessage("§c未知模式: " + mode + "。使用 dream|sapnap|task|taskduel|taskrace。");
            return false;
        }

        if (plugin.getGameManager().isGameRunning() && !force) {
            sender.sendMessage("§c在切换模式前请先停止当前游戏。添加 --force 可立即结束并切换。");
            return false;
        }

        if (force && plugin.getGameManager().isGameRunning()) {
            plugin.getGameManager().stopGame();
        }

        plugin.setCurrentMode(target);

        String confirmation = switch (target) {
            case DREAM -> "§a模式已设置为 §f追猎交换模式§a（速通者 + 猎人）。";
            case SAPNAP -> "§a模式已设置为 §f速通者交换模式§a（多速通者控制）。";
            case TASK -> "§a模式已设置为 §6任务大师§a（秘密目标）。";
            case TASK_DUEL -> "§a模式已设置为 §6多人任务大师模式§a（双共享身体与秘密目标）。";
            case TASK_RACE -> "§a模式已设置为 §6任务竞赛模式§a（无交换秘密目标竞赛）。";
        };
        sender.sendMessage(confirmation);

        if (sender instanceof Player player) {
            plugin.getGuiManager().openMainMenu(player);
        }

        return true;
    }

    private boolean handleCreator(CommandSender sender) {
        // No special permission; anyone can view credits/support
        sender.sendMessage("§6§lSpeedrunner Swap");
        sender.sendMessage("§e作者 §f m u j 4 b");
        sender.sendMessage("§d❤ 捐赠以支持开发");
        sender.sendMessage("§b" + SpeedrunnerSwap.DONATION_URL);
        return true;
    }

    private boolean handleMainCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此命令只能由玩家使用。");
            return false;
        }

        if (!sender.hasPermission("speedrunnerswap.command")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }

        if (plugin.getGuiManager() == null) {
            sender.sendMessage("§c错误: GUI管理器未正确初始化。请向插件开发者报告此问题。");
            plugin.getLogger().log(Level.SEVERE, "GUI Manager is null when trying to open main menu");
            return false;
        }

        try {
            // Open direct gamemode selector - allows access to each gamemode's main menu
            plugin.getGuiManager().openDirectGamemodeSelector((Player) sender);
            return true;
        } catch (Exception e) {
            sender.sendMessage("§c打开GUI时出错: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Error opening GUI for player " + sender.getName(), e);
            return false;
        }
    }
    
    private boolean handleStart(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.command")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }
        
        if (plugin.getGameManager().isGameRunning()) {
            sender.sendMessage("§c游戏已在运行中。");
            return false;
        }
        
        boolean success = plugin.getGameManager().startGame();
        if (success) {
            sender.sendMessage("§a游戏已成功启动。");
        } else {
            // Provide clearer guidance depending on current mode
            var mode = plugin.getCurrentMode();
            if (mode == com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.DREAM) {
                sender.sendMessage("§c启动失败。追猎交换模式需要至少 §e1名速通者§c 和 §e1名猎人§c。");
            } else if (mode == com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.TASK_DUEL) {
                sender.sendMessage(
                        "§c启动失败。多人任务大师模式需要至少 §e1名速通者§c 和 §e1名第二身体玩家§c。");
            } else if (mode == com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.TASK_RACE) {
                sender.sendMessage("§c启动失败。任务竞赛模式需要至少 §e2名速通者§c 且不能有猎人。");
            } else {
                sender.sendMessage("§c启动失败。你必须设置至少 §e1名速通者§c 且不能有猎人。");
            }
        }
        
        return success;
    }
    
    private boolean handleStop(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }
        
        if (!plugin.getGameManager().isGameRunning()) {
            sender.sendMessage("§c游戏未在运行。");
            return false;
        }
        
        plugin.getGameManager().stopGame();
        sender.sendMessage("§a游戏已停止。");
        
        return true;
    }
    
    private boolean handlePause(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }
        plugin.getGameManager().pauseGame();
        sender.sendMessage("§e游戏已暂停。");
        return true;
    }
    
    private boolean handleResume(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }
        plugin.getGameManager().resumeGame();
        sender.sendMessage("§a游戏已恢复。");
        return true;
    }
    
    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }
        
        sender.sendMessage("§6=== SpeedrunnerSwap 状态 ===");
        sender.sendMessage("§e当前模式: §f" + modeName(plugin.getCurrentMode()));
        sender.sendMessage("§e游戏运行中: §f" + plugin.getGameManager().isGameRunning());
        sender.sendMessage("§e游戏已暂停: §f" + plugin.getGameManager().isGamePaused());
        sender.sendMessage("§e会话世界: §f" + (plugin.getGameManager().getSessionWorldName() != null
                ? plugin.getGameManager().getSessionWorldName()
                : "未设置"));
        sender.sendMessage("§e同世界队伍UI: §f" + plugin.getConfigManager().isTeamSelectorSameWorldOnly());
        sender.sendMessage("§e分配限制: §f"
                + plugin.getConfigManager().isAssignmentRestrictedToSessionWorld());
        if (plugin.getCurrentMode() == com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.DREAM) {
            sender.sendMessage("§e共享猎人身体: §f" + plugin.getConfigManager().isSharedHunterControlEnabled());
            sender.sendMessage("§e传统猎人轮换: §f" + plugin.getConfigManager().isHunterSwapEnabled());
        } else if (plugin.isDualBodyTaskMode()) {
            sender.sendMessage("§e多人任务大师模式: §ftrue");
        }
        
        if (plugin.getGameManager().isGameRunning()) {
            if (plugin.usesSharedRunnerControl()) {
                Player activeRunner = plugin.getGameManager().getActiveRunner();
                sender.sendMessage("§e当前速通者: §f" + (activeRunner != null ? activeRunner.getName() : "无"));
                sender.sendMessage("§e距离下次交换: §f" + plugin.getGameManager().getTimeUntilNextSwap() + "秒");
            } else {
                sender.sendMessage("§e模式类型: §f无交换并行速通者");
            }
            
            List<Player> runners = plugin.getGameManager().getRunners();
            List<Player> hunters = plugin.getGameManager().getHunters();

            if (plugin.usesSharedSecondBody()) {
                Player activeHunter = plugin.getGameManager().getActiveHunter();
                sender.sendMessage("§e当前第二身体: §f" + (activeHunter != null ? activeHunter.getName() : "无"));
                sender.sendMessage(
                        "§e距离下次第二身体交换: §f" + plugin.getGameManager().getTimeUntilNextHunterSwap()
                                + "秒");
            } else if (plugin.getCurrentMode() == com.example.speedrunnerswap.SpeedrunnerSwap.SwapMode.DREAM
                    && plugin.getConfigManager().isHunterSwapEnabled()) {
                sender.sendMessage("§e猎人轮换: §f传统全队轮换");
            }
            
            sender.sendMessage("§e速通者: §f" + runners.stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(", ")));

            String secondGroupLabel = plugin.isDualBodyTaskMode() ? "第二身体" : "猎人";
            sender.sendMessage("§e" + secondGroupLabel + ": §f" + hunters.stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(", ")));
        }
        
        return true;
    }

    private org.bukkit.World determineAssignmentReferenceWorld(CommandSender sender, List<Player> players) {
        org.bukkit.World sessionWorld = plugin.getGameManager().getSessionWorld();
        if (sessionWorld != null) {
            return sessionWorld;
        }
        if (sender instanceof Player playerSender) {
            return playerSender.getWorld();
        }
        for (Player player : players) {
            if (player != null && player.getWorld() != null
                    && player.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                return player.getWorld();
            }
        }
        return null;
    }
    
    private boolean handleSetRunners(CommandSender sender, String[] playerNames) {
        if (!sender.hasPermission("speedrunnerswap.command")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }
        
        if (playerNames.length == 0) {
            sender.sendMessage("§c用法: /swap setrunners <玩家1> [玩家2] [玩家3] ...");
            return false;
        }
        
        List<Player> players = new ArrayList<>();
        for (String name : playerNames) {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                players.add(player);
            } else {
                sender.sendMessage("§c找不到玩家: " + name);
            }
        }
        
        if (players.isEmpty()) {
            sender.sendMessage("§c未指定有效玩家。");
            return false;
        }

        org.bukkit.World referenceWorld = determineAssignmentReferenceWorld(sender, players);
        List<Player> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Player player : players) {
            String reason = plugin.getGameManager().getAssignmentRestrictionReason(player,
                    com.example.speedrunnerswap.models.Team.RUNNER, referenceWorld);
            if (reason == null) {
                accepted.add(player);
            } else {
                rejected.add(reason);
            }
        }

        if (accepted.isEmpty()) {
            sender.sendMessage("§c无法分配有效速通者。");
            for (String reason : rejected) {
                sender.sendMessage("§7- §c" + reason);
            }
            return false;
        }

        plugin.getGameManager().establishSessionWorldFromAssignment(accepted.get(0), referenceWorld);
        plugin.getGameManager().setRunners(accepted);
        sender.sendMessage("§a速通者已设置: " + accepted.stream()
                .map(Player::getName)
                .collect(Collectors.joining(", ")));
        if (!rejected.isEmpty()) {
            sender.sendMessage("§e因多世界规则跳过的玩家:");
            for (String reason : rejected) {
                sender.sendMessage("§7- §e" + reason);
            }
        }
        
        return true;
    }
    
    private boolean handleSetHunters(CommandSender sender, String[] playerNames) {
        if (!sender.hasPermission("speedrunnerswap.command")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }
        
        if (playerNames.length == 0) {
            sender.sendMessage("§c用法: /swap sethunters <玩家1> [玩家2] [玩家3] ...");
            return false;
        }
        
        List<Player> players = new ArrayList<>();
        for (String name : playerNames) {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                players.add(player);
            } else {
                sender.sendMessage("§c找不到玩家: " + name);
            }
        }
        
        if (players.isEmpty()) {
            sender.sendMessage("§c未指定有效玩家。");
            return false;
        }

        org.bukkit.World referenceWorld = determineAssignmentReferenceWorld(sender, players);
        List<Player> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Player player : players) {
            String reason = plugin.getGameManager().getAssignmentRestrictionReason(player,
                    com.example.speedrunnerswap.models.Team.HUNTER, referenceWorld);
            if (reason == null) {
                accepted.add(player);
            } else {
                rejected.add(reason);
            }
        }

        if (accepted.isEmpty()) {
            sender.sendMessage("§c无法分配有效猎人。");
            for (String reason : rejected) {
                sender.sendMessage("§7- §c" + reason);
            }
            return false;
        }

        plugin.getGameManager().setHunters(accepted);
        sender.sendMessage("§a猎人已设置: " + accepted.stream()
                .map(Player::getName)
                .collect(Collectors.joining(", ")));
        if (!rejected.isEmpty()) {
            sender.sendMessage("§e因多世界规则跳过的玩家:");
            for (String reason : rejected) {
                sender.sendMessage("§7- §e" + reason);
            }
        }
        
        return true;
    }
    
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }
        
        // Stop the game if it's running
        if (plugin.getGameManager().isGameRunning()) {
            plugin.getGameManager().stopGame();
        }
        
        // Reload the config
        plugin.getConfigManager().loadConfig();
        sender.sendMessage("§a配置已重新加载。");
        
        return true;
    }

    private boolean handleTasks(CommandSender sender, String[] rest) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限管理任务。");
            return false;
        }
        if (rest.length == 0) {
            sender.sendMessage("§e用法: /swap tasks <list|enable <id>|disable <id>|difficulty <easy|medium|hard>|reload|reroll|endwhenoneleft <on|off|toggle>>");
            return true;
        }
        String sub = rest[0].toLowerCase();
        switch (sub) {
            case "list": {
                var tmm = plugin.getTaskManagerMode();
                if (tmm == null) { sender.sendMessage("§c任务管理器未初始化。"); return false; }
                var defs = tmm.getAllDefinitions();
                if (defs.isEmpty()) { sender.sendMessage("§7未定义任务。"); return true; }
                sender.sendMessage("§6任务 (id §7|§f 难度 §7|§f 启用):");
                int shown = 0;
                for (var e : defs.entrySet()) {
                    var d = e.getValue();
                    sender.sendMessage("§e"+d.id()+" §7| §f"+(d.difficulty()!=null?d.difficulty().name():"MEDIUM")+" §7| §f"+(d.enabled()?"是":"否"));
                    if (++shown >= 50) { sender.sendMessage("§7… (仅显示前50个)"); break; }
                }
                sender.sendMessage("§7难度筛选: §f"+tmm.getDifficultyFilter().name()+"§7 | 当前可用: §a"+tmm.getCandidateCount());
                return true;
            }
            case "enable":
            case "disable": {
                if (rest.length < 2) { sender.sendMessage("§c用法: /swap tasks "+sub+" <id>"); return false; }
                String id = rest[1];
                var tmm = plugin.getTaskManagerMode();
                if (tmm == null) { sender.sendMessage("§c任务管理器未初始化。"); return false; }
                boolean ok = tmm.setTaskEnabled(id, sub.equals("enable"));
                if (!ok) { sender.sendMessage("§c未知任务id: "+id); return false; }
                sender.sendMessage("§a任务 '"+id+"' 已" + (sub.equals("enable")?"启用":"禁用") + "。");
                return true;
            }
            case "difficulty": {
                if (rest.length < 2) { sender.sendMessage("§c用法: /swap tasks difficulty <easy|medium|hard>"); return false; }
                String lvl = rest[1].toLowerCase();
                com.example.speedrunnerswap.task.TaskDifficulty d;
                switch (lvl) {
                    case "easy" -> d = com.example.speedrunnerswap.task.TaskDifficulty.EASY;
                    case "hard" -> d = com.example.speedrunnerswap.task.TaskDifficulty.HARD;
                    default -> d = com.example.speedrunnerswap.task.TaskDifficulty.MEDIUM;
                }
                var tmm = plugin.getTaskManagerMode();
                if (tmm == null) { sender.sendMessage("§c任务管理器未初始化。"); return false; }
                tmm.setDifficultyFilter(d);
                sender.sendMessage("§a任务难度筛选已设置为 §f"+d.name());
                return true;
            }
            case "reroll": {
                if (plugin.getGameManager().isGameRunning()) {
                    sender.sendMessage("§c只能在游戏开始前重掷任务。");
                    return false;
                }
                if (!plugin.isTaskCompetitionMode()) {
                    sender.sendMessage("§c请先切换到任务竞赛模式: /swap mode task 或 /swap mode taskrace");
                    return false;
                }
                var tmm = plugin.getTaskManagerMode();
                if (tmm == null) { sender.sendMessage("§c任务管理器未初始化。"); return false; }
                // Build runner list from selected team assignments
                java.util.List<Player> selectedRunners = new java.util.ArrayList<>(plugin.getGameManager().getRunners());
                if (selectedRunners.isEmpty()) {
                    sender.sendMessage("§c未找到已选择的速通者。请先使用队伍选择器。");
                    return false;
                }
                tmm.assignAndAnnounceTasks(selectedRunners);
                sender.sendMessage("§a已为 §f"+selectedRunners.size()+"§a 名选中的速通者重掷任务。");
                return true;
            }
            case "endwhenoneleft": {
                boolean cur = plugin.getConfig().getBoolean("task_manager.end_when_one_left", false);
                if (rest.length >= 2) {
                    String opt = rest[1].toLowerCase();
                    if (opt.equals("on") || opt.equals("true")) cur = true; else if (opt.equals("off") || opt.equals("false")) cur = false; else cur = !cur;
                } else { cur = !cur; }
                plugin.getConfig().set("task_manager.end_when_one_left", cur);
                plugin.saveConfig();
                sender.sendMessage("§e仅剩一名速通者时结束: " + (cur ? "§a开启" : "§c关闭"));
                return true;
            }
            case "reload": {
                var tmm = plugin.getTaskManagerMode();
                if (tmm == null) { sender.sendMessage("§c任务管理器未初始化。"); return false; }
                try { plugin.getTaskConfigManager().reloadConfig(); } catch (Throwable ignored) {}
                tmm.reloadTasksFromFile();
                sender.sendMessage("§a[任务管理器] tasks.yml已重新加载，无需重启！");
                return true;
            }
            default:
                sender.sendMessage("§c未知任务子命令。使用 list|enable|disable|difficulty|reroll|endwhenoneleft|reload");
                return false;
        }
    }

    private boolean handleClearTeams(CommandSender sender) {
        if (!sender.hasPermission("speedrunnerswap.admin")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return false;
        }

        // Stop the game if it's running
        if (plugin.getGameManager().isGameRunning()) {
            plugin.getGameManager().stopGame();
        }

        java.util.LinkedHashSet<Player> affected = new java.util.LinkedHashSet<>();
        affected.addAll(plugin.getGameManager().getRunners());
        affected.addAll(plugin.getGameManager().getHunters());

        plugin.getGameManager().clearAllTeams();
        sender.sendMessage("§a已清除所有队伍（速通者和猎人）。");

        for (Player target : affected) {
            if (target != null && target.isOnline() && target != sender) {
                target.sendMessage("§e你的队伍分配已被 §f" + sender.getName() + " §e清除。");
            }
        }
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // Subcommands (canonical names only)
            List<String> subCommands = Arrays.asList("start", "stop", "pause", "resume", "status", "creator", "setrunners", "sethunters", "reload", "gui", "mode", "clearteams", "tasks", "complete", "interval", "randomize", "help");
            for (String subCommand : subCommands) {
                if (subCommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subCommand);
                }
            }
        } else if (args.length > 1) {
            // Player names for setrunners and sethunters
            if (args[0].equalsIgnoreCase("setrunners") || args[0].equalsIgnoreCase("sethunters")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String name = player.getName();
                    if (name.toLowerCase().startsWith(args[args.length - 1].toLowerCase())) {
                        completions.add(name);
                    }
                }
            } else if (args[0].equalsIgnoreCase("complete") && args.length == 2) {
                if ("confirm".startsWith(args[1].toLowerCase())) {
                    completions.add("confirm");
                }
                if ("reroll".startsWith(args[1].toLowerCase())) {
                    completions.add("reroll");
                }
            } else if (args[0].equalsIgnoreCase("complete") && args.length == 3
                    && args[1].equalsIgnoreCase("reroll")) {
                if ("confirm".startsWith(args[2].toLowerCase())) {
                    completions.add("confirm");
                }
            } else if (args[0].equalsIgnoreCase("mode") && args.length == 2) {
                for (String opt : new String[]{"dream", "sapnap", "task", "taskduel", "taskrace"}) {
                    if (opt.startsWith(args[1].toLowerCase())) completions.add(opt);
                }
                if ("--force".startsWith(args[1].toLowerCase())) {
                    completions.add("--force");
                }
            } else if (args[0].equalsIgnoreCase("mode") && args.length >= 3) {
                String current = args[args.length - 1].toLowerCase();
                if ("--force".startsWith(current)) {
                    completions.add("--force");
                } else if ("-f".startsWith(current)) {
                    completions.add("-f");
                }
            } else if (args[0].equalsIgnoreCase("randomize") && args.length == 2) {
                for (String opt : new String[]{"on","off"}) if (opt.startsWith(args[1].toLowerCase())) completions.add(opt);
            } else if (args[0].equalsIgnoreCase("tasks")) {
                if (args.length == 2) {
                    for (String opt : new String[]{"list","enable","disable","difficulty","reload","reroll","endwhenoneleft"}) if (opt.startsWith(args[1].toLowerCase())) completions.add(opt);
                } else if (args.length == 3 && (args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))) {
                    try {
                        var defs = plugin.getTaskManagerMode().getAllDefinitions();
                        for (String id : defs.keySet()) if (id.toLowerCase().startsWith(args[2].toLowerCase())) completions.add(id);
                    } catch (Throwable ignored) {}
                } else if (args.length == 3 && args[1].equalsIgnoreCase("difficulty")) {
                    for (String lvl : new String[]{"easy","medium","hard"}) if (lvl.startsWith(args[2].toLowerCase())) completions.add(lvl);
                }
            }
        }

        return completions;
    }
    
    private boolean handleTaskComplete(CommandSender sender, String[] rest) {
        // Allow any player to manually complete their task
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能完成任务。");
            return false;
        }
        
        if (!plugin.isTaskCompetitionMode()) {
            sender.sendMessage("§c任务完成仅在任务大师或任务竞赛模式中可用。");
            return false;
        }
        
        var taskMode = plugin.getTaskManagerMode();
        if (taskMode == null) {
            sender.sendMessage("§c任务管理器未初始化。");
            return false;
        }

        boolean gameRunning = plugin.getGameManager().isGameRunning();
        if (!gameRunning && taskMode.getAssignedTask(player) == null) {
            sender.sendMessage("§c你当前没有分配的任务。");
            return false;
        }

        String assignedTask = taskMode.getAssignedTask(player);
        if (assignedTask == null) {
            sender.sendMessage("§c你没有分配的任务。请先加入当前的任务竞赛。");
            return false;
        }
        
        // Get task description for confirmation
        var taskDef = taskMode.getTask(assignedTask);
        String description = taskDef != null ? taskDef.description() : assignedTask;
        
        // Check if player wants to see their task or complete it
        if (rest.length == 0) {
            // Show current task and instructions
            player.sendMessage("§6========== §e§l你的任务 §6==========");
            player.sendMessage("§f" + description);
            player.sendMessage("");
            player.sendMessage("§a§l完成任务:");
            player.sendMessage("§e/swap complete confirm");
            if (taskMode.isTaskRerollEnabled()) {
                player.sendMessage("§e/swap complete reroll confirm");
                player.sendMessage("§7剩余重掷次数: §f" + taskMode.getRemainingRerolls(player));
            }
            player.sendMessage("");
            player.sendMessage("§7当你使用完成命令时，你将赢得游戏。");
            player.sendMessage("§7请仅在真正完成任务后使用。");
            player.sendMessage("§6" + "=".repeat(35));
            return true;
        }
        
        String action = rest[0].toLowerCase();
        if ("reroll".equals(action)) {
            String blocked = taskMode.getRerollUnavailableReason(player);
            if (blocked != null) {
                sender.sendMessage("§c" + blocked);
                return false;
            }
            if (rest.length < 2 || !"confirm".equalsIgnoreCase(rest[1])) {
                sender.sendMessage("§e当前任务: §f" + description);
                sender.sendMessage("§7使用 §e/swap complete reroll confirm §7来消耗重掷次数。");
                sender.sendMessage("§7剩余重掷次数: §f" + taskMode.getRemainingRerolls(player));
                return true;
            }

            var newTask = taskMode.rerollTask(player);
            if (newTask == null) {
                sender.sendMessage("§c你当前没有可用的备用任务。");
                return false;
            }
            sender.sendMessage("§a你的任务已重掷。");
            sender.sendMessage("§e新任务: §f" + newTask.description());
            sender.sendMessage("§7剩余重掷次数: §f" + taskMode.getRemainingRerolls(player));
            return true;
        }

        if (!"confirm".equals(action)) {
            sender.sendMessage("§c使用 '/swap complete confirm' 完成任务，'/swap complete reroll confirm' 重掷任务，或 '/swap complete' 查看任务。");
            return false;
        }

        if (!gameRunning) {
            sender.sendMessage("§c任务完成仅在游戏进行中时可用。");
            return false;
        }
        
        // Confirm completion
        player.sendMessage("§a§l恭喜！你完成了任务:");
        player.sendMessage("§f" + description);
        
        // Complete the task
        taskMode.complete(player);
        
        return true;
    }
}
