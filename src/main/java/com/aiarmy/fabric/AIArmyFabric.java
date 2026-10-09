package com.aiarmy.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;
import java.util.function.Predicate;

/**
 * AI Army — singleplayer Fabric prototype for Minecraft 26.2 (Mojang names).
 *
 * Orbital wands live in {@link OrbitalSystem}. Army units are Villager proxies,
 * not fake players; they are spawned and removed through vanilla commands.
 */
public final class AIArmyFabric implements ModInitializer {
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final List<String> SKINS = List.of("random", "steve", "black");
    private static final int SPAWN_RADIUS = 64;

    private final Map<Integer, Integer> armies = new LinkedHashMap<>(); // army id -> unit count
    private final Map<Integer, String> orders = new HashMap<>();
    private final OrbitalSystem orbital = new OrbitalSystem();
    private int nextArmyId = 1;

    @Override
    public void onInitialize() {
        orbital.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            Predicate<CommandSourceStack> op = PermissionPredicates.require(
                    PermissionNode.of("aiarmy", "command"), PermissionLevel.GAMEMASTERS);

            orbital.registerCommands(dispatcher);

            // Command names follow section 55 of the spec.
            dispatcher.register(Commands.literal("aiarmycreate")
                    .requires(op)
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                            .then(Commands.argument("skin", StringArgumentType.word())
                                    .suggests((ctx, b) -> { for (String s : SKINS) b.suggest(s); return b.buildFuture(); })
                                    .executes(ctx -> create(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "skin"))))));

            dispatcher.register(Commands.literal("aiarmylist")
                    .requires(op)
                    .executes(ctx -> list(ctx.getSource())));

            dispatcher.register(Commands.literal("aiarmydelete")
                    .requires(op)
                    .then(Commands.argument("army", IntegerArgumentType.integer(1))
                            .executes(ctx -> delete(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "army")))));

            // /aiarmy tell <army> <message>  and  /aiarmy tell <army>-<unit> <message>
            dispatcher.register(Commands.literal("aiarmy")
                    .requires(op)
                    .then(Commands.literal("tell")
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .then(Commands.argument("message", StringArgumentType.greedyString())
                                            .executes(ctx -> tell(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "target"),
                                                    StringArgumentType.getString(ctx, "message")))))));
        });
    }

    // ---------------------------------------------------------------- army commands

    private int create(CommandSourceStack source, int amount, String skin) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Выполни команду из игры.")); return 0; }
        if (!SKINS.contains(skin)) {
            source.sendFailure(Component.literal("Скин должен быть: random, steve или black.")); return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos base = player.blockPosition();

        // Section 16: safe positions within 64 blocks; if there are not enough, report an error and spawn nothing.
        List<BlockPos> spots = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int maxTries = (int) (Math.PI * SPAWN_RADIUS * SPAWN_RADIUS * 1.5);
        for (int i = 1; i <= maxTries && spots.size() < amount; i++) {
            double r = 2 + (SPAWN_RADIUS - 2) * Math.sqrt((double) i / maxTries);
            double angle = i * GOLDEN_ANGLE;
            int x = base.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * r);
            if (!used.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
            BlockPos spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, base.getY(), z));
            BlockPos below = spawn.below();
            if (!level.getBlockState(spawn).isAir() || !level.getBlockState(spawn.above()).isAir()) continue;
            if (!level.getFluidState(below).isEmpty()) continue;
            spots.add(spawn);
        }
        if (spots.size() < amount) {
            int found = spots.size();
            source.sendFailure(Component.literal("Недостаточно безопасных позиций в радиусе " + SPAWN_RADIUS
                    + " блоков: найдено " + found + ", нужно " + amount + ". Армия не создана."));
            return 0;
        }

        int armyId = nextArmyId++;
        String tag = "aiarmy_army_" + armyId;
        for (BlockPos spawn : spots) {
            String nick = "ArmyUnit" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            run(level, "summon minecraft:villager " + xyz(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5)
                    + " {CustomName:\"" + nick + "\",CustomNameVisible:1b,PersistenceRequired:1b,Tags:[\"aiarmy\",\"" + tag + "\"]}");
        }
        armies.put(armyId, amount);
        orders.put(armyId, "IDLE");
        source.sendSuccess(() -> Component.literal("Создана ARMY-" + armyId + ": " + amount + " NPC-прокси (скин: " + skin + ")."), false);
        source.sendSuccess(() -> Component.literal("Прототип: это Villager, не fake-player; скины и приказы пока не применяются."), false);
        return amount;
    }

    private int list(CommandSourceStack source) {
        if (armies.isEmpty()) source.sendSuccess(() -> Component.literal("Армий нет."), false);
        armies.forEach((id, units) -> source.sendSuccess(() -> Component.literal(
                "ARMY-" + id + ": " + units + " юнитов; order=" + orders.getOrDefault(id, "IDLE")), false));
        return armies.size();
    }

    private int delete(CommandSourceStack source, int id) {
        Integer units = armies.remove(id);
        orders.remove(id);
        if (units == null) { source.sendFailure(Component.literal("Такой армии нет.")); return 0; }
        MinecraftServer server = source.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "kill @e[tag=aiarmy_army_" + id + "]");
        source.sendSuccess(() -> Component.literal("Удалена ARMY-" + id + "."), false);
        return units;
    }

    private int tell(CommandSourceStack source, String target, String message) {
        int dash = target.indexOf('-');
        int id;
        try { id = Integer.parseInt(dash < 0 ? target : target.substring(0, dash)); }
        catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Формат: /aiarmy tell <army> <message> или /aiarmy tell <army>-<unit> <message>")); return 0;
        }
        if (!armies.containsKey(id)) { source.sendFailure(Component.literal("Такой армии нет.")); return 0; }
        if (dash >= 0) {
            source.sendFailure(Component.literal("Индивидуальные приказы (" + target + ") пока не поддерживаются: юниты ещё не пронумерованы."));
            return 0;
        }
        orders.put(id, message);
        source.sendSuccess(() -> Component.literal("Приказ сохранён для ARMY-" + id + ": " + message), false);
        source.sendSuccess(() -> Component.literal("Исполнение приказов AI в этой сборке ещё не реализовано."), false);
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a vanilla command silently, in the dimension of the given level, with server permissions. */
    private static void run(ServerLevel level, String command) {
        MinecraftServer server = level.getServer();
        String dim = level.dimension().identifier().toString();
        CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "execute in " + dim + " run " + command);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String xyz(double x, double y, double z) {
        return num(x) + " " + num(y) + " " + num(z);
    }
}
