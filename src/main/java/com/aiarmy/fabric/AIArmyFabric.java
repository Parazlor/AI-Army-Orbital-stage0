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

            dispatcher.register(Commands.literal("aiarmy")
                    .requires(op)
                    .then(Commands.literal("create")
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 20))
                                    .executes(ctx -> create(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "amount")))
                                    .then(Commands.argument("skin", StringArgumentType.word())
                                            .executes(ctx -> create(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "amount"))))))
                    .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                    .then(Commands.literal("delete")
                            .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                    .executes(ctx -> delete(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "army")))))
                    .then(Commands.literal("tell")
                            .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                    .then(Commands.argument("message", StringArgumentType.greedyString())
                                            .executes(ctx -> tell(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "army"),
                                                    StringArgumentType.getString(ctx, "message")))))));
        });
    }

    // ---------------------------------------------------------------- army commands

    private int create(CommandSourceStack source, int amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Выполни команду из игры.")); return 0; }
        ServerLevel level = (ServerLevel) player.level();
        int armyId = nextArmyId;
        String tag = "aiarmy_army_" + armyId;
        int spawned = 0;
        BlockPos base = player.blockPosition();
        for (int i = 0; i < amount; i++) {
            double angle = i * GOLDEN_ANGLE;
            int radius = 2 + i / 8;
            int x = base.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, base.getY(), z));
            if (!level.getBlockState(spawn).isAir() || !level.getBlockState(spawn.above()).isAir()) continue;
            String nick = "ArmyUnit" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            run(level, "summon minecraft:villager " + xyz(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5)
                    + " {CustomName:\"" + nick + "\",CustomNameVisible:1b,PersistenceRequired:1b,Tags:[\"aiarmy\",\"" + tag + "\"]}");
            spawned++;
        }
        if (spawned == 0) { source.sendFailure(Component.literal("Не удалось найти безопасные позиции.")); return 0; }
        nextArmyId++;
        armies.put(armyId, spawned);
        orders.put(armyId, "IDLE");
        int count = spawned;
        source.sendSuccess(() -> Component.literal("Создана ARMY-" + armyId + ": " + count + " NPC-прокси."), false);
        source.sendSuccess(() -> Component.literal("Прототип: это Villager, не fake-player; приказами пока не управляют."), false);
        return spawned;
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

    private int tell(CommandSourceStack source, int id, String message) {
        if (!armies.containsKey(id)) { source.sendFailure(Component.literal("Такой армии нет.")); return 0; }
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
