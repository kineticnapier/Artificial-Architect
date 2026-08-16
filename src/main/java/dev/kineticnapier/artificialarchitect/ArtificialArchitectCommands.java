package dev.kineticnapier.artificialarchitect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.io.IOException;

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
            source.sendSuccess(
                    () -> Component.literal(
                            "Artificial Architect: world.json を出力しました: " + result.path()
                                    + " | side=" + result.sideLength()
                                    + " | nonAir=" + result.nonAirBlocks()
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
            ActionExecutor.ApplyResult result = ActionExecutor.apply(source.getLevel());
            source.sendSuccess(
                    () -> Component.literal(
                            "Artificial Architect: apply 完了 | actions=" + result.actionCount()
                                    + " | requested=" + result.requestedPlacements()
                                    + " | changed=" + result.changedBlocks()
                    ),
                    true
            );
            return 1;
        } catch (IOException | ActionExecutor.ValidationException e) {
            source.sendFailure(Component.literal("Artificial Architect apply rejected: " + e.getMessage()));
            return 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Artificial Architect apply failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
}
