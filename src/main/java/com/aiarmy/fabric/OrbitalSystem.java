package com.aiarmy.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Orbital System — section 58 of the AI Army spec v1.2.
 * Wands: Nuke, Stab, Strike, Stasis, Wolf, Boss.
 *
 * Entities are created with vanilla commands (/summon, /tp) through a silent server
 * command source, scheduled over ticks to control server load.
 */
public final class OrbitalSystem {
    private static final Logger LOG = LoggerFactory.getLogger("aiarmy-orbital");

    static final String WAND_PREFIX = "AI Army Orbital: ";
    static final List<String> WANDS = List.of("Nuke", "Stab", "Strike", "Stasis", "Wolf", "Boss");
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    // ---- 58.1 Nuke geometry: 8 rings, 660 TNT + 1 central = 661.
    // Radii were computed so that, with a nominal explosion zone radius of 4 blocks
    // (TNT explosion power), neighbouring ring bands overlap (No Safe Spots on flat ground)
    // while rings stay ~7 blocks apart (geometry stays readable). Verified at startup.
    static final int[] NUKE_COUNTS = {24, 40, 56, 72, 88, 104, 120, 156};
    static final double[] NUKE_RADII = {7.6, 15.0, 22.3, 29.6, 36.9, 44.1, 51.3, 58.6};
    static final double NUKE_ZONE_RADIUS = 4.0;
    static final int NUKE_DROP_HEIGHT = 50;
    static final double NUKE_START_VY = -0.15;

    // ---- 58.4 Boss geometry: 1 central + 99 in rings.
    static final int[] BOSS_COUNTS = {9, 15, 21, 25, 29};
    static final double[] BOSS_RADII = {4, 7, 10, 13, 16};

    private record Pending(String kind, InteractionHand hand, ItemStack stack) {}

    private final Config cfg = new Config();
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** Cooldowns for Stab, Wolf, Boss, Stasis: key = player UUID + wand, value = tick when ready. */
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** Sliding-window limits for Nuke/Strike, per army: key = armyKey + wand, value = usage ticks. */
    private final Map<String, Deque<Long>> armyUsage = new HashMap<>();
    /** Load-spreading scheduler: tick -> tasks. */
    private final TreeMap<Long, List<Runnable>> schedule = new TreeMap<>();
    private long tick = 0;
    private String bossType = "Warden";

    // ================================================================= setup

    void register() {
        cfg.load();
        verifyNukeGeometry();
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { schedule.clear(); pending.clear(); });
        UseItemCallback.EVENT.register(this::onUseItem);
    }

    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        Predicate<CommandSourceStack> op = PermissionPredicates.require(
                PermissionNode.of("aiarmy", "command"), PermissionLevel.GAMEMASTERS);

        // Only OP can give wands.
        dispatcher.register(Commands.literal("aiarmywands")
                .requires(op)
                .executes(ctx -> giveWands(ctx.getSource())));

        // OP only. Stasis teleports the player who ran the command.
        dispatcher.register(Commands.literal("aiarmyorbital")
                .requires(op)
                .then(Commands.literal("Boss")
                        .then(Commands.literal("Warden").executes(ctx -> setBossType(ctx.getSource(), "Warden")))
                        .then(Commands.literal("Wither").executes(ctx -> setBossType(ctx.getSource(), "Wither"))))
                .then(Commands.literal("Stasis")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> stasis(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))));
    }

    // ================================================================= wand identification

    /** Function is bound to the exact expected item name; any rename disables it. */
    static String wandKind(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.FISHING_ROD)) return null;
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom == null) return null;
        String name = custom.getString();
        if (!name.startsWith(WAND_PREFIX)) return null;
        String kind = name.substring(WAND_PREFIX.length());
        return WANDS.contains(kind) ? kind : null;
    }

    private int giveWands(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Выполни команду из игры.")); return 0; }
        for (String wand : WANDS) {
            ItemStack stack = new ItemStack(Items.FISHING_ROD);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(WAND_PREFIX + wand));
            List<Component> hint = switch (wand) {
                case "Stasis" -> List.of(Component.literal("Держи жезл в руке и введи:"),
                        Component.literal("/aiarmyorbital Stasis <x> <y> <z>"));
                case "Wolf" -> List.of(Component.literal("ПКМ дважды: 50 волков рядом с тобой"),
                        Component.literal("Смена слота отменяет операцию"));
                case "Boss" -> List.of(Component.literal("ПКМ дважды; цель — блок под прицелом"),
                        Component.literal("Тип: /aiarmyorbital Boss <Warden|Wither>"),
                        Component.literal("Смена слота отменяет операцию"));
                default -> List.of(Component.literal("ПКМ — начать, наведи прицел на цель"),
                        Component.literal("ПКМ ещё раз — подтвердить"),
                        Component.literal("Смена слота отменяет операцию"));
            };
            stack.set(DataComponents.LORE, new ItemLore(hint));
            player.getInventory().placeItemBackInInventory(stack);
        }
        source.sendSuccess(() -> Component.literal("Выданы 6 орбитальных жезлов AI Army."), false);
        return WANDS.size();
    }

    // ================================================================= right click flow

    private InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                        net.minecraft.world.level.Level level, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String kind = wandKind(stack);
        if (kind == null) return InteractionResult.PASS;
        // Our wand never casts the fishing rod, on either side.
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;

        if (kind.equals("Stasis")) {
            msg(sp, "Stasis: держи жезл и введи /aiarmyorbital Stasis <x> <y> <z>.", ChatFormatting.LIGHT_PURPLE);
            return InteractionResult.SUCCESS;
        }

        Pending p = pending.get(sp.getUUID());
        if (p == null || p.hand() != hand || p.stack() != stack || !p.kind().equals(kind)) {
            pending.put(sp.getUUID(), new Pending(kind, hand, stack));
            String what = kind.equals("Wolf")
                    ? "нажми ПКМ ещё раз этим же жезлом, чтобы призвать волков."
                    : "наведи прицел на блок-цель и нажми ПКМ ещё раз этим же жезлом.";
            msg(sp, kind + ": " + what + " Смена слота отменяет операцию.", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }
        pending.remove(sp.getUUID());
        confirm(sp, hand, stack, kind);
        return InteractionResult.SUCCESS;
    }

    /** Second right click: target is the block under the crosshair at THIS moment. */
    private void confirm(ServerPlayer player, InteractionHand hand, ItemStack stack, String kind) {
        ServerLevel level = (ServerLevel) player.level();

        BlockPos target = null;
        if (!kind.equals("Wolf")) {
            HitResult hit = player.pick(cfg.wandRange, 1.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bh)) {
                msg(player, kind + ": под прицелом нет блока (дальность " + (int) cfg.wandRange + "). Операция отменена.", ChatFormatting.RED);
                return;
            }
            target = bh.getBlockPos();
        }

        // Cooldowns / army limits are checked before anything is spawned; the wand is kept on refusal.
        if (!checkAndConsumeLimits(player, kind)) return;

        switch (kind) {
            case "Nuke" -> nuke(level, target);
            case "Stab" -> stab(level, target);
            case "Strike" -> strike(level, target, player.getUUID());
            case "Wolf" -> wolves(level, player);
            case "Boss" -> bosses(level, target);
            default -> { return; }
        }
        breakWand(player, stack, kind);
    }

    private void onTick(MinecraftServer server) {
        tick++;
        // 58: slot change cancels an unfinished operation.
        if (!pending.isEmpty()) {
            Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Pending> e = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) { it.remove(); continue; }
                if (p.getItemInHand(e.getValue().hand()) != e.getValue().stack()) {
                    it.remove();
                    msg(p, "Операция жезла " + e.getValue().kind() + " отменена: выбран другой слот.", ChatFormatting.GRAY);
                }
            }
        }
        // Scheduled spawns.
        while (!schedule.isEmpty() && schedule.firstKey() <= tick) {
            for (Runnable r : schedule.pollFirstEntry().getValue()) {
                try { r.run(); } catch (RuntimeException ex) { LOG.warn("Orbital task failed", ex); }
            }
        }
    }

    private void later(long delayTicks, Runnable task) {
        schedule.computeIfAbsent(tick + Math.max(0, delayTicks), k -> new ArrayList<>()).add(task);
    }

    // ================================================================= limits

    /**
     * Army that uses the wand, or null if the user is not an AI army.
     * Human players are not an army, so the Nuke/Strike army limits do not apply to them.
     * When AI units start using wands, return their army id here.
     */
    private static String armyKey(ServerPlayer player) {
        return null;
    }

    private boolean checkAndConsumeLimits(ServerPlayer player, String kind) {
        switch (kind) {
            case "Nuke" -> { return armyLimit(player, kind, cfg.nukeLimit, cfg.nukeWindowSec); }
            case "Strike" -> { return armyLimit(player, kind, cfg.strikeLimit, cfg.strikeWindowSec); }
            default -> { return personalCooldown(player, kind); }
        }
    }

    /** 58.1 / 58.5 / 58.7: limit per army in a sliding window — never global, never per human player. */
    private boolean armyLimit(ServerPlayer player, String kind, int limit, int windowSec) {
        String army = armyKey(player);
        if (army == null) return true;
        String key = army + "|" + kind;
        Deque<Long> uses = armyUsage.computeIfAbsent(key, k -> new ArrayDeque<>());
        long window = windowSec * 20L;
        while (!uses.isEmpty() && tick - uses.peekFirst() >= window) uses.pollFirst();
        if (uses.size() >= limit) {
            long waitSec = (window - (tick - uses.peekFirst()) + 19) / 20;
            msg(player, kind + ": лимит " + limit + " за " + windowSec / 60 + " мин для армии исчерпан. Подожди ещё " + waitSec + " с.", ChatFormatting.RED);
            return false;
        }
        uses.addLast(tick);
        return true;
    }

    /** 58.7: Stab, Wolf, Boss, Stasis — 20–30 s. */
    private boolean personalCooldown(ServerPlayer player, String kind) {
        String key = player.getUUID() + "|" + kind;
        long ready = cooldowns.getOrDefault(key, 0L);
        if (tick < ready) {
            msg(player, kind + ": перезарядка, ещё " + ((ready - tick + 19) / 20) + " с.", ChatFormatting.RED);
            return false;
        }
        cooldowns.put(key, tick + cfg.cooldownSec * 20L);
        return true;
    }

    /** 58: after a successful use the wand breaks, Mending or not. */
    private void breakWand(ServerPlayer player, ItemStack stack, String kind) {
        stack.shrink(1);
        Cmd.run((ServerLevel) player.level(), "playsound minecraft:entity.item.break player " + player.getStringUUID());
        msg(player, "Жезл " + kind + " применён и сломан.", ChatFormatting.GOLD);
    }

    // ================================================================= 58.1 Nuke

    private void nuke(ServerLevel level, BlockPos target) {
        double cx = target.getX() + 0.5, cz = target.getZ() + 0.5;
        nukeTnt(level, cx, cz);
        for (int ring = 0; ring < NUKE_COUNTS.length; ring++) {
            int n = NUKE_COUNTS[ring];
            double r = NUKE_RADII[ring];
            double phase = ring % 2 == 0 ? 0 : Math.PI / n; // stagger neighbouring rings
            for (int i = 0; i < n; i++) {
                double a = phase + 2 * Math.PI * i / n;
                nukeTnt(level, cx + Math.cos(a) * r, cz + Math.sin(a) * r);
            }
        }
    }

    /** TNT 50 blocks above the surface at its own (x, z), with a fuse that ends exactly on landing. */
    private void nukeTnt(ServerLevel level, double x, double z) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        double y = surface + NUKE_DROP_HEIGHT;
        int fuse = fallTicks(NUKE_DROP_HEIGHT, NUKE_START_VY);
        Cmd.run(level, "summon minecraft:tnt " + Cmd.xyz(x, y, z)
                + " {fuse:" + fuse + "s,Motion:[0.0," + Cmd.num(NUKE_START_VY) + ",0.0],Tags:[\"aiarmy_orbital\"]}");
    }

    /**
     * Ticks a primed TNT needs to fall the given height, replicating vanilla motion:
     * each tick gravity 0.04 is applied, the entity moves, then velocity is multiplied by 0.98.
     * The fuse is decremented after the move, so fuse == landing tick means detonation on contact.
     */
    static int fallTicks(double height, double startVy) {
        double y = height, v = startVy;
        for (int t = 1; t < 1000; t++) {
            v -= 0.04;
            y += v;
            if (y <= 0) return t;
            v *= 0.98;
        }
        return 80;
    }

    /** Startup self-check of the No Safe Spots geometry and the 661 TNT count. */
    private static void verifyNukeGeometry() {
        int total = 1;
        double covered = NUKE_ZONE_RADIUS;
        boolean ok = true;
        for (int i = 0; i < NUKE_COUNTS.length; i++) {
            total += NUKE_COUNTS[i];
            double chord = 2 * Math.PI * NUKE_RADII[i] / NUKE_COUNTS[i];
            if (chord >= 2 * NUKE_ZONE_RADIUS) { ok = false; break; }
            double half = Math.sqrt(NUKE_ZONE_RADIUS * NUKE_ZONE_RADIUS - chord * chord / 4);
            if (NUKE_RADII[i] - half > covered) { ok = false; break; }
            covered = NUKE_RADII[i] + half;
        }
        if (total != 661 || !ok) LOG.error("Nuke geometry check FAILED: total={} coverageOk={}", total, ok);
        else LOG.info("Nuke geometry OK: 661 TNT, continuous coverage up to {} blocks", String.format(Locale.ROOT, "%.1f", covered));
    }

    // ================================================================= 58.2 Stab

    /** Narrow vertical shaft from the highest point of the zone down to bedrock. */
    private void stab(ServerLevel level, BlockPos target) {
        int x = target.getX(), z = target.getZ();
        int top = Integer.MIN_VALUE;
        for (int dx = -cfg.stabZoneRadius; dx <= cfg.stabZoneRadius; dx++)
            for (int dz = -cfg.stabZoneRadius; dz <= cfg.stabZoneRadius; dz++)
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz));
        double cx = x + 0.5, cz = z + 0.5;
        // One column, one explosion wide. All charges detonate together (no knock-back scatter).
        for (int y = top; y > level.getMinY(); y -= cfg.stabInterval) {
            Cmd.run(level, "summon minecraft:tnt " + Cmd.xyz(cx, y, cz)
                    + " {fuse:" + cfg.stabFuse + "s,NoGravity:1b,Tags:[\"aiarmy_orbital\"]}");
        }
    }

    // ================================================================= 58.5 Strike

    /**
     * Waves of blue (dangerous) wither skulls. Each wave flies in parallel from its own direction;
     * the targets of one wave fill a disc on a Vogel spiral, so there are no planned gaps.
     */
    private void strike(ServerLevel level, BlockPos target, UUID owner) {
        double cx = target.getX() + 0.5, cz = target.getZ() + 0.5;
        int perWave = cfg.strikePerWave;
        double discR = cfg.strikeSpacing * Math.sqrt(perWave / Math.PI);
        String ownerTag = ",Owner:" + Cmd.uuidArray(owner);
        for (int wave = 0; wave < cfg.strikeWaves; wave++) {
            double az = 2 * Math.PI * wave / cfg.strikeWaves + 0.3;
            double el = Math.toRadians(30 + 25 * ((wave % 3) / 2.0)); // 30°, 42.5°, 55°
            double dx = -Math.cos(az) * Math.cos(el), dy = -Math.sin(el), dz = -Math.sin(az) * Math.cos(el);
            final int w = wave;
            later((long) wave * cfg.strikeWaveIntervalTicks, () -> {
                for (int i = 0; i < perWave; i++) {
                    double r = discR * Math.sqrt((i + 0.5) / perWave);
                    double a = i * GOLDEN_ANGLE + w;
                    double tx = cx + Math.cos(a) * r, tz = cz + Math.sin(a) * r;
                    double ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(tx), (int) Math.floor(tz)) + 0.5;
                    double sx = tx - dx * cfg.strikeDistance, sy = ty - dy * cfg.strikeDistance, sz = tz - dz * cfg.strikeDistance;
                    double v = cfg.strikeSpeed;
                    Cmd.run(level, "summon minecraft:wither_skull " + Cmd.xyz(sx, sy, sz)
                            + " {dangerous:1b,Motion:[" + Cmd.num(dx * v) + "," + Cmd.num(dy * v) + "," + Cmd.num(dz * v) + "]"
                            + ownerTag + ",Tags:[\"aiarmy_orbital\"]}");
                }
            });
        }
    }

    // ================================================================= 58.3 Wolf

    /** 50 wolves with Strength II, Speed II and Wolf Armor, next to the activator. */
    private void wolves(ServerLevel level, ServerPlayer player) {
        BlockPos base = player.blockPosition();
        String nbt = "{Owner:" + Cmd.uuidArray(player.getUUID()) + ","
                + "equipment:{body:{id:\"minecraft:wolf_armor\",count:1}},"
                + "active_effects:[{id:\"minecraft:strength\",amplifier:1b,duration:12000},"
                + "{id:\"minecraft:speed\",amplifier:1b,duration:12000}],Tags:[\"aiarmy_orbital\"]}";
        for (int i = 0; i < 50; i++) {
            double a = i * GOLDEN_ANGLE, r = 1.5 + Math.sqrt(i) * 0.35;
            double x = base.getX() + 0.5 + Math.cos(a) * r, z = base.getZ() + 0.5 + Math.sin(a) * r;
            BlockPos p = BlockPos.containing(x, base.getY(), z);
            boolean free = level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir();
            double sx = free ? x : player.getX(), sy = free ? base.getY() : player.getY(), sz = free ? z : player.getZ();
            Cmd.run(level, "summon minecraft:wolf " + Cmd.xyz(sx, sy, sz) + " " + nbt);
        }
    }

    // ================================================================= 58.4 Boss

    private int setBossType(CommandSourceStack source, String type) {
        bossType = type;
        source.sendSuccess(() -> Component.literal("Тип Boss-жезла: " + type + ". Боссы появятся при подтверждении жезла."), false);
        return 1;
    }

    /** 1 central boss + 99 in rings, spawned a few per tick to limit load. */
    private void bosses(ServerLevel level, BlockPos target) {
        String type = bossType.equals("Wither") ? "minecraft:wither" : "minecraft:warden";
        double cx = target.getX() + 0.5, cz = target.getZ() + 0.5;
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{cx, cz});
        for (int ring = 0; ring < BOSS_COUNTS.length; ring++) {
            for (int i = 0; i < BOSS_COUNTS[ring]; i++) {
                double a = 2 * Math.PI * i / BOSS_COUNTS[ring];
                points.add(new double[]{cx + Math.cos(a) * BOSS_RADII[ring], cz + Math.sin(a) * BOSS_RADII[ring]});
            }
        }
        for (int i = 0; i < points.size(); i++) {
            double[] pt = points.get(i);
            later(i / Math.max(1, cfg.bossPerTick), () -> {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(pt[0]), (int) Math.floor(pt[1]));
                Cmd.run(level, "summon " + type + " " + Cmd.xyz(pt[0], y, pt[1]) + " {Tags:[\"aiarmy_orbital\"]}");
            });
        }
    }

    // ================================================================= 58.6 Stasis

    private int stasis(CommandSourceStack source, int x, int y, int z) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Выполни команду из игры.")); return 0; }
        InteractionHand hand = null;
        for (InteractionHand h : InteractionHand.values()) {
            if ("Stasis".equals(wandKind(player.getItemInHand(h)))) { hand = h; break; }
        }
        if (hand == null) { source.sendFailure(Component.literal("Stasis: держи жезл Stasis в руке.")); return 0; }

        ServerLevel level = (ServerLevel) player.level();
        if (y <= level.getMinY() || y + 1 > level.getMaxY()) {
            source.sendFailure(Component.literal("Stasis: недопустимая высота.")); return 0;
        }
        BlockPos dest = new BlockPos(x, y, z);
        BlockPos below = dest.below();
        if (!level.getWorldBorder().isWithinBounds(dest)) {
            source.sendFailure(Component.literal("Stasis: точка за границей мира.")); return 0;
        }
        boolean safe = level.getBlockState(dest).isAir()
                && level.getBlockState(dest.above()).isAir()
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && level.getFluidState(below).isEmpty()
                && !level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                && !level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.CACTUS);
        if (!safe) {
            source.sendFailure(Component.literal("Stasis: точка небезопасна (нужны 2 блока воздуха и твёрдый пол без лавы/магмы/кактуса)."));
            return 0;
        }
        if (!personalCooldown(player, "Stasis")) return 0;
        Cmd.run(level, "tp " + player.getStringUUID() + " " + Cmd.xyz(x + 0.5, y, z + 0.5));
        breakWand(player, player.getItemInHand(hand), "Stasis");
        return 1;
    }

    // ================================================================= helpers

    private static void msg(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    /** Settings in config/aiarmy-orbital.properties (created with defaults on first start). */
    static final class Config {
        int cooldownSec = 25;
        int nukeLimit = 3, nukeWindowSec = 300;
        int strikeLimit = 1, strikeWindowSec = 300;
        int strikeWaves = 10, strikePerWave = 100, strikeWaveIntervalTicks = 10;
        double strikeSpacing = 2.0, strikeDistance = 40.0, strikeSpeed = 1.2;
        int stabInterval = 3, stabFuse = 40, stabZoneRadius = 2;
        int bossPerTick = 5;
        double wandRange = 160.0;

        void load() {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("aiarmy-orbital.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) { p.load(in); }
                catch (IOException e) { LOG.warn("Cannot read {}, using defaults", file, e); }
            }
            cooldownSec = clamp(i(p, "cooldown.seconds", cooldownSec), 20, 30);
            nukeLimit = i(p, "nuke.limitPerArmy", nukeLimit);
            nukeWindowSec = i(p, "nuke.windowSeconds", nukeWindowSec);
            strikeLimit = i(p, "strike.limitPerArmy", strikeLimit);
            strikeWindowSec = i(p, "strike.windowSeconds", strikeWindowSec);
            strikeWaves = Math.max(1, i(p, "strike.waves", strikeWaves));
            strikePerWave = Math.max(1, i(p, "strike.perWave", strikePerWave));
            strikeWaveIntervalTicks = Math.max(1, i(p, "strike.waveIntervalTicks", strikeWaveIntervalTicks));
            strikeSpacing = d(p, "strike.spacing", strikeSpacing);
            strikeDistance = d(p, "strike.distance", strikeDistance);
            strikeSpeed = d(p, "strike.speed", strikeSpeed);
            stabInterval = Math.max(1, i(p, "stab.interval", stabInterval));
            stabFuse = Math.max(1, i(p, "stab.fuse", stabFuse));
            stabZoneRadius = Math.max(0, i(p, "stab.zoneRadius", stabZoneRadius));
            bossPerTick = Math.max(1, i(p, "boss.perTick", bossPerTick));
            wandRange = d(p, "wand.range", wandRange);

            p.setProperty("cooldown.seconds", "" + cooldownSec);
            p.setProperty("nuke.limitPerArmy", "" + nukeLimit);
            p.setProperty("nuke.windowSeconds", "" + nukeWindowSec);
            p.setProperty("strike.limitPerArmy", "" + strikeLimit);
            p.setProperty("strike.windowSeconds", "" + strikeWindowSec);
            p.setProperty("strike.waves", "" + strikeWaves);
            p.setProperty("strike.perWave", "" + strikePerWave);
            p.setProperty("strike.waveIntervalTicks", "" + strikeWaveIntervalTicks);
            p.setProperty("strike.spacing", "" + strikeSpacing);
            p.setProperty("strike.distance", "" + strikeDistance);
            p.setProperty("strike.speed", "" + strikeSpeed);
            p.setProperty("stab.interval", "" + stabInterval);
            p.setProperty("stab.fuse", "" + stabFuse);
            p.setProperty("stab.zoneRadius", "" + stabZoneRadius);
            p.setProperty("boss.perTick", "" + bossPerTick);
            p.setProperty("wand.range", "" + wandRange);
            try {
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "AI Army Orbital System"); }
            } catch (IOException e) { LOG.warn("Cannot write {}", file, e); }
        }

        private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
        private static int i(Properties p, String k, int def) {
            try { return Integer.parseInt(p.getProperty(k, "" + def).trim()); } catch (NumberFormatException e) { return def; }
        }
        private static double d(Properties p, String k, double def) {
            try { return Double.parseDouble(p.getProperty(k, "" + def).trim()); } catch (NumberFormatException e) { return def; }
        }
    }
}
