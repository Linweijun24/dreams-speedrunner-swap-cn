package com.example.speedrunnerswap.gui;

import com.example.speedrunnerswap.SpeedrunnerSwap;
import com.example.speedrunnerswap.config.ConfigManager;
import com.example.speedrunnerswap.game.GameManager;
import com.example.speedrunnerswap.models.Team;
import com.example.speedrunnerswap.task.TaskDefinition;
import com.example.speedrunnerswap.task.TaskManagerMode;
import com.example.speedrunnerswap.task.TaskDifficulty;
import com.example.speedrunnerswap.utils.GuiCompat;
import com.example.speedrunnerswap.utils.Msg;
import com.example.speedrunnerswap.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Rebuilt GUI system that exposes the entire plugin configuration and runtime
 * controls.
 * Every screen is described using Menu definitions to keep navigation
 * predictable.
 */
public final class GuiManager implements Listener {

    private static final ItemStack FILLER_PRIMARY;
    private static final ItemStack FILLER_ACCENT;
    private static final ItemStack FILLER_BORDER;
    private static final NamespacedKey BUTTON_KEY;
    private static final List<String> PARTICLE_TYPES = List.of("DUST", "END_ROD", "FLAME", "CRIT", "HEART", "CLOUD",
            "SMOKE");

    static {
        FILLER_PRIMARY = pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        FILLER_ACCENT = pane(Material.WHITE_STAINED_GLASS_PANE);
        FILLER_BORDER = pane(Material.BLUE_STAINED_GLASS_PANE);
        BUTTON_KEY = new NamespacedKey(SpeedrunnerSwap.getInstance(), "menu_button");
    }

    private static ItemStack pane(Material material) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        GuiCompat.setDisplayName(meta, " ");
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        pane.setItemMeta(meta);
        return pane;
    }

    private final SpeedrunnerSwap plugin;
    private final Map<MenuKey, MenuBuilder> builders = new EnumMap<>(MenuKey.class);
    private final Map<UUID, MenuSession> sessions = new HashMap<>();
    private final Map<UUID, Deque<MenuRequest>> history = new HashMap<>();
    private final Map<UUID, Team> teamFocus = new HashMap<>();
    private final Map<UUID, StatsParent> statsParents = new HashMap<>();

    public GuiManager(SpeedrunnerSwap plugin) {
        this.plugin = plugin;
        registerBuilders();
    }

    // -----------------------------------------------------------------
    // Public entry points

    public void openMainMenu(Player player) {
        open(player, MenuKey.MAIN, null, false);
    }

    public void openDirectGamemodeSelector(Player player) {
        if (player == null)
            return;
        resetNavigation(player);
        open(player, MenuKey.MODE_SELECT_DIRECT, null, false);
    }

    public void openModeSelector(Player player) {
        open(player, MenuKey.MODE_SELECT, null, false);
    }

    public void openTeamSelector(Player player) {
        open(player, MenuKey.TEAM_MANAGEMENT, null, false);
    }

    public void openSettingsMenu(Player player) {
        open(player, MenuKey.SETTINGS_HOME, null, false);
    }

    public void openPowerUpsMenu(Player player) {
        open(player, MenuKey.POWERUPS_ROOT, null, false);
    }

    public void openDangerousBlocksMenu(Player player) {
        open(player, MenuKey.DANGEROUS_BLOCKS, null, false);
    }

    public void openTaskManagerMenu(Player player) {
        open(player, MenuKey.TASK_HOME, null, false);
    }

    public void openStatisticsMenu(Player player, StatsParent parent) {
        statsParents.put(player.getUniqueId(), parent);
        open(player, MenuKey.STATS_ROOT, null, false);
    }

    public void openStatisticsMenu(Player player) {
        openStatisticsMenu(player, StatsParent.SETTINGS);
    }

    // -----------------------------------------------------------------
    // Menu engine

    private void open(Player player, MenuKey key, Object data, boolean replaceHistory) {
        if (player == null)
            return;
        MenuBuilder builder = builders.get(key);
        if (builder == null) {
            player.closeInventory();
            player.sendMessage("§c菜单未实现：" + key.name());
            return;
        }

        MenuRequest request = new MenuRequest(key, data);
        MenuContext context = new MenuContext(this, player, request);
        MenuScreen screen = builder.build(context);

        Inventory inventory = GuiCompat.createInventory(null, screen.size(), screen.title());
        fill(inventory, screen, context);

        sessions.put(player.getUniqueId(), new MenuSession(request, screen, inventory));

        Deque<MenuRequest> stack = history.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        if (replaceHistory && !stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty() || !stack.peek().equals(request)) {
            stack.push(request);
        }

        player.openInventory(inventory);
    }

    void reopen(Player player) {
        MenuSession session = sessions.get(player.getUniqueId());
        if (session == null)
            return;
        open(player, session.request.key(), session.request.data(), true);
    }

    void open(Player player, MenuKey key) {
        open(player, key, null, false);
    }

    void open(Player player, MenuKey key, Object data) {
        open(player, key, data, false);
    }

    void openPrevious(Player player) {
        Deque<MenuRequest> stack = history.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.closeInventory();
            return;
        }
        stack.pop(); // current
        if (stack.isEmpty()) {
            player.closeInventory();
            return;
        }
        MenuRequest previous = stack.peek();
        open(player, previous.key(), previous.data(), true);
    }

    private void fill(Inventory inventory, MenuScreen screen, MenuContext context) {
        int rows = inventory.getSize() / 9;
        for (int i = 0; i < inventory.getSize(); i++) {
            int row = i / 9;
            int col = i % 9;
            ItemStack filler;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                filler = FILLER_BORDER.clone();
            } else {
                boolean usePrimary = ((row + col) % 2) == 0;
                filler = (usePrimary ? FILLER_PRIMARY : FILLER_ACCENT).clone();
            }
            inventory.setItem(i, filler);
        }
        for (MenuItem item : screen.items()) {
            if (item.slot() < 0 || item.slot() >= inventory.getSize())
                continue;
            ItemStack icon = item.icon().apply(context);
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(BUTTON_KEY, PersistentDataType.STRING, item.id());
            icon.setItemMeta(meta);
            inventory.setItem(item.slot(), icon);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        MenuSession session = sessions.get(player.getUniqueId());
        if (session == null)
            return;
        if (!Objects.equals(event.getView().getTopInventory(), session.inventory()))
            return;

        event.setCancelled(true);
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR)
            return;
        ItemMeta meta = current.getItemMeta();
        if (meta == null)
            return;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String id = container.get(BUTTON_KEY, PersistentDataType.STRING);
        if (id == null)
            return;

        MenuItem item = session.screen.button(id);
        if (item == null || item.action() == null)
            return;

        MenuClickContext ctx = new MenuClickContext(this, player, session.request, event.isShiftClick(),
                event.getClick());
        item.action().accept(ctx);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        MenuSession session = sessions.get(player.getUniqueId());
        if (session == null)
            return;
        if (Objects.equals(event.getView().getTopInventory(), session.inventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;
        MenuSession session = sessions.get(player.getUniqueId());
        if (session != null && Objects.equals(session.inventory(), event.getInventory())) {
            sessions.remove(player.getUniqueId());
        }
    }

    // -----------------------------------------------------------------
    // Menu registrations

    private void registerBuilders() {
        builders.put(MenuKey.MAIN, ctx -> buildMainMenu(ctx));
        builders.put(MenuKey.MODE_SELECT, ctx -> buildModeSelect(ctx, false));
        builders.put(MenuKey.MODE_SELECT_DIRECT, ctx -> buildModeSelect(ctx, true));
        builders.put(MenuKey.TEAM_MANAGEMENT, this::buildTeamMenu);
        builders.put(MenuKey.SETTINGS_HOME, this::buildSettingsHome);
        builders.put(MenuKey.SETTINGS_SWAP, this::buildSwapSettings);
        builders.put(MenuKey.SETTINGS_SAFETY, this::buildSafetySettings);
        builders.put(MenuKey.SETTINGS_HUNTER, this::buildHunterSettings);
        builders.put(MenuKey.POWERUPS_ROOT, this::buildPowerUpsRoot);
        builders.put(MenuKey.POWERUPS_EFFECTS, this::buildPowerUpEffects);
        builders.put(MenuKey.POWERUPS_DURATION, this::buildPowerUpDurations);
        builders.put(MenuKey.DANGEROUS_BLOCKS, this::buildDangerousBlocks);
        builders.put(MenuKey.SETTINGS_WORLD_BORDER, this::buildWorldBorder);
        builders.put(MenuKey.SETTINGS_BOUNTY, this::buildBounty);
        builders.put(MenuKey.SETTINGS_LAST_STAND, this::buildLastStand);
        builders.put(MenuKey.SETTINGS_SUDDEN_DEATH, this::buildSuddenDeath);
        builders.put(MenuKey.STATS_ROOT, this::buildStatsRoot);
        builders.put(MenuKey.STATS_ADVANCED, this::buildStatsAdvanced);
        builders.put(MenuKey.SETTINGS_TASK, this::buildTaskSettings);
        builders.put(MenuKey.TASK_HOME, this::buildTaskHome);
        builders.put(MenuKey.TASK_CUSTOM, this::buildTaskCustom);
        builders.put(MenuKey.TASK_POOL, this::buildTaskPool);
        builders.put(MenuKey.TASK_ASSIGNMENTS, this::buildTaskAssignments);
        builders.put(MenuKey.TASK_RUNNERS, this::buildTaskRunners);
        builders.put(MenuKey.TASK_ADVANCED, this::buildTaskAdvanced);
        builders.put(MenuKey.SETTINGS_MULTIWORLD, this::buildMultiworldSettings);
        builders.put(MenuKey.SETTINGS_VOICE_CHAT, this::buildVoiceChat);
        builders.put(MenuKey.SETTINGS_BROADCAST, this::buildBroadcast);
        builders.put(MenuKey.SETTINGS_END_MESSAGES, this::buildEndGameMessages);
        builders.put(MenuKey.SETTINGS_UI, this::buildUiSettings);
        builders.put(MenuKey.KIT_MANAGER, this::buildKitManager);
    }

    // -----------------------------------------------------------------
    // Menu builders

    private void resetNavigation(Player player) {
        UUID id = player.getUniqueId();
        sessions.remove(id);
        history.remove(id);
    }

    private MenuScreen buildMainMenu(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        GameManager gm = plugin.getGameManager();
        ConfigManager cfg = plugin.getConfigManager();
        boolean running = gm.isGameRunning();
        boolean paused = gm.isGamePaused();
        SpeedrunnerSwap.SwapMode mode = plugin.getCurrentMode();

        items.add(backButton(0, "§7§l返回", null, null, null));

        List<String> statusLore = new ArrayList<>();
        statusLore.add("§7模式：§f" + modeDisplayName(mode));
        statusLore.add("§7运行中：" + (running ? "§a是" : "§c否"));
        statusLore.add("§7已暂停：" + (paused ? "§e是" : "§c否"));
        statusLore.add("§7会话世界：§f" + Optional.ofNullable(gm.getSessionWorldName()).orElse("未设置"));
        statusLore.add("§7速通者：§b" + gm.getRunners().size());
        if (mode == SpeedrunnerSwap.SwapMode.DREAM) {
            statusLore.add("§7猎人：§c" + gm.getHunters().size());
        } else if (mode == SpeedrunnerSwap.SwapMode.TASK_DUEL) {
            statusLore.add("§7第二具身体：§d" + gm.getHunters().size());
        } else {
            statusLore.add("§7猎人：§8此模式不使用");
        }
        statusLore.add("§7仅分配同世界：" + (cfg.isTeamSelectorSameWorldOnly() ? "§a是" : "§c否"));
        statusLore.add("§7限制到会话世界："
                + (cfg.isAssignmentRestrictedToSessionWorld() ? "§a是" : "§c否"));
        items.add(simpleItem(4, () -> icon(Material.CLOCK, "§6§l游戏状态", statusLore)));

        if (!running) {
            items.add(clickItem(10, () -> icon(Material.LIME_CONCRETE, "§a§l开始游戏",
                    plugin.usesSharedRunnerControl()
                            ? List.of("§7交换间隔：§f" + cfg.getSwapInterval() + "秒")
                            : List.of("§7此模式没有定期交换")), ctxClick -> {
                        if (gm.startGame()) {
                            Msg.send(ctxClick.player(), "§a游戏已开始！");
                        } else {
                            Msg.send(ctxClick.player(), "§c无法开始。请检查团队分配。");
                        }
                        ctxClick.reopen();
                    }));
        } else {
            items.add(clickItem(10,
                    () -> icon(Material.RED_CONCRETE, "§c§l停止游戏", List.of("§7结束当前游戏")), ctxClick -> {
                        gm.stopGame();
                        Msg.send(ctxClick.player(), "§c游戏已停止。");
                        ctxClick.reopen();
                    }));
            if (paused) {
                items.add(clickItem(12, () -> icon(Material.ORANGE_CONCRETE, "§a§l恢复",
                        List.of(plugin.usesSharedRunnerControl() ? "§7恢复交换" : "§7恢复当前比赛")),
                        ctxClick -> {
                            gm.resumeGame();
                            ctxClick.reopen();
                        }));
            } else {
                items.add(clickItem(12, () -> icon(Material.YELLOW_CONCRETE, "§e§l暂停",
                        List.of(plugin.usesSharedRunnerControl() ? "§7暂停交换" : "§7暂停当前比赛")),
                        ctxClick -> {
                            gm.pauseGame();
                            ctxClick.reopen();
                        }));
            }
            items.add(clickItem(14,
                    () -> icon(Material.NETHER_STAR,
                            plugin.usesSharedRunnerControl() ? "§e§l强制交换" : "§7§l此模式无交换",
                            plugin.usesSharedRunnerControl()
                                    ? List.of("§7触发立即交换")
                                    : List.of("§7任务竞赛模式让所有速通者同时活跃")),
                    ctxClick -> {
                        if (!plugin.usesSharedRunnerControl()) {
                            Msg.send(ctxClick.player(), "§e此模式不使用定期交换。");
                            return;
                        }
                        gm.triggerImmediateSwap();
                        Msg.send(ctxClick.player(), "§e交换已触发。");
                    }));
        }

        items.add(clickItem(18, () -> icon(Material.NETHER_STAR, "§d§l关于 muj4b",
                List.of(
                        "§7点击显示支持信息",
                        "§7并分享捐赠链接。")),
                ctxClick -> {
                    plugin.getGameManager().sendDonationMessage(ctxClick.player());
                    Msg.send(ctxClick.player(), "§d感谢支持 muj4b！");
                }));

        items.add(clickItem(20,
                () -> icon(Material.PLAYER_HEAD, "§b§l团队管理", List.of("§7分配速通者和猎人")),
                ctxClick -> open(ctxClick.player(), MenuKey.TEAM_MANAGEMENT, null, false)));
        items.add(
                clickItem(21, () -> icon(Material.ENDER_EYE, "§d§l选择模式", List.of("§7切换追猎交换/速通者交换/任务大师")),
                        ctxClick -> open(ctxClick.player(), MenuKey.MODE_SELECT, null, false)));
        items.add(clickItem(22, () -> icon(Material.COMPARATOR, "§6§l设置", List.of("§7配置每个机制")),
                ctxClick -> open(ctxClick.player(), MenuKey.SETTINGS_HOME, null, false)));
        items.add(clickItem(23, () -> icon(Material.BOOK, "§b§l统计", List.of("§7调整统计跟踪")),
                ctxClick -> openStatisticsMenu(ctxClick.player(), StatsParent.MAIN)));
        items.add(clickItem(24, () -> icon(Material.TARGET, "§6§l任务管理器", List.of("§7管理秘密任务")),
                ctxClick -> open(ctxClick.player(), MenuKey.TASK_HOME, null, false)));

        items.add(clickItem(30, () -> icon(Material.POTION, "§d§l增益效果", List.of("§7配置交换效果")),
                ctxClick -> open(ctxClick.player(), MenuKey.POWERUPS_ROOT, null, false)));
        items.add(clickItem(31, () -> icon(Material.BARRIER, "§c§l危险方块", List.of("§7安全交换黑名单")),
                ctxClick -> open(ctxClick.player(), MenuKey.DANGEROUS_BLOCKS, null, false)));
        items.add(clickItem(32, () -> icon(Material.GOLD_INGOT, "§6§l赏金系统", List.of("§7猎人奖励")),
                ctxClick -> open(ctxClick.player(), MenuKey.SETTINGS_BOUNTY, null, false)));
        items.add(clickItem(33, () -> icon(Material.DRAGON_HEAD, "§4§l突然死亡", List.of("§7终局对决")),
                ctxClick -> open(ctxClick.player(), MenuKey.SETTINGS_SUDDEN_DEATH, null, false)));

        if (mode == SpeedrunnerSwap.SwapMode.SAPNAP) {
            items.add(
                    clickItem(40, () -> icon(Material.CLOCK, "§b§l队列洗牌", List.of("§7随机化速通者顺序")),
                            ctxClick -> {
                                if (plugin.getGameManager().shuffleQueue()) {
                                    Msg.send(ctxClick.player(), "§a速通者队列已洗牌。");
                                } else {
                                    Msg.send(ctxClick.player(), "§c没有足够的速通者来洗牌。");
                                }
                            }));
        }

        return new MenuScreen(plugin.getConfigManager().getGuiMainMenuTitle(), 54, items);
    }

    private MenuScreen buildModeSelect(MenuContext ctx, boolean direct) {
        List<MenuItem> items = new ArrayList<>();
        SpeedrunnerSwap.SwapMode current = plugin.getCurrentMode();

        if (direct) {
            items.add(simpleItem(4, () -> icon(Material.NETHER_STAR, "§e§l欢迎使用速通者交换",
                    List.of("§7选择你想运行的挑战", "§7并直接进入设置。"))));
            items.add(modeItem(10, SpeedrunnerSwap.SwapMode.DREAM, true, current));
            items.add(modeItem(12, SpeedrunnerSwap.SwapMode.SAPNAP, true, current));
            items.add(modeItem(14, SpeedrunnerSwap.SwapMode.TASK, true, current));
            items.add(modeItem(16, SpeedrunnerSwap.SwapMode.TASK_DUEL, true, current));
            items.add(modeItem(18, SpeedrunnerSwap.SwapMode.TASK_RACE, true, current));
            items.add(simpleItem(22, () -> icon(Material.MAP, "§b§l当前模式",
                    List.of("§7当前：§f" + modeDisplayName(current), "", "§7选择另一个图标来切换。"))));
            items.add(clickItem(29, () -> icon(Material.PLAYER_HEAD, "§a§l团队管理器",
                    List.of("§7分配速通者和猎人")),
                    context -> open(context.player(), MenuKey.TEAM_MANAGEMENT, null, false)));
            items.add(clickItem(31, () -> icon(Material.EMERALD, "§a§l打开控制中心",
                    List.of("§7直接进入主控制面板")),
                    context -> open(context.player(), MenuKey.MAIN, null, false)));
            items.add(clickItem(33, () -> icon(Material.COMPARATOR, "§6§l快速设置",
                    List.of("§7立即调整核心机制")),
                    context -> open(context.player(), MenuKey.SETTINGS_HOME, null, false)));
            items.add(backButton(35, "§7§l返回", null, null, null));
        } else {
            items.add(modeItem(10, SpeedrunnerSwap.SwapMode.DREAM, false, current));
            items.add(modeItem(12, SpeedrunnerSwap.SwapMode.SAPNAP, false, current));
            items.add(modeItem(14, SpeedrunnerSwap.SwapMode.TASK, false, current));
            items.add(modeItem(16, SpeedrunnerSwap.SwapMode.TASK_DUEL, false, current));
            items.add(modeItem(18, SpeedrunnerSwap.SwapMode.TASK_RACE, false, current));
            items.add(backButton(22, "§7§l返回", MenuKey.MAIN, null,
                player -> openPrevious(player)));
        }

        int size = direct ? 36 : 27;
        String title = direct ? "§9§l速通者交换中心" : "§6§l模式选择器";
        return new MenuScreen(title, size, items);
    }

    private MenuItem modeItem(int slot, SpeedrunnerSwap.SwapMode mode, boolean direct,
            SpeedrunnerSwap.SwapMode current) {
        boolean selected = mode == current;
        boolean isDefault = plugin.getConfigManager().getDefaultMode() == mode;
        Material mat = switch (mode) {
            case DREAM -> Material.DIAMOND_SWORD;
            case SAPNAP -> Material.DIAMOND_BOOTS;
            case TASK -> Material.TARGET;
            case TASK_DUEL -> Material.MACE;
            case TASK_RACE -> Material.RECOVERY_COMPASS;
        };
        List<String> lore = new ArrayList<>();
        lore.add("§8────────────────");
        switch (mode) {
            case DREAM -> lore.addAll(List.of("§e§l速通者 vs 猎人", "§7经典追逐体验"));
            case SAPNAP -> lore.addAll(List.of("§b§l多速通者控制", "§7共享一个身体合作"));
            case TASK -> lore.addAll(List.of("§6§l任务大师", "§7秘密目标和欺骗"));
            case TASK_DUEL -> lore.addAll(List.of("§6§l多人任务大师模式", "§7两具共享身体互相破坏"));
            case TASK_RACE -> lore.addAll(List.of("§6§l任务竞赛模式", "§7并行无交换目标竞赛"));
        }
        if (direct) {
            lore.add("");
            switch (mode) {
                case DREAM -> lore.addAll(List.of("§7推荐：§f3+ 玩家", "§7(1 速通者, 2+ 猎人)"));
                case SAPNAP -> lore.addAll(List.of("§7推荐：§f2-4 玩家", "§7适合合作速通"));
                case TASK -> lore.addAll(List.of("§7推荐：§f3+ 玩家", "§7战略混乱"));
                case TASK_DUEL -> lore.addAll(List.of("§7推荐：§f4+ 玩家", "§72 具共享身体同时活跃"));
                case TASK_RACE -> lore.addAll(List.of("§7推荐：§f2+ 玩家", "§7所有人同时游玩"));
            }
        }
        lore.add("");
        lore.add(selected ? "§a当前激活" : "§e点击切换");
        lore.add(isDefault ? "§b服务器默认模式" : "§7Shift-点击保存为默认");

        return clickItem(slot, () -> {
            String prefix = selected ? "§a§l" : isDefault ? "§b§l" : "§e§l";
            ItemStack icon = icon(mat, prefix + switch (mode) {
                case DREAM -> "追猎交换模式";
                case SAPNAP -> "速通者交换模式";
                case TASK -> "任务大师";
                case TASK_DUEL -> "多人任务大师模式";
                case TASK_RACE -> "任务竞赛模式";
            }, lore);
            if (selected || isDefault) {
                ItemMeta meta = icon.getItemMeta();
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                icon.setItemMeta(meta);
            }
            return icon;
        }, ctx -> {
            if (ctx.shift()) {
                plugin.getConfigManager().setDefaultMode(mode);
                Msg.send(ctx.player(), "§e启动模式已设置为 §f" + modeDisplayName(mode) + "§e。");
                ctx.reopen();
                return;
            }
            if (selected) {
                Msg.send(ctx.player(), "§e已在使用该模式。");
                return;
            }
            if (plugin.getGameManager().isGameRunning()) {
                Msg.send(ctx.player(), "§c切换模式前请先停止当前游戏。");
                return;
            }
            plugin.setCurrentMode(mode);
            Msg.send(ctx.player(), "§a已切换到 §f" + modeDisplayName(mode) + "§a 模式。");
            if (direct) {
                open(ctx.player(), MenuKey.MAIN, null, false);
            } else {
                ctx.reopen();
            }
        });
    }

    private String modeDisplayName(SpeedrunnerSwap.SwapMode mode) {
        return switch (mode) {
            case DREAM -> "追猎交换模式";
            case SAPNAP -> "速通者交换模式";
            case TASK -> "任务大师";
            case TASK_DUEL -> "多人任务大师模式";
            case TASK_RACE -> "任务竞赛模式";
        };
    }

    private MenuScreen buildTeamMenu(MenuContext ctx) {
        Player viewer = ctx.player();
        GameManager gm = plugin.getGameManager();
        Team initialFocus = teamFocus.computeIfAbsent(viewer.getUniqueId(), uuid -> Team.RUNNER);

        boolean huntersAvailable = plugin.getCurrentMode() == SpeedrunnerSwap.SwapMode.DREAM
                || plugin.isDualBodyTaskMode();
        boolean taskDuelMode = plugin.isDualBodyTaskMode();
        String secondBodyLabel = taskDuelMode ? "第二具身体" : "猎人";
        String secondBodyPlural = taskDuelMode ? "第二具身体" : "猎人";
        if (!huntersAvailable && initialFocus == Team.HUNTER) {
            initialFocus = Team.RUNNER;
            teamFocus.put(viewer.getUniqueId(), Team.RUNNER);
        }
        final Team focus = initialFocus;

        List<MenuItem> items = new ArrayList<>();

        items.add(backButton(0, "§7§l返回", MenuKey.MAIN, null, this::openMainMenu));

        items.add(clickItem(2, () -> icon(Material.DIAMOND_BOOTS,
                focus == Team.RUNNER ? "§a§l正在分配速通者" : "§b§l分配速通者",
                List.of("§7点击设置焦点")), ctxClick -> {
                    teamFocus.put(ctxClick.player().getUniqueId(), Team.RUNNER);
                    ctxClick.reopen();
                }));

        List<String> instructionLore = new ArrayList<>();
        instructionLore.add("§71. 选择速通者/" + secondBodyPlural + " 焦点");
        instructionLore.add("§72. 点击玩家头像进行分配");
        instructionLore.add("§73. Shift-点击移除");
        if (!huntersAvailable) {
            instructionLore.add("§c此模式仅使用速通者。");
            instructionLore.add("§7分配 " + secondBodyPlural + " 已禁用。");
        } else if (taskDuelMode) {
            instructionLore.add("§7多人任务大师模式使用速通者作为身体A，猎人作为身体B。");
        }
        if (plugin.getConfigManager().isAssignmentRestrictedToSessionWorld()) {
            instructionLore.add("§7分配世界：§f"
                    + Optional.ofNullable(gm.getSessionWorldName()).orElse(ctx.player().getWorld().getName()));
        }
        if (plugin.getConfigManager().isTeamSelectorSameWorldOnly()) {
            instructionLore.add("§b世界过滤：§f" + ctx.player().getWorld().getName());
        } else {
            instructionLore.add("§7世界过滤：§f所有在线玩家");
        }
        items.add(simpleItem(4, () -> icon(Material.BOOK, "§e§l说明", instructionLore)));

        items.add(clickItem(6, () -> icon(Material.IRON_SWORD,
                huntersAvailable
                        ? (focus == Team.HUNTER ? "§a§l正在分配 " + secondBodyLabel
                                : "§c§l分配 " + secondBodyLabel)
                        : "§7§l" + secondBodyLabel + " 已禁用",
                huntersAvailable
                        ? List.of("§7点击设置焦点")
                        : List.of(taskDuelMode ? "§7仅多人任务大师模式" : "§7仅追猎交换模式")),
                ctxClick -> {
                    if (!huntersAvailable) {
                        Msg.send(ctxClick.player(),
                                "§e" + secondBodyLabel + " 仅在追猎交换模式或多人任务大师模式中可用。");
                        return;
                    }
                    teamFocus.put(ctxClick.player().getUniqueId(), Team.HUNTER);
                    ctxClick.reopen();
                }));

        items.add(clickItem(8, () -> icon(Material.BARRIER, "§c§l清除所有", List.of("§7移除所有分配")),
                ctxClick -> {
                    Set<Player> affected = new HashSet<>();
                    affected.addAll(gm.getRunners());
                    affected.addAll(gm.getHunters());
                    gm.clearAllTeams();
                    Msg.send(ctxClick.player(), "§c已清除所有团队。");
                    for (Player p : affected) {
                        if (p != null && p.isOnline() && p != ctxClick.player()) {
                            Msg.send(p, "§e你的团队分配已被 §f" + ctxClick.player().getName() + " §e清除");
                        }
                    }
                    ctxClick.reopen();
                }));

        List<Player> candidates = getTeamSelectionCandidates(ctx.player());
        int slot = 9;
        for (Player online : candidates) {
            if (slot >= 54)
                break;
            Team assigned = gm.isRunner(online) ? Team.RUNNER : gm.isHunter(online) ? Team.HUNTER : Team.NONE;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(online);
            String prefix = switch (assigned) {
                case RUNNER -> "§b";
                case HUNTER -> "§c";
                case NONE -> "§7";
            };
            GuiCompat.setDisplayName(meta, prefix + online.getName());
            Team currentFocus = huntersAvailable ? focus : Team.RUNNER;
            List<String> lore = new ArrayList<>();
            lore.add("§7团队：" + switch (assigned) {
                case RUNNER -> "§b速通者";
                case HUNTER -> taskDuelMode ? "§d第二具身体" : "§c猎人";
                case NONE -> "§7未分配";
            });
            lore.add("§7世界：§f" + online.getWorld().getName());
            lore.add("§7焦点：" + (currentFocus == Team.RUNNER ? "§b速通者" : "§d" + secondBodyLabel));
            lore.add("§7点击分配，Shift-点击清除");
            GuiCompat.setLore(meta, lore);
            head.setItemMeta(meta);

            items.add(clickItem(slot, () -> head, ctxClick -> {
                Team targetTeam = ctxClick.shift() ? Team.NONE
                        : teamFocus.getOrDefault(ctxClick.player().getUniqueId(), Team.RUNNER);
                if (!huntersAvailable && targetTeam == Team.HUNTER) {
                    teamFocus.put(ctxClick.player().getUniqueId(), Team.RUNNER);
                    Msg.send(ctxClick.player(),
                            "§e仅在追猎交换模式或多人任务大师模式中分配" + secondBodyPlural + "。");
                    ctxClick.reopen();
                    return;
                }
                if (targetTeam == Team.HUNTER && !huntersAvailable) {
                    Msg.send(ctxClick.player(),
                            "§e仅在追猎交换模式或多人任务大师模式中分配" + secondBodyPlural + "。");
                    return;
                }
                World referenceWorld = gm.getSessionWorld();
                if (referenceWorld == null) {
                    referenceWorld = ctxClick.player().getWorld();
                }
                String reason = gm.getAssignmentRestrictionReason(online, targetTeam, referenceWorld);
                if (reason != null) {
                    Msg.send(ctxClick.player(), "§c" + reason);
                    return;
                }
                boolean changed = gm.assignPlayerToTeam(online, targetTeam, referenceWorld);
                if (!changed) {
                    Msg.send(ctxClick.player(), "§e对 §f" + online.getName() + " §e没有变化");
                } else if (targetTeam == Team.NONE) {
                    Msg.send(ctxClick.player(), "§e已将 §f" + online.getName() + " §e从团队中移除。");
                    if (online != ctxClick.player())
                        Msg.send(online, "§e你已被 §f" + ctxClick.player().getName() + " §e从所有团队中移除");
                } else {
                    String label = targetTeam == Team.RUNNER ? "§b速通者"
                            : (taskDuelMode ? "§d第二具身体" : "§c猎人");
                    Msg.send(ctxClick.player(), "§a已将 §f" + online.getName() + " §a加入 " + label + "§a。");
                    if (online != ctxClick.player())
                        Msg.send(online, "§e你已被 §f" + ctxClick.player().getName() + " §e分配到 " + label);
                }
                ctxClick.reopen();
            }));

            slot++;
            if ((slot + 1) % 9 == 0)
                slot += 2;
        }

        return new MenuScreen(plugin.getConfigManager().getGuiTeamSelectorTitle(), 54, items);
    }

    private MenuScreen buildSettingsHome(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.MAIN, null, this::openMainMenu));

        items.add(navigateItem(10, Material.CLOCK, "§e§l交换与计时", MenuKey.SETTINGS_SWAP,
                "§7间隔、随机性、猎人交换"));
        items.add(navigateItem(11, Material.SHIELD, "§c§l安全与冻结", MenuKey.SETTINGS_SAFETY,
                "§7安全交换、冻结模式、睡眠"));
        items.add(navigateItem(12, Material.COMPASS, "§c§l猎人工具", MenuKey.SETTINGS_HUNTER,
                "§7追踪器、指南针干扰"));
        items.add(navigateItem(13, Material.POTION, "§d§l增益效果", MenuKey.POWERUPS_ROOT,
                "§7管理效果"));
        items.add(navigateItem(14, Material.BARRIER, "§4§l世界边界", MenuKey.SETTINGS_WORLD_BORDER,
                "§7收缩计时"));
        items.add(navigateItem(15, Material.GOLD_INGOT, "§6§l赏金", MenuKey.SETTINGS_BOUNTY,
                "§7猎人奖励系统"));
        items.add(navigateItem(16, Material.TOTEM_OF_UNDYING, "§6§l最后坚守", MenuKey.SETTINGS_LAST_STAND,
                "§7最终速通者增益"));

        items.add(navigateItem(19, Material.DRAGON_HEAD, "§4§l突然死亡", MenuKey.SETTINGS_SUDDEN_DEATH,
                "§7终局对决"));
        items.add(navigateItem(20, Material.BOOK, "§b§l统计", MenuKey.STATS_ROOT,
                "§7跟踪开关"));
        items.add(navigateItem(21, Material.TARGET, "§6§l任务大师", MenuKey.SETTINGS_TASK,
                "§7竞赛规则"));
        items.add(navigateItem(22, Material.NOTE_BLOCK, "§d§l语音聊天", MenuKey.SETTINGS_VOICE_CHAT,
                "§7简易语音聊天集成"));
        items.add(navigateItem(23, Material.BELL, "§e§l广播", MenuKey.SETTINGS_BROADCAST,
                "§7公告设置"));
        items.add(navigateItem(24, Material.COMPARATOR, "§b§l界面与计时器", MenuKey.SETTINGS_UI,
                "§7动作栏、标题、可见性"));
        items.add(navigateItem(25, Material.WRITABLE_BOOK, "§6§l游戏结束消息", MenuKey.SETTINGS_END_MESSAGES,
                "§7获胜者标题和副标题"));

        items.add(navigateItem(28, Material.CHEST, "§a§l装备包", MenuKey.KIT_MANAGER,
                "§7开关装备包和快速操作"));
        items.add(navigateItem(29, Material.MAGMA_BLOCK, "§c§l危险方块", MenuKey.DANGEROUS_BLOCKS,
                "§7编辑安全交换黑名单"));
        items.add(navigateItem(30, Material.ENDER_PEARL, "§b§l多世界", MenuKey.SETTINGS_MULTIWORLD,
                "§7多宇宙和重生兼容性"));

        return new MenuScreen(plugin.getConfigManager().getGuiSettingsTitle(), 54, items);
    }

    private MenuScreen buildSwapSettings(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(10, Material.REPEATER, "§e§l随机交换",
                cfg::isSwapRandomized,
                value -> cfg.setSwapRandomized(value),
                "§7间隔周围的高斯分布"));

        items.add(adjustItem(11, Material.CLOCK, "§e§l基础间隔",
                cfg::getSwapInterval,
                value -> cfg.setSwapInterval(value),
                5, 15, 5, 600,
                "§7每 X 秒交换一次"));

        items.add(toggleItem(12, Material.REDSTONE_TORCH, "§e§l实验性间隔",
                cfg::isBetaIntervalEnabled,
                value -> cfg.setBetaIntervalEnabled(value),
                "§7允许间隔 <30秒 或超过最大值"));

        items.add(adjustItem(13, Material.COMPASS, "§6§l最小间隔",
                cfg::getMinSwapInterval,
                value -> plugin.getConfig().set("swap.min_interval", value),
                5, 15, 5, 600,
                "§7最小随机间隔"));

        items.add(adjustItem(14, Material.COMPASS, "§6§l最大间隔",
                cfg::getSwapIntervalMax,
                value -> plugin.getConfig().set("swap.max_interval", value),
                5, 15, 5, 1800,
                "§7最大随机间隔"));

        items.add(adjustItem(15, Material.SPYGLASS, "§6§l抖动标准差",
                () -> (int) Math.round(cfg.getJitterStdDev()),
                value -> plugin.getConfig().set("swap.jitter.stddev", (double) value),
                1, 5, 0, 600,
                "§7随机间隔的标准差"));

        items.add(toggleItem(16, Material.LEVER, "§e§l限制抖动",
                cfg::isClampJitter,
                value -> plugin.getConfig().set("swap.jitter.clamp", value),
                "§7将随机间隔限制在最小/最大值内"));

        items.add(toggleItem(17, Material.WITHER_SKELETON_SKULL, "§e§l共享猎人身体",
                cfg::isSharedHunterControlEnabled,
                value -> cfg.setSharedHunterControlEnabled(value),
                "§7仅追猎交换模式：猎人共享一个身体"));

        items.add(adjustItem(18, Material.CROSSBOW, "§6§l共享猎人间隔",
                cfg::getSharedHunterControlInterval,
                value -> cfg.setSharedHunterControlInterval(value),
                10, 30, 10, 600,
                "§7共享猎人轮换间隔（秒）"));

        items.add(toggleItem(19, Material.PISTON, "§e§l传统猎人交换",
                cfg::isHunterSwapEnabled,
                value -> plugin.getConfig().set("swap.hunter_swap.enabled", value),
                "§7仅追猎交换模式，当共享猎人身体关闭时"));

        items.add(adjustItem(20, Material.ARROW, "§6§l传统猎人间隔",
                cfg::getHunterSwapInterval,
                value -> plugin.getConfig().set("swap.hunter_swap.interval", value),
                10, 30, 10, 600,
                "§7完整猎人洗牌的间隔（秒）"));

        items.add(toggleItem(21, Material.BLAZE_POWDER, "§e§l烫手山芋模式",
                cfg::isHotPotatoModeEnabled,
                value -> plugin.getConfig().set("swap.hot_potato_mode.enabled", value),
                "§7立即交换到受伤的速通者"));

        items.add(toggleItem(22, Material.REDSTONE_TORCH, "§e§l断线暂停",
                cfg::isPauseOnDisconnect,
                value -> plugin.getConfig().set("swap.pause_on_disconnect", value),
                "§7活跃速通者离开时自动暂停"));

        items.add(toggleItem(23, Material.BOOK, "§e§l应用模式默认",
                cfg::getApplyDefaultOnModeSwitch,
                value -> plugin.getConfig().set("swap.apply_default_on_mode_switch", value),
                "§7切换模式时重置间隔"));

        items.add(adjustItem(24, Material.DIAMOND_SWORD, "§6§l追猎交换默认",
                () -> cfg.getModeDefaultInterval(SpeedrunnerSwap.SwapMode.DREAM),
                value -> cfg.setModeDefaultInterval(SpeedrunnerSwap.SwapMode.DREAM, value),
                5, 15, 5, 600,
                "§7追猎交换模式的默认间隔"));
        items.add(adjustItem(25, Material.DIAMOND_BOOTS, "§6§l速通者交换默认",
                () -> cfg.getModeDefaultInterval(SpeedrunnerSwap.SwapMode.SAPNAP),
                value -> cfg.setModeDefaultInterval(SpeedrunnerSwap.SwapMode.SAPNAP, value),
                5, 15, 5, 600,
                "§7速通者交换模式的默认间隔"));
        items.add(adjustItem(26, Material.TARGET, "§6§l任务大师默认",
                () -> cfg.getModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK),
                value -> cfg.setModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK, value),
                5, 15, 5, 600,
                "§7任务大师模式的默认间隔"));
        items.add(adjustItem(27, Material.RECOVERY_COMPASS, "§6§l任务竞赛默认",
                () -> cfg.getModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK_RACE),
                value -> cfg.setModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK_RACE, value),
                5, 15, 5, 600,
                "§7存储用于模式切换；任务竞赛模式不会发生交换"));
        items.add(adjustItem(29, Material.MACE, "§6§l多人任务大师默认",
                () -> cfg.getModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK_DUEL),
                value -> cfg.setModeDefaultInterval(SpeedrunnerSwap.SwapMode.TASK_DUEL, value),
                5, 15, 5, 600,
                "§7多人任务大师模式两具身体的默认间隔"));

        items.add(adjustItem(28, Material.SHIELD, "§6§l无敌时间（秒）",
                () -> (int) Math.round(plugin.getConfig().getInt("swap.grace_period_ticks", 40) / 20.0),
                value -> plugin.getConfig().set("swap.grace_period_ticks", Math.max(0, value) * 20),
                1, 5, 0, 600,
                "§7交换后的无敌时间（秒）"));

        items.add(toggleConfigItem(30, Material.PLAYER_HEAD, "§e§l保留速通者进度",
                "swap.preserve_runner_progress_on_end", false,
                "§7将最终速通者物品栏复制给所有人"));

        items.add(backButton(44, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        return new MenuScreen("§e§l交换与计时", 54, items);
    }

    private MenuScreen buildSafetySettings(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(10, Material.SLIME_BLOCK, "§e§l安全交换",
                cfg::isSafeSwapEnabled,
                value -> cfg.setSafeSwapEnabled(value),
                "§7交换前扫描区域"));

        items.add(adjustItem(11, Material.MAP, "§6§l水平半径",
                cfg::getSafeSwapHorizontalRadius,
                value -> plugin.getConfig().set("safe_swap.horizontal_radius", value),
                1, 5, 1, 32,
                "§7扫描半径（方块）"));

        items.add(adjustItem(12, Material.LADDER, "§6§l垂直距离",
                cfg::getSafeSwapVerticalDistance,
                value -> plugin.getConfig().set("safe_swap.vertical_distance", value),
                1, 5, 1, 32,
                "§7垂直搜索范围"));

        items.add(clickItem(13, () -> icon(Material.MAGMA_BLOCK, "§c§l危险方块", List.of("§7编辑黑名单")),
                ctxClick -> open(ctxClick.player(), MenuKey.DANGEROUS_BLOCKS, null, false)));

        items.add(cycleItem(19, Material.ICE, "§6§l冻结模式", cfg::getFreezeMode,
                value -> {
                    String next = switch (value.toUpperCase(Locale.ROOT)) {
                        case "EFFECTS" -> "SPECTATOR";
                        case "SPECTATOR" -> "LIMBO";
                        case "LIMBO" -> "CAGE";
                        default -> "EFFECTS";
                    };
                    plugin.getConfigManager().setFreezeMode(next);
                    plugin.getGameManager().refreshFreezeMechanic();
                    return next;
                },
                List.of("§7如何处理不活跃的速通者",
                        "§bEFFECTS §7- 严重缓慢",
                        "§bSPECTATOR §7- 旁观者模式",
                        "§bLIMBO §7- 传送到虚空位置",
                        "§bCAGE §7- 困在笼子里")));

        items.add(toggleItem(20, Material.REDSTONE_TORCH, "§e§l冻结机制",
                cfg::isFreezeMechanicEnabled,
                value -> {
                    plugin.getConfig().set("freeze_mechanic.enabled", value);
                    plugin.getGameManager().refreshFreezeMechanic();
                },
                "§7强制不活跃速通者靠近活跃速通者"));

        items.add(adjustItem(21, Material.CLOCK, "§6§l冻结持续时间",
                cfg::getFreezeDurationTicks,
                value -> {
                    plugin.getConfig().set("freeze_mechanic.duration_ticks", value);
                    plugin.getGameManager().refreshFreezeMechanic();
                },
                20, 100, 20, 20 * 60 * 5,
                "§7冻结持续的刻数"));

        items.add(adjustItem(22, Material.REPEATER, "§6§l检查间隔",
                cfg::getFreezeCheckIntervalTicks,
                value -> {
                    plugin.getConfig().set("freeze_mechanic.check_interval_ticks", value);
                    plugin.getGameManager().refreshFreezeMechanic();
                },
                5, 20, 5, 200,
                "§7冻结检查间隔（刻）"));

        items.add(adjustItem(23, Material.COMPASS, "§6§l最大距离",
                () -> (int) Math.round(cfg.getFreezeMaxDistance()),
                value -> {
                    plugin.getConfig().set("freeze_mechanic.max_distance", value);
                    plugin.getGameManager().refreshFreezeMechanic();
                },
                5, 20, 5, 256,
                "§7冻结前的最大距离"));

        items.add(toggleItem(30, Material.BARRIER, "§e§l取消移动",
                cfg::isCancelMovement,
                value -> plugin.getConfig().set("cancel.movement", value),
                "§7阻止不活跃速通者移动"));

        items.add(toggleItem(31, Material.STICK, "§e§l取消交互",
                cfg::isCancelInteractions,
                value -> plugin.getConfig().set("cancel.interactions", value),
                "§7阻止不活跃速通者交互"));

        items.add(toggleConfigItem(24, Material.PAPER, "§e§l限制不活跃聊天",
                "chat.restrict_inactive_runners", false,
                "§7防止等待中的速通者聊天"));

        items.add(toggleItem(32, Material.WHITE_BED, "§e§l单人睡眠",
                cfg::isSinglePlayerSleepEnabled,
                value -> cfg.setSinglePlayerSleepEnabled(value),
                "§7只有活跃速通者需要睡觉"));

        items.add(toggleConfigItem(33, Material.RESPAWN_ANCHOR, "§e§l强制全局重生",
                "spawn.force_global", true,
                "§7覆盖个人床"));

        items.add(clickItem(34, () -> {
            org.bukkit.Location spawn = plugin.getConfigManager().getSpawnLocation();
            String worldName = spawn.getWorld() != null ? spawn.getWorld().getName() : "未知";
            return icon(Material.COMPASS, "§b§l设置重生点",
                    List.of("§7世界：§f" + worldName,
                            String.format(Locale.ROOT, "§7坐标：§f%.1f / %.1f / %.1f", spawn.getX(), spawn.getY(),
                                    spawn.getZ()),
                            "",
                            "§e点击使用你的位置"));
        }, ctxClick -> {
            plugin.getConfigManager().setGlobalSpawn(ctxClick.player().getLocation(), true);
            Msg.send(ctxClick.player(), "§a全局重生点已更新到你当前位置。");
            ctxClick.reopen();
        }));

        items.add(clickItem(35, () -> {
            org.bukkit.Location limbo = cfg.getLimboLocation();
            String world = limbo.getWorld() != null ? limbo.getWorld().getName() : "未知";
            String coords = String.format(Locale.ROOT, "§f%.1f §7/ §f%.1f §7/ §f%.1f", limbo.getX(), limbo.getY(),
                    limbo.getZ());
            return icon(Material.ENDER_PEARL, "§b§l设置虚空位置",
                    List.of("§7世界：§f" + world, "§7坐标：" + coords, "", "§e点击使用你的位置"));
        }, ctxClick -> {
            org.bukkit.Location loc = ctxClick.player().getLocation();
            plugin.getConfig().set("limbo.world", loc.getWorld() != null ? loc.getWorld().getName() : "world");
            plugin.getConfig().set("limbo.x", loc.getX());
            plugin.getConfig().set("limbo.y", loc.getY());
            plugin.getConfig().set("limbo.z", loc.getZ());
            plugin.saveConfig();
            Msg.send(ctxClick.player(), "§a虚空位置已更新到你当前位置。");
            ctxClick.reopen();
        }));

        items.add(backButton(44, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        return new MenuScreen("§c§l安全与冻结", 54, items);
    }

    private MenuScreen buildHunterSettings(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(10, Material.COMPASS, "§e§l追踪器",
                cfg::isTrackerEnabled,
                value -> {
                    cfg.setTrackerEnabled(value);
                    if (plugin.getGameManager().isGameRunning()) {
                        if (value) {
                            plugin.getTrackerManager().startTracking();
                            plugin.getTrackerManager().updateAllHunterCompasses();
                        } else {
                            plugin.getTrackerManager().stopTracking();
                        }
                    }
                },
                "§7给猎人追踪指南针"));

        items.add(adjustItem(11, Material.REPEATER, "§6§l更新刻数",
                cfg::getTrackerUpdateTicks,
                value -> {
                    plugin.getConfig().set("tracker.update_ticks", value);
                    if (plugin.getGameManager().isGameRunning() && cfg.isTrackerEnabled()) {
                        plugin.getTrackerManager().startTracking();
                    }
                },
                5, 20, 1, 200,
                "§7指南针更新频率"));

        items.add(toggleItem(12, Material.BLAZE_ROD, "§e§l指南针干扰",
                cfg::isCompassJammingEnabled,
                value -> plugin.getConfig().set("tracker.compass_jamming.enabled", value),
                "§7交换后扰乱指南针"));

        items.add(adjustItem(13, Material.CLOCK, "§6§l干扰持续时间",
                cfg::getCompassJamDuration,
                value -> plugin.getConfig().set("tracker.compass_jamming.duration_ticks", value),
                20, 100, 20, 20 * 60,
                "§7指南针保持干扰的刻数"));

        items.add(adjustItem(14, Material.SPYGLASS, "§6§l干扰距离",
                cfg::getCompassJamMaxDistance,
                value -> cfg.setCompassJamMaxDistance(value),
                10, 50, 0, 5000,
                "§7最大随机偏移"));

        items.add(adjustItem(15, Material.OBSIDIAN, "§6§l传送门重试次数",
                () -> plugin.getConfig().getInt("tracker.portal_retry_attempts", 5),
                value -> plugin.getConfig().set("tracker.portal_retry_attempts", Math.max(1, value)),
                1, 2, 1, 10,
                "§7在传送门中放弃前的尝试次数"));

        items.add(adjustItem(16, Material.CLOCK, "§6§l传送门重试延迟",
                () -> (int) plugin.getConfig().getLong("tracker.portal_retry_delay_ticks", 20L),
                value -> plugin.getConfig().set("tracker.portal_retry_delay_ticks", Math.max(1, value)),
                5, 20, 1, 200,
                "§7传送门重试检查间隔（刻）"));

        items.add(clickItem(44, () -> icon(Material.BARRIER, "§7§l返回", Collections.emptyList()),
                ctxClick -> open(ctxClick.player(), MenuKey.SETTINGS_HOME, null, false)));

        return new MenuScreen("§c§l猎人工具", 27, items);
    }

    private MenuScreen buildPowerUpsRoot(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(11, Material.LIME_DYE, "§e§l增益效果",
                cfg::isPowerUpsEnabled,
                value -> cfg.setPowerUpsEnabled(value),
                "§7启用随机交换效果"));

        items.add(navigateItem(13, Material.SPLASH_POTION, "§a§l正面效果", MenuKey.POWERUPS_EFFECTS,
                "§7配置增益", "positive"));
        items.add(navigateItem(15, Material.POISONOUS_POTATO, "§c§l负面效果", MenuKey.POWERUPS_EFFECTS,
                "§7配置减益", "negative"));

        items.add(navigateItem(22, Material.CLOCK, "§6§l持续时间与等级", MenuKey.POWERUPS_DURATION,
                "§7修改持续时间范围"));

        items.add(clickItem(44, () -> icon(Material.BARRIER, "§7§l返回", Collections.emptyList()),
                ctxClick -> open(ctxClick.player(), MenuKey.SETTINGS_HOME, null, false)));

        return new MenuScreen("§d§l增益效果", 27, items);
    }

    private MenuScreen buildPowerUpEffects(MenuContext ctx) {
        boolean positive = "positive".equalsIgnoreCase(String.valueOf(ctx.request().data()));
        List<String> list = positive ? plugin.getConfigManager().getGoodPowerUps()
                : plugin.getConfigManager().getBadPowerUps();
        Set<String> enabled = new HashSet<>();
        for (String id : list) {
            enabled.add(id.toUpperCase(Locale.ROOT));
        }

        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.POWERUPS_ROOT, null, this::openPowerUpsMenu));

        int slot = 9;
        @SuppressWarnings("deprecation")
        PotionEffectType[] effectTypes = PotionEffectType.values();
        for (PotionEffectType type : effectTypes) {
            if (type == null || type.getKey() == null)
                continue;
            String id = type.getKey().getKey().toUpperCase(Locale.ROOT);
            String prefix = positive ? "§a" : "§c";
            Material material = positive ? Material.HONEY_BOTTLE : Material.SPIDER_EYE;
            items.add(toggleItem(slot, material, prefix + id,
                    () -> enabled.contains(id), value -> {
                        List<String> editable = positive ? plugin.getConfig().getStringList("power_ups.good_effects")
                                : plugin.getConfig().getStringList("power_ups.bad_effects");
                        if (value && !editable.contains(id))
                            editable.add(id);
                        if (!value)
                            editable.remove(id);
                        if (positive)
                            plugin.getConfig().set("power_ups.good_effects", editable);
                        else
                            plugin.getConfig().set("power_ups.bad_effects", editable);
                        plugin.saveConfig();
                        Msg.send(ctx.player(), "§e" + id + ": " + (value ? "§a已启用" : "§c已禁用"));
                    }, "§7点击切换"));

            slot++;
            if ((slot + 1) % 9 == 0)
                slot += 2;
            if (slot >= 54)
                break;
        }

        return new MenuScreen(positive ? "§a§l正面效果" : "§c§l负面效果", 54, items);
    }

    private MenuScreen buildPowerUpDurations(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.POWERUPS_ROOT, null, this::openPowerUpsMenu));

        items.add(adjustItem(12, Material.CLOCK, "§6§l最小持续时间", cfg::getPowerUpsMinSeconds,
                value -> cfg.setPowerUpsMinSeconds(value), 5, 20, 1, 1800,
                "§7秒（最小）"));
        items.add(adjustItem(14, Material.CLOCK, "§6§l最大持续时间", cfg::getPowerUpsMaxSeconds,
                value -> cfg.setPowerUpsMaxSeconds(value), 5, 20, 1, 3600,
                "§7秒（最大）"));
        items.add(adjustItem(21, Material.EXPERIENCE_BOTTLE, "§6§l最小等级", cfg::getPowerUpsMinLevel,
                value -> cfg.setPowerUpsMinLevel(value), 1, 1, 1, 5,
                "§7药水效果等级（最小）"));
        items.add(adjustItem(23, Material.EXPERIENCE_BOTTLE, "§6§l最大等级", cfg::getPowerUpsMaxLevel,
                value -> cfg.setPowerUpsMaxLevel(value), 1, 1, 1, 5,
                "§7药水效果等级（最大）"));

        return new MenuScreen("§6§l持续时间与等级", 45, items);
    }

    private MenuScreen buildDangerousBlocks(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        Set<Material> blocks = new HashSet<>(plugin.getConfigManager().getDangerousBlocks());
        List<Material> sorted = new ArrayList<>(blocks);
        sorted.sort(java.util.Comparator.comparing(Enum::name));

        int slot = 9;
        for (Material material : sorted) {
            items.add(clickItem(slot, () -> icon(material, "§e" + material.name(), List.of("§c点击移除")),
                    ctxClick -> {
                        Set<Material> set = plugin.getConfigManager().getDangerousBlocks();
                        set.remove(material);
                        List<String> updated = new ArrayList<>();
                        for (Material m : set)
                            updated.add(m.name());
                        plugin.getConfig().set("safe_swap.dangerous_blocks", updated);
                        plugin.saveConfig();
                        Msg.send(ctxClick.player(), "§e已移除 §f" + material.name());
                        ctxClick.reopen();
                    }));
            slot++;
            if ((slot + 1) % 9 == 0)
                slot += 2;
        }

        items.add(clickItem(44, () -> icon(Material.EMERALD_BLOCK, "§a§l添加方块", List.of("§7在聊天中输入方块ID")),
                ctxClick -> {
                    plugin.getChatInputHandler().expectConfigListAdd(ctxClick.player(), "safe_swap.dangerous_blocks");
                    ctxClick.player().closeInventory();
                    Msg.send(ctxClick.player(), "§e输入方块ID（或'cancel'取消）。");
                }));

        return new MenuScreen("§c§l危险方块", 54, items);
    }

    private MenuScreen buildWorldBorder(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleConfigItem(10, Material.BARRIER, "§e§l世界边界", "world_border.enabled", false,
                "§7启用收缩边界"));

        items.add(adjustConfigItem(12, Material.GRASS_BLOCK, "§6§l初始大小",
                "world_border.initial_size", 2000,
                50, 100, 50, 100000,
                "§7开始时的方块数"));
        items.add(adjustConfigItem(14, Material.BEDROCK, "§6§l最终大小",
                "world_border.final_size", 100,
                25, 100, 25, 5000,
                "§7结束时的方块数"));
        items.add(adjustConfigItem(16, Material.CLOCK, "§6§l收缩持续时间",
                "world_border.shrink_duration", 1800,
                60, 300, 60, 21600,
                "§7收缩时间（秒）"));

        items.add(adjustConfigItem(20, Material.SPYGLASS, "§6§l警告距离",
                "world_border.warning_distance", 50,
                5, 25, 0, 5000,
                "§7警告距离（方块）"));

        items.add(adjustConfigItem(22, Material.BELL, "§6§l警告间隔（秒）",
                "world_border.warning_interval", 300,
                30, 60, 30, 3600,
                "§7警报间隔（秒）"));

        return new MenuScreen("§4§l世界边界", 45, items);
    }

    private MenuScreen buildBounty(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(10, Material.GOLD_INGOT, "§6§l启用赏金",
                () -> plugin.getConfig().getBoolean("bounty.enabled", false),
                value -> {
                    plugin.getConfig().set("bounty.enabled", value);
                    plugin.saveConfig();
                    if (!value)
                        plugin.getBountyManager().clearBounty();
                },
                "§7启用猎人赏金挑战"));

        items.add(adjustItem(12, Material.CLOCK, "§6§l冷却时间（秒）",
                () -> plugin.getConfig().getInt("bounty.cooldown", 300),
                value -> plugin.getConfig().set("bounty.cooldown", Math.max(0, value)),
                30, 60, 0, 3600,
                "§7赏金之间的最小间隔（秒）"));

        items.add(adjustItem(14, Material.GLOWSTONE_DUST, "§6§l发光持续时间（秒）",
                () -> plugin.getConfig().getInt("bounty.glow_duration", 300),
                value -> plugin.getConfig().set("bounty.glow_duration", Math.max(10, value)),
                30, 60, 10, 3600,
                "§7目标发光的时间（秒）"));

        items.add(adjustItem(21, Material.SUGAR, "§6§l速度奖励（秒）",
                () -> plugin.getConfig().getInt("bounty.rewards.speed_duration", 300),
                value -> plugin.getConfig().set("bounty.rewards.speed_duration", Math.max(10, value)),
                30, 60, 10, 6000,
                "§7击杀者速度效果持续时间"));

        items.add(adjustItem(23, Material.BLAZE_POWDER, "§6§l力量奖励（秒）",
                () -> plugin.getConfig().getInt("bounty.rewards.strength_duration", 300),
                value -> plugin.getConfig().set("bounty.rewards.strength_duration", Math.max(10, value)),
                30, 60, 10, 6000,
                "§7击杀者力量效果持续时间"));

        items.add(clickItem(30, () -> icon(Material.TARGET, "§a§l分配新赏金", List.of("§7选择新目标")),
                ctxClick -> {
                    plugin.getBountyManager().assignNewBounty();
                    Msg.send(ctxClick.player(), "§a新赏金已分配。");
                }));
        items.add(clickItem(32, () -> icon(Material.BARRIER, "§c§l清除赏金", List.of("§7移除当前目标")),
                ctxClick -> {
                    plugin.getBountyManager().clearBounty();
                    Msg.send(ctxClick.player(), "§c赏金已清除。");
                }));

        return new MenuScreen("§6§l赏金系统", 45, items);
    }

    private MenuScreen buildLastStand(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(10, Material.TOTEM_OF_UNDYING, "§6§l最后坚守",
                cfg::isLastStandEnabled,
                value -> plugin.getConfig().set("last_stand.enabled", value),
                "§7增强最终速通者"));

        items.add(adjustItem(12, Material.CLOCK, "§6§l持续时间",
                cfg::getLastStandDuration,
                value -> plugin.getConfig().set("last_stand.duration_ticks", value),
                20, 100, 20, 20 * 60 * 5,
                "§7增益持续的刻数"));

        items.add(adjustItem(14, Material.IRON_SWORD, "§6§l力量等级",
                cfg::getLastStandStrengthAmplifier,
                value -> plugin.getConfig().set("last_stand.strength_amplifier", value),
                1, 1, 0, 5,
                "§7放大器（等级-1）"));

        items.add(adjustItem(16, Material.SUGAR, "§6§l速度等级",
                cfg::getLastStandSpeedAmplifier,
                value -> plugin.getConfig().set("last_stand.speed_amplifier", value),
                1, 1, 0, 5,
                "§7放大器（等级-1）"));

        return new MenuScreen("§6§l最后坚守", 45, items);
    }

    private MenuScreen buildSuddenDeath(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleConfigItem(10, Material.DRAGON_HEAD, "§4§l突然死亡",
                "sudden_death.enabled", false,
                "§7启用末地维度对决"));

        items.add(adjustItem(12, Material.CLOCK, "§6§l激活延迟（秒）",
                () -> plugin.getConfig().getInt("sudden_death.activation_delay", 1200),
                value -> plugin.getConfig().set("sudden_death.activation_delay", Math.max(30, value)),
                60, 300, 30, 36000,
                "§7突然死亡开始前的秒数"));

        items.add(adjustItem(14, Material.SHIELD, "§6§l抗性（秒）",
                () -> (int) Math
                        .round(plugin.getConfig().getInt("sudden_death.effects.resistance_duration", 200) / 20.0),
                value -> plugin.getConfig().set("sudden_death.effects.resistance_duration", Math.max(1, value) * 20),
                5, 20, 1, 600,
                "§7抗性 IV 持续时间"));

        items.add(adjustItem(16, Material.GOLDEN_APPLE, "§6§l生命恢复（秒）",
                () -> (int) Math
                        .round(plugin.getConfig().getInt("sudden_death.effects.regeneration_duration", 200) / 20.0),
                value -> plugin.getConfig().set("sudden_death.effects.regeneration_duration", Math.max(1, value) * 20),
                5, 20, 1, 600,
                "§7生命恢复 III 持续时间"));

        items.add(adjustItem(20, Material.SPYGLASS, "§6§l最大干扰距离",
                () -> plugin.getConfig().getInt("sudden_death.arena.max_jam_distance", 100),
                value -> plugin.getConfig().set("sudden_death.arena.max_jam_distance", Math.max(0, value)),
                10, 50, 0, 10000,
                "§7随机指南针偏移的方块数"));

        items.add(clickItem(22, () -> {
            double x = plugin.getConfig().getDouble("sudden_death.arena.x", 100.0);
            double y = plugin.getConfig().getDouble("sudden_death.arena.y", 50.0);
            double z = plugin.getConfig().getDouble("sudden_death.arena.z", 0.0);
            String worldName = plugin.getConfig().getString("sudden_death.arena.world", "world_the_end");
            return icon(Material.ENDER_EYE, "§b§l设置竞技场位置",
                    List.of("§7当前：§f" + x + ", " + y + ", " + z,
                            "§7世界：§f" + worldName,
                            "", "§e点击使用你的位置"));
        }, ctxClick -> {
            org.bukkit.Location loc = ctxClick.player().getLocation();
            plugin.getConfig().set("sudden_death.arena.x", loc.getX());
            plugin.getConfig().set("sudden_death.arena.y", loc.getY());
            plugin.getConfig().set("sudden_death.arena.z", loc.getZ());
            plugin.getConfig().set("sudden_death.arena.world",
                    loc.getWorld() != null ? loc.getWorld().getName() : "world_the_end");
            plugin.saveConfig();
            Msg.send(ctxClick.player(), "§a竞技场位置已更新。");
            ctxClick.reopen();
        }));

        items.add(clickItem(30, () -> icon(Material.CLOCK, "§e§l计划", List.of("§7开始倒计时")), ctxClick -> {
            plugin.getSuddenDeathManager().scheduleSuddenDeath();
            Msg.send(ctxClick.player(), "§e突然死亡已计划。");
        }));
        items.add(clickItem(32, () -> icon(Material.BARRIER, "§c§l取消计划", Collections.emptyList()),
                ctxClick -> {
                    plugin.getSuddenDeathManager().cancelSchedule();
                    Msg.send(ctxClick.player(), "§c计划已取消。");
                }));
        items.add(clickItem(34, () -> icon(Material.TNT, "§4§l立即激活", Collections.emptyList()), ctxClick -> {
            plugin.getSuddenDeathManager().activateSuddenDeath();
            Msg.send(ctxClick.player(), "§4突然死亡已激活！");
        }));

        return new MenuScreen("§4§l突然死亡", 45, items);
    }

    private MenuScreen buildStatsRoot(MenuContext ctx) {
        StatsParent parent = statsParents.getOrDefault(ctx.player().getUniqueId(), StatsParent.SETTINGS);
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", parent == StatsParent.MAIN ? MenuKey.MAIN : MenuKey.SETTINGS_HOME, null,
                player -> {
                    if (parent == StatsParent.MAIN)
                        open(player, MenuKey.MAIN, null, false);
                    else
                        openSettingsMenu(player);
                }));

        items.add(toggleItem(11, Material.LIME_DYE, "§e§l统计信息",
                () -> plugin.getConfig().getBoolean("stats.enabled", true),
                value -> {
                    plugin.getConfig().set("stats.enabled", value);
                    plugin.saveConfig();
                    if (!value)
                        plugin.getStatsManager().stopTracking();
                    else if (plugin.getGameManager().isGameRunning())
                        plugin.getStatsManager().startTracking();
                },
                "§7切换服务器端追踪"));

        items.add(toggleItem(13, Material.COMPASS, "§6§l距离追踪",
                () -> plugin.getConfig().getBoolean("stats.distance_tracking", true),
                value -> {
                    plugin.getConfig().set("stats.distance_tracking", value);
                    plugin.saveConfig();
                }, "§7启用速通者-猎人距离度量"));

        items.add(adjustItem(15, Material.REPEATER, "§6§l距离更新",
                () -> plugin.getConfig().getInt("stats.distance_update_ticks", 20),
                value -> {
                    plugin.getConfig().set("stats.distance_update_ticks", value);
                    plugin.saveConfig();
                }, 5, 20, 1, 200,
                "§7距离更新间隔（刻）"));

        items.add(toggleConfigItem(17, Material.CLOCK, "§e§l周期显示",
                "stats.periodic_display", false,
                "§7自动公告统计"));

        items.add(adjustConfigItem(26, Material.CLOCK, "§6§l显示间隔（秒）",
                "stats.periodic_display_interval", 300,
                30, 60, 30, 3600,
                "§7统计公告间隔（秒）"));

        items.add(clickItem(22, () -> icon(Material.PAPER, "§b§l广播快照", List.of("§7发送统计到聊天")),
                ctxClick -> plugin.getStatsManager().displayStats()));

        items.add(navigateItem(24, Material.SPYGLASS, "§6§l高级", MenuKey.STATS_ADVANCED, "§7附加设置"));

        return new MenuScreen("§b§l统计", 45, items);
    }

    private MenuScreen buildStatsAdvanced(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.STATS_ROOT, null,
                player -> open(player, MenuKey.STATS_ROOT, null, false)));

        items.add(adjustItem(11, Material.CLOCK, "§6§l行动栏更新",
                cfg::getActionBarUpdateTicks,
                value -> plugin.getConfig().set("ui.update_ticks.actionbar", value),
                5, 20, 1, 200,
                "§7行动栏刷新间隔（刻）"));

        items.add(adjustItem(13, Material.EXPERIENCE_BOTTLE, "§6§l标题更新",
                cfg::getTitleUpdateTicks,
                value -> plugin.getConfig().set("ui.update_ticks.title", value),
                1, 5, 1, 200,
                "§7标题刷新间隔（刻）"));

        items.add(adjustItem(15, Material.COMPASS, "§6§l追踪器更新",
                cfg::getTrackerUpdateTicks,
                value -> plugin.getConfig().set("tracker.update_ticks", value),
                5, 20, 1, 200,
                "§7指南针刷新间隔"));

        items.add(cycleItem(20, Material.CLOCK, "§6§l速通者计时器", cfg::getRunnerTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setRunnerTimerVisibility(next);
                    return next;
                }, timerLore("活跃速通者")));
        items.add(cycleItem(22, Material.CLOCK, "§6§l等待计时器", cfg::getWaitingTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setWaitingTimerVisibility(next);
                    return next;
                }, timerLore("等待中的速通者")));
        items.add(cycleItem(24, Material.CLOCK, "§6§l猎人计时器", cfg::getHunterTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setHunterTimerVisibility(next);
                    return next;
                }, timerLore("猎人")));

        return new MenuScreen("§b§l统计 - 高级", 45, items);
    }

    private MenuScreen buildTaskHome(MenuContext ctx) {
        GameManager gm = plugin.getGameManager();
        TaskManagerMode taskMode = plugin.getTaskManagerMode();
        SpeedrunnerSwap.SwapMode currentMode = plugin.getCurrentMode();
        boolean taskModeSelected = plugin.isTaskCompetitionMode();
        boolean noSwapMode = plugin.isParallelTaskMode();
        boolean dualBodyMode = plugin.isDualBodyTaskMode();

        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.MAIN, null, this::openMainMenu));
        items.add(clickItem(2, () -> icon(Material.TARGET,
                currentMode == SpeedrunnerSwap.SwapMode.TASK ? "§a§l正在使用任务大师" : "§6§l使用任务大师",
                List.of("§7共享身体交换竞赛", "§7经典秘密任务破坏模式")), ctxClick -> {
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c切换任务模式前请先停止当前游戏。");
                        return;
                    }
                    plugin.setCurrentMode(SpeedrunnerSwap.SwapMode.TASK);
                    Msg.send(ctxClick.player(), "§a任务中心已设置为 §f任务大师§a。");
                    ctxClick.reopen();
                }));
        items.add(clickItem(4, () -> icon(Material.MACE,
                currentMode == SpeedrunnerSwap.SwapMode.TASK_DUEL ? "§a§l正在使用多人任务大师" : "§6§l使用多人任务大师",
                List.of("§72个共享身体同时活跃", "§7每个玩家仍有一个秘密任务")), ctxClick -> {
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c切换任务模式前请先停止当前游戏。");
                        return;
                    }
                    plugin.setCurrentMode(SpeedrunnerSwap.SwapMode.TASK_DUEL);
                    Msg.send(ctxClick.player(), "§a任务中心已设置为 §f多人任务大师§a。");
                    ctxClick.reopen();
                }));
        items.add(clickItem(6, () -> icon(Material.RECOVERY_COMPASS,
                currentMode == SpeedrunnerSwap.SwapMode.TASK_RACE ? "§a§l正在使用任务竞赛" : "§6§l使用任务竞赛",
                List.of("§72名或更多速通者同时进行游戏", "§7没有定期交换或非活跃锁定")), ctxClick -> {
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c切换任务模式前请先停止当前游戏。");
                        return;
                    }
                    plugin.setCurrentMode(SpeedrunnerSwap.SwapMode.TASK_RACE);
                    Msg.send(ctxClick.player(), "§a任务中心已设置为 §f任务竞赛§a。");
                    ctxClick.reopen();
                }));
        items.add(simpleItem(8, () -> icon(Material.BOOK, "§e§l当前任务模式",
                List.of(
                        "§7当前插件模式：§f" + modeDisplayName(currentMode),
                        "§7任务难度：§f"
                                + (taskMode != null ? taskMode.getDifficultyFilter().name() : "MEDIUM"),
                        taskModeSelected
                                ? (noSwapMode ? "§7竞赛类型：§f无交换并行竞赛"
                                        : dualBodyMode ? "§7竞赛类型：§f双共享身体"
                                                : "§7竞赛类型：§f共享身体交换")
                                : "§c从此中心开始前请先切换到任务模式"))));

        boolean running = gm.isGameRunning();
        boolean paused = gm.isGamePaused();
        boolean ready = taskModeSelected && gm.canStartGame();
        int runnerCount = gm.getRunners().size();
        int onlineRunners = (int) gm.getRunners().stream().filter(Player::isOnline).count();
        int hunterCount = gm.getHunters().size();
        int onlineHunters = (int) gm.getHunters().stream().filter(Player::isOnline).count();
        int requiredRunners = noSwapMode ? 2 : 1;

        // 状态面板 ------------------------------------------------
        items.add(clickItem(10, () -> {
            if (running) {
                List<String> lore = new ArrayList<>();
                lore.add("§7正在运行，§f" + onlineRunners + " §7名在线速通者");
                if (dualBodyMode) {
                    lore.add("§7第二具身体在线：§f" + onlineHunters);
                }
                lore.add("");
                lore.add("§e点击结束当前回合");
                return icon(Material.BARRIER, "§c§l停止竞赛",
                        lore);
            }

            List<String> lore = new ArrayList<>();
            if (!taskModeSelected) {
                lore.add("§c请先选择一个任务模式");
            } else if (!ready) {
                lore.add("§c选择的玩家数量不足");
                if (dualBodyMode) {
                    lore.add("§7至少需要 §f1名速通者 §7和 §f1名第二具身体玩家");
                } else {
                    lore.add("§7至少选择 §f" + requiredRunners + " §b名速通者");
                }
            } else {
                lore.add("§7已准备好，§f" + runnerCount + " §7名速通者");
                if (dualBodyMode) {
                    lore.add("§7第二具身体玩家：§f" + hunterCount);
                }
            }
            lore.add("");
            lore.add(ready ? "§e点击开始"
                    : dualBodyMode ? "§c分配两个共享身体以开始" : "§c分配速通者以开始");
            String label = ready ? "§a§l开始竞赛" : "§4§l无法开始";
            Material mat = ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
            return icon(mat, label, lore);
        }, ctxClick -> {
            if (running) {
                gm.stopGame();
                Msg.send(ctxClick.player(), "§c" + modeDisplayName(currentMode) + " 回合已停止。");
                ctxClick.reopen();
                return;
            }
            if (!taskModeSelected) {
                Msg.send(ctxClick.player(), "§c请先在此中心选择一个任务模式。");
                return;
            }
            if (!ready) {
                if (dualBodyMode) {
                    Msg.send(ctxClick.player(), "§c开始前请至少分配一名速通者和一名第二具身体玩家。");
                } else {
                    Msg.send(ctxClick.player(), "§c开始前请至少分配 " + requiredRunners + " 名速通者。");
                }
                return;
            }
            if (gm.startGame()) {
                Msg.send(ctxClick.player(), "§a" + modeDisplayName(currentMode) + " 竞赛已开始。");
            } else {
                Msg.send(ctxClick.player(), "§c无法开始。请检查队伍分配。");
            }
            ctxClick.reopen();
        }));

        items.add(clickItem(12, () -> {
            if (!running) {
                return icon(Material.GRAY_CONCRETE, "§7§l暂停竞赛",
                        List.of("§7当前回合未运行"));
            }
            if (paused) {
                return icon(Material.LIME_DYE, "§a§l恢复竞赛",
                        List.of("§7恢复当前竞赛"));
            }
            return icon(Material.CLOCK, "§e§l暂停竞赛",
                    List.of("§7冻结进度而不结束", "§e点击暂停"));
        }, ctxClick -> {
            if (!running) {
                Msg.send(ctxClick.player(), "§e请先开始竞赛。");
                return;
            }
            if (paused) {
                gm.resumeGame();
                Msg.send(ctxClick.player(), "§a竞赛已恢复。");
            } else {
                gm.pauseGame();
                Msg.send(ctxClick.player(), "§e竞赛已暂停。");
            }
            ctxClick.reopen();
        }));

        items.add(clickItem(14, () -> {
            String assigned = taskMode != null ? taskMode.getAssignedTaskDescription(ctx.player()) : null;
            List<String> lore = new ArrayList<>();
            if (assigned == null) {
                lore.add("§7你还没有被分配任务");
                lore.add("§7请先开始或预滚动回合");
            } else {
                lore.add("§7当前任务：");
                lore.add("§f" + assigned);
                lore.add("");
                lore.add("§e点击重新发送你的任务");
                lore.add("§dShift点击使用重掷机会");
                lore.add("§7剩余重掷次数：§f" + taskMode.getRemainingRerolls(ctx.player()));
            }
            return icon(Material.PAPER, "§e§l你的任务", lore);
        }, ctxClick -> {
            if (taskMode == null) {
                Msg.send(ctxClick.player(), "§c任务管理器不可用。");
                return;
            }
            String assigned = taskMode.getAssignedTaskDescription(ctxClick.player());
            if (assigned == null) {
                Msg.send(ctxClick.player(), "§e你还没有被分配任务。");
                return;
            }
            if (ctxClick.shift()) {
                String blocked = taskMode.getRerollUnavailableReason(ctxClick.player());
                if (blocked != null) {
                    Msg.send(ctxClick.player(), "§c" + blocked);
                    return;
                }
                TaskDefinition newTask = taskMode.rerollTask(ctxClick.player());
                if (newTask == null) {
                    Msg.send(ctxClick.player(), "§c当前没有可用的替代任务。");
                    return;
                }
                Msg.send(ctxClick.player(), "§a任务已重掷为：§f" + newTask.description());
                ctxClick.reopen();
                return;
            }
            Msg.send(ctxClick.player(), "§6你的任务：§f" + assigned);
            if (taskMode.isTaskRerollEnabled()) {
                String blocked = taskMode.getRerollUnavailableReason(ctxClick.player());
                Msg.send(ctxClick.player(),
                        blocked == null ? "§7Shift点击此方块或使用 §e/swap complete reroll confirm"
                                : "§7重掷状态：§f" + blocked);
            }
        }));

        items.add(clickItem(16, () -> {
            boolean hasAssignments = taskMode != null && !taskMode.getAssignments().isEmpty();
            List<String> lore = new ArrayList<>();
            lore.add("§7广播当前秘密任务");
            lore.add(hasAssignments ? "§a有可用分配" : "§c还没有分配");
            lore.add("");
            lore.add("§e点击脉冲竞赛更新");
            return icon(Material.HEART_OF_THE_SEA, "§b§l脉冲竞赛", lore);
        }, ctxClick -> {
            if (taskMode == null || taskMode.getAssignments().isEmpty()) {
                Msg.send(ctxClick.player(), "§c还没有活跃分配。请先开始回合。");
                return;
            }
            taskMode.broadcastAssignments();
            Msg.send(ctxClick.player(), "§a正在向所有玩家广播当前分配。");
        }));

        items.add(clickItem(15, () -> icon(Material.NETHERITE_SWORD, "§6§l任务难度",
                List.of("§7当前池：§f" + (taskMode != null ? taskMode.getDifficultyFilter().name() : "MEDIUM"),
                        "§e点击循环 简单 → 中等 → 困难",
                        "§7在开始新回合前选择")), ctxClick -> {
                    if (taskMode == null) {
                        Msg.send(ctxClick.player(), "§c任务管理器不可用。");
                        return;
                    }
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c更改任务难度前请先停止当前回合。");
                        return;
                    }
                    TaskDifficulty cur = taskMode.getDifficultyFilter();
                    TaskDifficulty next = switch (cur) {
                        case EASY -> TaskDifficulty.MEDIUM;
                        case MEDIUM -> TaskDifficulty.HARD;
                        case HARD -> TaskDifficulty.EASY;
                    };
                    taskMode.setDifficultyFilter(next);
                    Msg.send(ctxClick.player(), "§a任务难度已设置为 §f" + next.name());
                    ctxClick.reopen();
                }));

        // 身体管理 --------------------------------------
        items.add(clickItem(19, () -> icon(Material.PLAYER_HEAD,
                dualBodyMode ? "§d§l身体管理" : "§b§l速通者管理",
                dualBodyMode ? List.of("§7分配共享身体 A + B 玩家")
                        : List.of("§7任务竞赛仅使用速通者")), ctxClick -> {
                    if (plugin.isDualBodyTaskMode()) {
                        open(ctxClick.player(), MenuKey.TEAM_MANAGEMENT, null, false);
                    } else {
                        open(ctxClick.player(), MenuKey.TASK_RUNNERS, null, false);
                    }
                }));

        // 任务统计板块 ----------------------------------------
        boolean statsEnabled = plugin.getConfig().getBoolean("stats.enabled", true);
        boolean distanceEnabled = plugin.getConfig().getBoolean("stats.distance_tracking", true);
        int distanceTicks = plugin.getConfig().getInt("stats.distance_update_ticks", 20);
        List<String> statsLore = new ArrayList<>();
        statsLore.add("§7统计： " + (statsEnabled ? "§a已启用" : "§c已禁用"));
        statsLore.add("§7距离追踪： " + (distanceEnabled ? "§a已启用" : "§c已禁用"));
        statsLore.add("§7更新间隔： §f" + distanceTicks + " 刻");
        statsLore.add("");
        statsLore.add("§e点击打开统计设置");
        items.add(clickItem(21, () -> icon(Material.BOOK, "§e§l竞赛统计", statsLore),
                ctxClick -> open(ctxClick.player(), MenuKey.STATS_ROOT, null, false)));

        // 随机任务 ------------------------------------------------
        items.add(clickItem(23, () -> icon(Material.FEATHER, "§d§l随机任务",
                List.of("§7重新分配秘密任务", "§7在回合开始前使用")), ctxClick -> {
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c随机任务前请先停止游戏。");
                        return;
                    }
                    if (taskMode != null) {
                        List<Player> participants = plugin.isDualBodyTaskMode()
                                ? java.util.stream.Stream.concat(plugin.getGameManager().getRunners().stream(),
                                        plugin.getGameManager().getHunters().stream()).toList()
                                : plugin.getGameManager().getRunners();
                        taskMode.assignAndAnnounceTasks(participants);
                        Msg.send(ctxClick.player(),
                                "§a已为当前" + (plugin.isDualBodyTaskMode() ? "参与者" : "速通者") + "重新随机任务。");
                    }
                }));

        // 任务设置与高级 ------------------------------------
        items.add(navigateItem(25, Material.WRITABLE_BOOK, "§6§l任务设置", MenuKey.SETTINGS_TASK,
                "§7暂停规则、默认设置、宽限"));

        items.add(navigateItem(28, Material.EMERALD, "§a§l自定义任务", MenuKey.TASK_CUSTOM,
                "§7创建和编辑条目"));

        int assignments = taskMode != null ? taskMode.getAssignments().size() : 0;
        items.add(navigateItem(30, Material.PAPER, "§e§l当前分配", MenuKey.TASK_ASSIGNMENTS,
                "§7查看谁有每个任务", assignments));

        items.add(navigateItem(32, Material.BOOKSHELF, "§6§l任务池", MenuKey.TASK_POOL,
                "§7启用/禁用单个任务", 0));

        items.add(navigateItem(34, Material.COMPARATOR, "§b§l高级控制", MenuKey.TASK_ADVANCED,
                "§7UI性能、计时器、配置浏览器"));

        return new MenuScreen("§6§l任务竞赛", 45, items);
    }

    private MenuScreen buildTaskSettings(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        items.add(toggleConfigItem(10, Material.REDSTONE_TORCH, "§e§l断开连接时暂停",
                "task_manager.pause_on_disconnect", true,
                "§7任务参与者断开连接时暂停"));

        items.add(toggleConfigItem(11, Material.BARRIER, "§e§l超时时移除",
                "task_manager.remove_on_timeout", true,
                "§7移除超过重新加入宽限的玩家"));

        items.add(toggleConfigItem(12, Material.HOPPER, "§e§l允许迟到加入",
                "task_manager.allow_late_joiners", false,
                "§7允许玩家在游戏中途加入"));

        items.add(toggleConfigItem(13, Material.BOOK, "§e§l包含默认任务",
                "task_manager.include_default_tasks", true,
                "§7在池中保留内置目标"));

        items.add(adjustConfigItem(14, Material.CLOCK, "§6§l重新加入宽限（秒）",
                "task_manager.rejoin_grace_seconds", 180, 10, 30, 10, 3600,
                "§7允许重新连接的秒数"));

        items.add(adjustConfigItem(15, Material.CLOCK, "§6§l最大游戏时长（分钟）",
                "task_manager.max_game_duration", 0,
                5, 15, 0, 360,
                "§70 = 无限时长"));

        items.add(toggleConfigItem(16, Material.NAME_TAG, "§e§l剩一人时结束",
                "task_manager.end_when_one_left", false,
                "§7只剩一名参与者时自动结束"));

        items.add(clickItem(19, () -> icon(Material.NETHERITE_SWORD, "§6§l任务难度",
                List.of("§7当前池：§f"
                        + (plugin.getTaskManagerMode() != null ? plugin.getTaskManagerMode().getDifficultyFilter().name()
                                : "MEDIUM"),
                        "§e点击循环 简单 → 中等 → 困难")), ctxClick -> {
                    TaskManagerMode mode = plugin.getTaskManagerMode();
                    if (mode == null) {
                        Msg.send(ctxClick.player(), "§c任务管理器不可用。");
                        return;
                    }
                    if (plugin.getGameManager().isGameRunning()) {
                        Msg.send(ctxClick.player(), "§c更改任务难度前请先停止当前回合。");
                        return;
                    }
                    TaskDifficulty cur = mode.getDifficultyFilter();
                    TaskDifficulty next = switch (cur) {
                        case EASY -> TaskDifficulty.MEDIUM;
                        case MEDIUM -> TaskDifficulty.HARD;
                        case HARD -> TaskDifficulty.EASY;
                    };
                    mode.setDifficultyFilter(next);
                    Msg.send(ctxClick.player(), "§a任务难度已设置为 §f" + next.name());
                    ctxClick.reopen();
                }));

        items.add(toggleConfigItem(20, Material.AMETHYST_SHARD, "§e§l玩家任务重掷",
                "task_manager.reroll.enabled", true,
                "§7允许玩家重掷自己的任务"));

        items.add(adjustConfigItem(21, Material.EXPERIENCE_BOTTLE, "§6§l每位玩家重掷次数",
                "task_manager.reroll.uses_per_player", 1,
                1, 1, 0, 5,
                "§70 禁用玩家重掷"));

        items.add(toggleConfigItem(22, Material.LIME_DYE, "§e§l允许开始前重掷",
                "task_manager.reroll.allow_before_start", true,
                "§7玩家可以在开始前重掷预分配的任务"));

        items.add(toggleConfigItem(23, Material.CLOCK, "§e§l允许第一回合期间重掷",
                "task_manager.reroll.allow_during_first_turn", true,
                "§7共享控制：直到玩家的第一回合结束"));

        items.add(adjustConfigItem(24, Material.RECOVERY_COMPASS, "§6§l任务竞赛窗口（秒）",
                "task_manager.reroll.task_race_window_seconds", 60,
                15, 30, 0, 600,
                "§7任务竞赛的重掷窗口开启时间"));

        items.add(simpleItem(29, () -> icon(Material.MACE, "§6§l多人任务大师",
                List.of("§7两个共享身体使用标准交换间隔",
                        "§7为身体A分配速通者，为身体B分配猎人"))));

        items.add(simpleItem(31, () -> icon(Material.PAPER, "§7玩家快捷方式",
                List.of("§7玩家可以使用 §e/swap complete reroll confirm",
                        "§7或在任务中心Shift点击 §e你的任务"))));

        return new MenuScreen("§6§l任务设置", 45, items);
    }

    private MenuScreen buildTaskCustom(MenuContext ctx) {
        TaskManagerMode mode = plugin.getTaskManagerMode();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        items.add(simpleItem(2, () -> icon(Material.BOOK, "§7默认池",
                List.of("§7包含： "
                        + (plugin.getConfig().getBoolean("task_manager.include_default_tasks", true) ? "§a是"
                                : "§c否"),
                        "§7在任务设置中切换"))));

        items.add(clickItem(8,
                () -> icon(Material.EMERALD_BLOCK, "§a§l添加自定义任务", List.of("§7通过聊天输入ID")), ctxClick -> {
                    plugin.getChatInputHandler().expectTaskId(ctxClick.player());
                    ctxClick.player().closeInventory();
                    Msg.send(ctxClick.player(), "§e在聊天中输入唯一的任务ID。");
                }));

        if (mode != null) {
            List<String> ids = mode.getCustomTaskIds();
            int slot = 9;
            for (String id : ids) {
                TaskDefinition def = mode.getTask(id);
                String description = def != null ? def.description() : "";
                items.add(clickItem(slot, () -> icon(Material.PAPER, "§e" + id,
                        List.of("§7" + description, "", "§c点击移除")), ctxClick -> {
                            if (mode.removeCustomTask(id)) {
                                Msg.send(ctxClick.player(), "§c已移除自定义任务 §f" + id);
                            }
                            ctxClick.reopen();
                        }));
                slot++;
                if ((slot + 1) % 9 == 0)
                    slot += 2;
                if (slot >= 54)
                    break;
            }
        }

        return new MenuScreen("§6§l自定义任务", 54, items);
    }

    private MenuScreen buildTaskRunners(MenuContext ctx) {
        GameManager gm = plugin.getGameManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        List<String> taskRunnerLore = new ArrayList<>();
        taskRunnerLore.add("§7仅速通者。猎人未使用");
        taskRunnerLore.add("§7Shift点击移除玩家");
        if (plugin.getConfigManager().isAssignmentRestrictedToSessionWorld()) {
            taskRunnerLore.add("§7分配世界：§f"
                    + Optional.ofNullable(gm.getSessionWorldName()).orElse(ctx.player().getWorld().getName()));
        }
        if (plugin.getConfigManager().isTeamSelectorSameWorldOnly()) {
            taskRunnerLore.add("§b世界过滤：§f" + ctx.player().getWorld().getName());
        } else {
            taskRunnerLore.add("§7世界过滤：§f所有在线玩家");
        }
        items.add(simpleItem(2, () -> icon(Material.BOOK, "§6§l任务竞赛", taskRunnerLore)));

        items.add(clickItem(6, () -> icon(Material.BARRIER, "§c§l清除速通者",
                List.of("§7移除所有速通者分配")), ctxClick -> {
                    Set<Player> affected = new HashSet<>(gm.getRunners());
                    for (Player player : affected) {
                        gm.assignPlayerToTeam(player, Team.NONE);
                        if (player != ctxClick.player()) {
                            Msg.send(player, "§e你被 §f" + ctxClick.player().getName() + " §e从速通者中移除");
                        }
                    }
                    Msg.send(ctxClick.player(), "§c已清除所有速通者。");
                    ctxClick.reopen();
                }));

        List<Player> candidates = getTeamSelectionCandidates(ctx.player());
        int slot = 9;
        for (Player online : candidates) {
            if (slot >= 54)
                break;
            boolean isRunner = gm.isRunner(online);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(online);
            GuiCompat.setDisplayName(meta, (isRunner ? "§b" : "§7") + online.getName());
            GuiCompat.setLore(meta, List.of(
                    isRunner ? "§a当前是速通者" : "§7未分配",
                    "§7世界：§f" + online.getWorld().getName(),
                    "§7点击：分配为速通者",
                    "§7Shift点击：清除"));
            head.setItemMeta(meta);

            items.add(clickItem(slot, () -> head, ctxClick -> {
                boolean remove = ctxClick.shift();
                Team target = remove ? Team.NONE : Team.RUNNER;
                World referenceWorld = gm.getSessionWorld();
                if (referenceWorld == null) {
                    referenceWorld = ctxClick.player().getWorld();
                }
                String reason = gm.getAssignmentRestrictionReason(online, target, referenceWorld);
                if (reason != null) {
                    Msg.send(ctxClick.player(), "§c" + reason);
                    return;
                }
                boolean changed = gm.assignPlayerToTeam(online, target, referenceWorld);
                if (!changed) {
                    Msg.send(ctxClick.player(), "§e§f" + online.getName() + " §e无变化");
                } else if (remove) {
                    Msg.send(ctxClick.player(), "§e已将 §f" + online.getName() + " §e从速通者中移除。");
                    if (online != ctxClick.player()) {
                        Msg.send(online, "§e你被 §f" + ctxClick.player().getName() + " §e从速通者中移除");
                    }
                } else {
                    Msg.send(ctxClick.player(), "§a已将 §f" + online.getName() + " §a添加为速通者。");
                    if (online != ctxClick.player()) {
                        Msg.send(online, "§e你被 §f" + ctxClick.player().getName() + " §e分配为速通者");
                    }
                }
                ctxClick.reopen();
            }));

            slot++;
            if ((slot + 1) % 9 == 0)
                slot += 2;
        }

        return new MenuScreen("§b§l速通者管理", 54, items);
    }

    private MenuScreen buildMultiworldSettings(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        String sessionWorld = Optional.ofNullable(plugin.getGameManager().getSessionWorldName()).orElse("无");
        boolean mvCore = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core") != null;
        boolean mvInv = plugin.getServer().getPluginManager().getPlugin("Multiverse-Inventories") != null;
        items.add(simpleItem(4, () -> icon(Material.MAP, "§b§l兼容性状态",
                List.of(
                        "§7Multiverse-Core： " + (mvCore ? "§a已检测到" : "§7未检测到"),
                        "§7Multiverse-Inventories： " + (mvInv ? "§a已检测到" : "§7未检测到"),
                        "§7会话世界： §f" + sessionWorld,
                        "§7使用此菜单保持重生在游戏世界中"))));

        items.add(toggleItem(10, Material.ENDER_EYE, "§e§l多世界兼容",
                cfg::isMultiworldCompatibilityEnabled,
                cfg::setMultiworldCompatibilityEnabled,
                "§7多世界支持的主开关"));

        items.add(toggleItem(12, Material.RESPAWN_ANCHOR, "§e§l会话世界重生",
                cfg::isKeepRunnersInSessionWorldEnabled,
                cfg::setKeepRunnersInSessionWorldEnabled,
                "§7优先使用活跃游戏的Overworld作为速通者重生点"));

        items.add(toggleItem(14, Material.CHORUS_FRUIT, "§e§l重生后强制传送",
                cfg::isRunnerRespawnEnforcementEnabled,
                cfg::setRunnerRespawnEnforcementEnabled,
                "§7如果其他插件覆盖重生位置，重生后重新传送"));

        items.add(adjustItem(16, Material.CLOCK, "§6§l强制传送延迟",
                cfg::getRunnerRespawnEnforcementDelayTicks,
                cfg::setRunnerRespawnEnforcementDelayTicks,
                1, 5, 1, 40,
                "§7第一次重生纠正前的刻数"));

        items.add(toggleItem(28, Material.PLAYER_HEAD, "§e§l同世界队伍选择器",
                cfg::isTeamSelectorSameWorldOnly,
                cfg::setTeamSelectorSameWorldOnly,
                "§7分配队伍时仅显示当前世界的玩家"));

        items.add(toggleItem(29, Material.IRON_BARS, "§e§l限制分配",
                cfg::isAssignmentRestrictedToSessionWorld,
                cfg::setAssignmentRestrictedToSessionWorld,
                "§7禁止分配会话世界外的玩家"));

        items.add(toggleItem(30, Material.COMPASS, "§e§l更新会话世界",
                cfg::isSessionWorldUpdatesEnabled,
                cfg::setSessionWorldUpdatesEnabled,
                "§7跟随活跃速通者进入其他Overworld风格世界"));

        items.add(simpleItem(32, () -> icon(Material.BOOK,
                "§7这修复了什么",
                List.of(
                        "§7防止回退重生到服务器的第一个世界",
                        "§7帮助Multiverse-Core世界路由",
                        "§7帮助Multiverse-Inventories应用第二个位置时"))));

        return new MenuScreen("§b§l多世界", 45, items);
    }

    private MenuScreen buildTaskPool(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        TaskManagerMode mode = plugin.getTaskManagerMode();
        if (mode == null) {
            items.add(simpleItem(22, () -> icon(Material.BARRIER, "§c任务管理器不可用",
                    List.of("§7任务模式未初始化。"))));
            return new MenuScreen("§6§l任务池", 54, items);
        }

        TaskDefinition[] defs = mode.getAllDefinitions().values().toArray(new TaskDefinition[0]);
        java.util.Arrays.sort(defs, java.util.Comparator.comparing(TaskDefinition::id, String.CASE_INSENSITIVE_ORDER));

        int perPage = 36;
        int totalPages = Math.max(1, (int) Math.ceil(defs.length / (double) perPage));
        int pageIndex = 0;
        if (ctx.request().data() instanceof Integer p) {
            pageIndex = Math.max(0, Math.min(p, totalPages - 1));
        }
        final int page = pageIndex;

        items.add(cycleItem(2, Material.NETHERITE_SWORD, "§6§l难度",
                () -> mode.getDifficultyFilter().name(), current -> {
                    TaskDifficulty cur = mode.getDifficultyFilter();
                    TaskDifficulty next = switch (cur) {
                        case EASY -> TaskDifficulty.MEDIUM;
                        case MEDIUM -> TaskDifficulty.HARD;
                        case HARD -> TaskDifficulty.EASY;
                    };
                    mode.setDifficultyFilter(next);
                    return next.name();
                }, List.of("§7分配时使用的过滤", "§7循环 简单 → 中等 → 困难")));

        items.add(simpleItem(4, () -> icon(Material.PAPER, "§7符合条件的任务",
                List.of("§a" + mode.getCandidateCount() + " §7可供选择"))));

        items.add(clickItem(6, () -> icon(Material.ENDER_CHEST, "§e§l重载tasks.yml",
                List.of("§7重新读取任务定义")), ctxClick -> {
                    mode.reloadTasksFromFile();
                    Msg.send(ctxClick.player(), "§a已重载tasks.yml。");
                    open(ctxClick.player(), MenuKey.TASK_POOL, page, true);
                }));

        int start = page * perPage;
        int end = Math.min(defs.length, start + perPage);
        for (int i = start; i < end; i++) {
            TaskDefinition def = defs[i];
            int displayIndex = i - start;
            int row = displayIndex / 9;
            int col = displayIndex % 9;
            int slot = 9 + row * 9 + col;
            String id = def.id();
            TaskDefinition current = mode.getTask(id);
            boolean enabled = current == null || current.enabled();
            Material mat = enabled ? Material.WRITABLE_BOOK : Material.GRAY_DYE;
            List<String> lore = new ArrayList<>();
            lore.add("§7" + Optional.ofNullable(def.description()).orElse("无描述"));
            lore.add("§7难度： §f" + (def.difficulty() != null ? def.difficulty().name() : "MEDIUM"));
            if (def.categories() != null && !def.categories().isEmpty()) {
                lore.add("§7标签： §f" + String.join(", ", def.categories()));
            }
            lore.add("");
            lore.add(enabled ? "§a已启用" : "§c已禁用");
            lore.add("§7点击切换");
            items.add(clickItem(slot, () -> icon(mat, (enabled ? "§a" : "§c") + id, lore), ctxClick -> {
                TaskDefinition cur = mode.getTask(id);
                boolean next = cur == null || !cur.enabled();
                mode.setTaskEnabled(id, next);
                Msg.send(ctxClick.player(), "§e任务 §f" + id + " §e现在" + (next ? "§a已启用" : "§c已禁用"));
                open(ctxClick.player(), MenuKey.TASK_POOL, page, true);
            }));
        }

        if (page > 0) {
            items.add(clickItem(45, () -> icon(Material.ARROW, "§7§l上一页",
                    List.of("§7第 " + page + " 页，共 " + totalPages + " 页")),
                    ctxClick -> open(ctxClick.player(), MenuKey.TASK_POOL, page - 1, false)));
        }
        if (page < totalPages - 1) {
            items.add(clickItem(53, () -> icon(Material.ARROW, "§7§l下一页",
                    List.of("§7第 " + (page + 2) + " 页，共 " + totalPages + " 页")),
                    ctxClick -> open(ctxClick.player(), MenuKey.TASK_POOL, page + 1, false)));
        }

        items.add(simpleItem(49, () -> icon(Material.NAME_TAG, "§7页面信息",
                List.of("§7第 §f" + (page + 1) + "§7 页，共 §f" + totalPages + " 页",
                        "§7任务总数：§f" + defs.length))));

        return new MenuScreen("§6§l任务池", 54, items);
    }

    private MenuScreen buildTaskAdvanced(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        items.add(simpleItem(2, () -> icon(Material.PAINTING, "§7性能提示",
                List.of("§7更低的更新率 → 更快的UI刷新", "§7使用这些工具进行微调"))));

        items.add(navigateItem(10, Material.COMPARATOR, "§b§lUI性能", MenuKey.SETTINGS_UI,
                "§7行动栏、标题和粒子颜色"));
        items.add(navigateItem(12, Material.CLOCK, "§6§l交换时机", MenuKey.SETTINGS_SWAP,
                "§7间隔、抖动、宽限期"));
        items.add(navigateItem(14, Material.BOOK, "§e§l统计高级", MenuKey.STATS_ADVANCED,
                "§7距离追踪频率"));
        items.add(navigateItem(16, Material.BELL, "§6§l广播控制", MenuKey.SETTINGS_BROADCAST,
                "§7切换公告"));

        items.add(navigateItem(21, Material.NOTE_BLOCK, "§d§l语音聊天", MenuKey.SETTINGS_VOICE_CHAT,
                "§7Simple Voice Chat集成"));
        items.add(navigateItem(23, Material.CHEST, "§a§l装备管理器", MenuKey.KIT_MANAGER,
                "§7快速装备测试"));
        items.add(navigateItem(25, Material.ENCHANTED_BOOK, "§6§l任务设置", MenuKey.SETTINGS_TASK,
                "§7断开连接时暂停、默认设置"));

        items.add(clickItem(31, () -> icon(Material.PAPER, "§e§l配置浏览器",
                List.of("§7在聊天中输入配置路径", "§7格式：path=value", "§7示例：swap.interval=75")),
                ctxClick -> {
                    ctxClick.player().closeInventory();
                    Msg.send(ctxClick.player(), "§e输入配置路径和值（path=value）或'cancel'取消。");
                    plugin.getChatInputHandler().expectConfigString(ctxClick.player(), "__dynamic__");
                }));

        return new MenuScreen("§b§l高级控制", 45, items);
    }

    private MenuItem buildParticleTypeCycler(int slot) {
        return cycleItem(slot, Material.FIREWORK_STAR, "§6§l粒子类型",
                plugin.getConfigManager()::getParticleTrailType,
                current -> {
                    int idx = PARTICLE_TYPES.indexOf(current == null ? "" : current.toUpperCase(Locale.ROOT));
                    int nextIndex = (idx + 1) % PARTICLE_TYPES.size();
                    String type = PARTICLE_TYPES.get(nextIndex);
                    plugin.getConfig().set("particle_trail.type", type);
                    return type;
                },
                List.of("§7循环允许的粒子ID"));
    }

    private List<Player> getTeamSelectionCandidates(Player viewer) {
        List<Player> candidates = new ArrayList<>();
        if (viewer == null) {
            return candidates;
        }
        boolean sameWorldOnly = plugin.getConfigManager().isTeamSelectorSameWorldOnly();
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean assigned = plugin.getGameManager().isRunner(online) || plugin.getGameManager().isHunter(online);
            if (!sameWorldOnly || online.getWorld().equals(viewer.getWorld()) || assigned) {
                candidates.add(online);
            }
        }
        candidates.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    private MenuScreen buildTaskAssignments(MenuContext ctx) {
        TaskManagerMode mode = plugin.getTaskManagerMode();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§lBack", MenuKey.TASK_HOME, null, this::openTaskManagerMenu));

        if (mode != null) {
            int slot = 9;
            for (Map.Entry<UUID, String> entry : mode.getAssignments().entrySet()) {
                UUID uuid = entry.getKey();
                String taskId = entry.getValue();
                String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName())
                        .orElse(uuid.toString().substring(0, 8));
                TaskDefinition def = mode.getTask(taskId);
                String desc = def != null ? def.description() : "未知任务";
                items.add(simpleItem(slot, () -> icon(Material.PAPER, "§e" + name,
                        List.of("§7任务：§f" + taskId, "§7" + desc))));
                slot++;
                if ((slot + 1) % 9 == 0)
                    slot += 2;
                if (slot >= 54)
                    break;
            }
        }

        return new MenuScreen("§6§l任务分配", 54, items);
    }

    private MenuScreen buildVoiceChat(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));
        items.add(toggleConfigItem(11, Material.NOTE_BLOCK, "§e§l语音聊天集成",
                "voice_chat.enabled", false,
                "§7与Simple Voice Chat集成"));
        items.add(toggleConfigItem(13, Material.LEVER, "§e§l静音非活跃速通者",
                "voice_chat.mute_inactive_runners", true,
                "§7自动静音非活跃玩家"));
        return new MenuScreen("§d§l语音聊天", 27, items);
    }

    private MenuScreen buildBroadcast(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));
        items.add(toggleConfigItem(11, Material.BELL, "§e§l公告", "broadcasts.enabled", true,
                "§7启用一般公告"));
        items.add(toggleConfigItem(13, Material.MAP, "§e§l游戏事件", "broadcasts.game_events", true,
                "§7公告开始/停止"));
        items.add(toggleConfigItem(15, Material.PAPER, "§e§l队伍变更", "broadcasts.team_changes", true,
                "§7公告队伍分配变更"));
        return new MenuScreen("§e§l公告设置", 27, items);
    }

    private MenuScreen buildEndGameMessages(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(simpleItem(4, () -> icon(Material.NAME_TAG, "§6§l可编辑的游戏结束文本",
                List.of("§7自定义获胜者标题/副标题和广播",
                        "§c创作者鸣谢/支持文本已故意锁定"))));

        items.add(editTextConfigItem(10, Material.LIME_BANNER, "§a速通者胜利标题",
                "messages.end_game.runner.title", "§a§l速通者胜利！"));
        items.add(editTextConfigItem(11, Material.LIME_DYE, "§a速通者胜利（速通者副标题）",
                "messages.end_game.runner.subtitle_runner", "§e你们太专注了，干得好"));
        items.add(editTextConfigItem(12, Material.YELLOW_DYE, "§e速通者胜利（猎人副标题）",
                "messages.end_game.runner.subtitle_hunter", "§e你们太专注了，干得好"));

        items.add(editTextConfigItem(19, Material.RED_BANNER, "§c猎人胜利标题",
                "messages.end_game.hunter.title", "§c§l猎人胜利！"));
        items.add(editTextConfigItem(20, Material.RED_DYE, "§c猎人胜利（速通者副标题）",
                "messages.end_game.hunter.subtitle_runner", "§e你不是主角，大叔"));
        items.add(editTextConfigItem(21, Material.ORANGE_DYE, "§6猎人胜利（猎人副标题）",
                "messages.end_game.hunter.subtitle_hunter", "§e那些速通者太菜了"));

        items.add(editTextConfigItem(28, Material.BARRIER, "§7无获胜者标题",
                "messages.end_game.none.title", "§c§l游戏结束"));
        items.add(editTextConfigItem(29, Material.GRAY_DYE, "§7无获胜者（速通者副标题）",
                "messages.end_game.none.subtitle_runner", "§e未宣布获胜者。"));
        items.add(editTextConfigItem(30, Material.LIGHT_GRAY_DYE, "§7无获胜者（猎人副标题）",
                "messages.end_game.none.subtitle_hunter", "§e未宣布获胜者。"));

        items.add(editTextConfigItem(32, Material.PAPER, "§e结束广播消息",
                "messages.end_game.broadcast", "§a[SpeedrunnerSwap] 游戏结束！%winner%"));
        items.add(simpleItem(41, () -> icon(Material.BOOK, "§7广播占位符",
                List.of("§f%winner% §7→ \"速通者队伍获胜！\" / \"猎人队伍获胜！\" / \"游戏结束！\"",
                        "§f%winner_team% §7→ 速通者 / 猎人 / 无"))));

        return new MenuScreen("§6§l游戏结束消息", 45, items);
    }

    private MenuScreen buildUiSettings(MenuContext ctx) {
        ConfigManager cfg = plugin.getConfigManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(adjustItem(11, Material.CLOCK, "§6§l行动栏更新",
                cfg::getActionBarUpdateTicks,
                value -> plugin.getConfig().set("ui.update_ticks.actionbar", value),
                5, 20, 1, 200,
                "§7行动栏刷新间隔（刻）"));

        items.add(adjustItem(13, Material.EXPERIENCE_BOTTLE, "§6§l标题更新",
                cfg::getTitleUpdateTicks,
                value -> plugin.getConfig().set("ui.update_ticks.title", value),
                1, 5, 1, 200,
                "§7标题刷新间隔（刻）"));

        items.add(cycleItem(20, Material.CLOCK, "§6§l速通者计时器", cfg::getRunnerTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setRunnerTimerVisibility(next);
                    return next;
                }, timerLore("活跃速通者可见性")));
        items.add(cycleItem(22, Material.CLOCK, "§6§l等待计时器", cfg::getWaitingTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setWaitingTimerVisibility(next);
                    return next;
                }, timerLore("等待中速通者可见性")));
        items.add(cycleItem(24, Material.CLOCK, "§6§l猎人计时器", cfg::getHunterTimerVisibility,
                current -> {
                    String next = nextVisibility(current);
                    cfg.setHunterTimerVisibility(next);
                    return next;
                }, timerLore("猎人可见性")));

        items.add(toggleItem(29, Material.BLAZE_POWDER, "§e§l粒子轨迹",
                plugin.getConfigManager()::isParticleTrailEnabled,
                value -> plugin.getConfig().set("particle_trail.enabled", value),
                "§7切换速通者粒子轨迹"));

        items.add(adjustItem(31, Material.REDSTONE, "§6§l生成间隔",
                plugin.getConfigManager()::getParticleSpawnInterval,
                value -> plugin.getConfig().set("particle_trail.spawn_interval", Math.max(1, value)),
                1, 5, 1, 200,
                "§7轨迹生成间隔（刻）"));

        items.add(buildParticleTypeCycler(33));

        items.add(adjustItem(39, Material.RED_DYE, "§c红色通道",
                () -> plugin.getConfigManager().getParticleTrailColor()[0],
                value -> updateParticleColorChannel(0, value),
                5, 20, 0, 255,
                "§7调整红色强度"));
        items.add(adjustItem(40, Material.GREEN_DYE, "§a绿色通道",
                () -> plugin.getConfigManager().getParticleTrailColor()[1],
                value -> updateParticleColorChannel(1, value),
                5, 20, 0, 255,
                "§7调整绿色强度"));
        items.add(adjustItem(41, Material.LAPIS_LAZULI, "§9蓝色通道",
                () -> plugin.getConfigManager().getParticleTrailColor()[2],
                value -> updateParticleColorChannel(2, value),
                5, 20, 0, 255,
                "§7调整蓝色强度"));

        return new MenuScreen("§b§lUI与计时器", 45, items);
    }

    private MenuScreen buildKitManager(MenuContext ctx) {
        List<MenuItem> items = new ArrayList<>();
        items.add(backButton(0, "§7§l返回", MenuKey.SETTINGS_HOME, null, this::openSettingsMenu));

        items.add(toggleItem(11, Material.CHEST, "§e§l装备已启用",
                plugin.getConfigManager()::isKitsEnabled,
                value -> plugin.getConfigManager().setKitsEnabled(value),
                "§7切换开始时装备分配"));

        items.add(clickItem(13,
                () -> icon(Material.DIAMOND_SWORD, "§a§l给予速通者装备", List.of("§7装备配置的速通者装备")),
                ctxClick -> plugin.getKitManager().applyRunnerKit(ctxClick.player())));

        items.add(clickItem(15,
                () -> icon(Material.IRON_SWORD, "§c§l给予猎人装备", List.of("§7装备配置的猎人装备")),
                ctxClick -> plugin.getKitManager().applyHunterKit(ctxClick.player())));

        items.add(simpleItem(31, () -> icon(Material.PAPER, "§7编辑装备",
                List.of("§7在kits.yml中编辑内容", "§7或使用/swap kits命令"))));

        return new MenuScreen("§a§l装备管理器", 36, items);
    }

    // -----------------------------------------------------------------
    // Helper item factories

    private MenuItem simpleItem(int slot, Supplier<ItemStack> icon) {
        return new MenuItem("static-" + slot, slot, ctx -> icon.get(), null);
    }

    private MenuItem clickItem(int slot, Supplier<ItemStack> icon, Consumer<MenuClickContext> action) {
        return new MenuItem("click-" + slot + "-" + UUID.randomUUID(), slot, ctx -> icon.get(), action);
    }

    private MenuItem backButton(int slot, String label, MenuKey target, Object data, Consumer<Player> handler) {
        return clickItem(slot, () -> icon(Material.ARROW, label, List.of("§7返回")), ctx -> {
            popHistory(ctx.player());
            if (handler != null) {
                handler.accept(ctx.player());
                return;
            }
            if (target != null) {
                open(ctx.player(), target, data, false);
                return;
            }
            Deque<MenuRequest> stack = history.get(ctx.player().getUniqueId());
            if (stack == null || stack.isEmpty()) {
                ctx.player().closeInventory();
                return;
            }
            MenuRequest previous = stack.peek();
            open(ctx.player(), previous.key(), previous.data(), false);
        });
    }

    private void popHistory(Player player) {
        Deque<MenuRequest> stack = history.get(player.getUniqueId());
        if (stack != null && !stack.isEmpty()) {
            stack.pop();
        }
    }

    private MenuItem navigateItem(int slot, Material material, String name, MenuKey target, String description) {
        return navigateItem(slot, material, name, target, description, null);
    }

    private MenuItem navigateItem(int slot, Material material, String name, MenuKey target, String description,
            Object data) {
        return clickItem(slot, () -> icon(material, name, List.of("§7" + description)),
                ctx -> open(ctx.player(), target, data, false));
    }

    private MenuItem toggleItem(int slot, Material material, String label, BooleanSupplier getter,
            Consumer<Boolean> setter, String description) {
        return clickItem(slot, () -> {
            boolean enabled = getter.getAsBoolean();
            String status = enabled ? "§a已启用" : "§c已禁用";
            return icon(material, label + ": " + status, description == null ? List.of("§7点击切换")
                    : List.of("§7" + description, "§7点击切换"));
        }, ctx -> {
            boolean next = !getter.getAsBoolean();
            setter.accept(next);
            plugin.saveConfig();
            Msg.send(ctx.player(), "§e" + label.replace("§", "") + ": " + (next ? "§a已启用" : "§c已禁用"));
            ctx.reopen();
        });
    }

    private MenuItem toggleConfigItem(int slot, Material material, String label, String path, boolean def,
            String description) {
        return toggleItem(slot, material, label,
                () -> plugin.getConfig().getBoolean(path, def),
                value -> {
                    plugin.getConfig().set(path, value);
                },
                description);
    }

    private MenuItem adjustItem(int slot, Material material, String label, IntSupplier getter, Consumer<Integer> setter,
            int step, int shiftStep, int min, int max, String description) {
        return clickItem(slot, () -> icon(material, label + " §f" + getter.getAsInt(),
                List.of("§7" + description,
                        "§7左/右键：±" + step,
                        "§7Shift：±" + shiftStep)),
                ctx -> {
                    int value = getter.getAsInt();
                    int delta = ctx.shift() ? shiftStep : step;
                    if (ctx.click() == ClickType.LEFT)
                        value += delta;
                    else if (ctx.click() == ClickType.RIGHT)
                        value -= delta;
                    value = Math.max(min, Math.min(max, value));
                    setter.accept(value);
                    plugin.saveConfig();
                    Msg.send(ctx.player(), "§e" + label.replace("§", "") + ": §f" + value);
                    ctx.reopen();
                });
    }

    private MenuItem adjustConfigItem(int slot, Material material, String label, String path, int def,
            int step, int shiftStep, int min, int max, String description) {
        return adjustItem(slot, material, label,
                () -> plugin.getConfig().getInt(path, def),
                value -> plugin.getConfig().set(path, value),
                step, shiftStep, min, max, description);
    }

    private MenuItem editTextConfigItem(int slot, Material material, String label, String path, String fallback) {
        return clickItem(slot, () -> {
            String current = plugin.getConfig().getString(path, fallback);
            List<String> lore = new ArrayList<>();
            lore.add("§7当前：§f" + previewText(current));
            lore.add("");
            lore.add("§7点击在聊天中编辑");
            lore.add("§7使用 §f& §7（或§符号代码）设置颜色");
            lore.add("§7输入 §fclear §7清空文本");
            return icon(material, label, lore);
        }, ctx -> {
            ctx.player().closeInventory();
            Msg.send(ctx.player(), "§e正在编辑 §f" + path + "§e。");
            Msg.send(ctx.player(), "§7在聊天中输入新文本。");
            Msg.send(ctx.player(), "§7输入 §fcancel §7取消，或 §fclear §7清空。");
            plugin.getChatInputHandler().expectConfigString(ctx.player(), path);
        });
    }

    private String previewText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<blank>";
        }
        String clean = TextUtil.stripColors(raw.replace('&', '§'));
        if (clean == null || clean.isBlank()) {
            return "<colored text>";
        }
        return clean.length() > 42 ? clean.substring(0, 42) + "..." : clean;
    }

    private void updateParticleColorChannel(int channel, int value) {
        int[] rgb = plugin.getConfigManager().getParticleTrailColor();
        if (channel < 0 || channel >= rgb.length) {
            return;
        }
        int clamped = Math.max(0, Math.min(255, value));
        rgb[channel] = clamped;
        plugin.getConfig().set("particle_trail.color", Arrays.asList(rgb[0], rgb[1], rgb[2]));
    }

    private MenuItem cycleItem(int slot, Material material, String label, Supplier<String> getter,
            Function<String, String> cycler, List<String> description) {
        return clickItem(slot, () -> icon(material, label + ": §f" + getter.get(), description), ctx -> {
            String next = cycler.apply(getter.get());
            plugin.saveConfig();
            Msg.send(ctx.player(), "§e" + label.replace("§", "") + ": §f" + next);
            ctx.reopen();
        });
    }

    private String nextVisibility(String current) {
        if (current == null)
            return "always";
        return switch (current.toLowerCase(Locale.ROOT)) {
            case "always" -> "last_10";
            case "last_10" -> "never";
            default -> "always";
        };
    }

    private List<String> timerLore(String title) {
        return List.of("§7可见性：always（始终）、last_10（最后10秒）、never（从不）", "§7当前调整：§f" + title);
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        GuiCompat.setDisplayName(meta, name);
        if (lore != null && !lore.isEmpty())
            GuiCompat.setLore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }

    // -----------------------------------------------------------------
    // Supporting records and enums

    private enum MenuKey {
        MAIN,
        MODE_SELECT,
        MODE_SELECT_DIRECT,
        TEAM_MANAGEMENT,
        SETTINGS_HOME,
        SETTINGS_SWAP,
        SETTINGS_SAFETY,
        SETTINGS_HUNTER,
        POWERUPS_ROOT,
        POWERUPS_EFFECTS,
        POWERUPS_DURATION,
        DANGEROUS_BLOCKS,
        SETTINGS_WORLD_BORDER,
        SETTINGS_BOUNTY,
        SETTINGS_LAST_STAND,
        SETTINGS_SUDDEN_DEATH,
        SETTINGS_TASK,
        TASK_HOME,
        TASK_CUSTOM,
        TASK_POOL,
        TASK_ASSIGNMENTS,
        TASK_RUNNERS,
        TASK_ADVANCED,
        STATS_ROOT,
        STATS_ADVANCED,
        SETTINGS_MULTIWORLD,
        SETTINGS_VOICE_CHAT,
        SETTINGS_BROADCAST,
        SETTINGS_END_MESSAGES,
        SETTINGS_UI,
        KIT_MANAGER
    }

    public enum StatsParent {
        MAIN,
        SETTINGS
    }

    private interface MenuBuilder {
        MenuScreen build(MenuContext context);
    }

    private record MenuRequest(MenuKey key, Object data) {
    }

    private record MenuSession(MenuRequest request, MenuScreen screen, Inventory inventory) {
    }

    private record MenuScreen(String title, int size, List<MenuItem> items) {
        MenuItem button(String id) {
            for (MenuItem item : items) {
                if (item.id().equals(id))
                    return item;
            }
            return null;
        }
    }

    private record MenuItem(String id, int slot, Function<MenuContext, ItemStack> icon,
            Consumer<MenuClickContext> action) {
    }

    private static class MenuContext {
        private final GuiManager manager;
        private final Player player;
        private final MenuRequest request;

        MenuContext(GuiManager manager, Player player, MenuRequest request) {
            this.manager = manager;
            this.player = player;
            this.request = request;
        }

        public GuiManager manager() {
            return manager;
        }

        public Player player() {
            return player;
        }

        public MenuRequest request() {
            return request;
        }
    }

    private static final class MenuClickContext extends MenuContext {
        private final boolean shift;
        private final ClickType click;

        MenuClickContext(GuiManager manager, Player player, MenuRequest request, boolean shift, ClickType click) {
            super(manager, player, request);
            this.shift = shift;
            this.click = click;
        }

        public boolean shift() {
            return shift;
        }

        public ClickType click() {
            return click;
        }

        public void reopen() {
            manager().reopen(player());
        }
    }

}
