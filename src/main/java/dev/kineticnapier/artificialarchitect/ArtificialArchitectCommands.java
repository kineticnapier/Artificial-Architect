package dev.kineticnapier.artificialarchitect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.nio.charset.StandardCharsets;

public final class ArtificialArchitectCommands {
    private ArtificialArchitectCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(commandTree("architect"));
        // Compatibility alias for the original MVP command.
        dispatcher.register(commandTree("aibridge"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> commandTree(String name) {
        return Commands.literal(name)
                .then(Commands.literal("dump")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ArtificialArchitectCommands::dump)))
                .then(Commands.literal("apply")
                        .executes(ArtificialArchitectCommands::apply));
    }

    private static int dump(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int radius = IntegerArgumentType.getInteger(context, "radius");

        try {
            ServerPlayer player = source.getPlayerOrException();
            WorldExporter.ExportResult result = WorldExporter.export(player, radius);

            int rawBytes = result.json().getBytes(StandardCharsets.UTF_8).length;
            int compressedBytes = ArtificialArchitectNetwork.openSaveDialog(player, result.json());
            source.sendSuccess(
                    () -> Component.literal(
                            "Artificial Architect: world.json の保存ダイアログを開きました"
                                    + " | side=" + result.sideLength()
                                    + " | nonAir=" + result.nonAirBlocks()
                                    + " | transfer=" + formatBytes(rawBytes) + " -> " + formatBytes(compressedBytes) + " gzip"
                                    + " | snapshotId=" + result.snapshotId()
                    ),
                    false
            );
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Artificial Architect dump failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static int apply(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerPlayer player = source.getPlayerOrException();
            ArtificialArchitectNetwork.openActionsDialog(player);
            source.sendSuccess(
                    () -> Component.literal("Artificial Architect: actions.json のファイルダイアログを開きました。"),
                    false
            );
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Artificial Architect apply failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static String formatBytes(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MiB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
