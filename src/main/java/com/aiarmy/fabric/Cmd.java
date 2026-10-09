package com.aiarmy.fabric;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;
import java.util.UUID;

/** Helpers for running vanilla commands silently from the mod. */
final class Cmd {
    private Cmd() {}

    /** Runs a vanilla command silently, in the dimension of the given level, with server permissions. */
    static void run(ServerLevel level, String command) {
        MinecraftServer server = level.getServer();
        String dim = level.dimension().identifier().toString();
        CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "execute in " + dim + " run " + command);
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String xyz(double x, double y, double z) {
        return num(x) + " " + num(y) + " " + num(z);
    }

    /** UUID in the SNBT int-array form used by entity data, e.g. [I;1,2,3,4]. */
    static String uuidArray(UUID id) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
    }
}
