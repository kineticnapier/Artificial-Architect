package dev.kineticnapier.artificialarchitect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.util.function.Supplier;

public final class ArtificialArchitectNetwork {
    public static final int MAX_JSON_CHARS = 900_000;

    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ArtificialArchitectMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private ArtificialArchitectNetwork() {}

    public static void register() {
        int id = 0;

        CHANNEL.messageBuilder(SaveWorldJsonPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SaveWorldJsonPacket::encode)
                .decoder(SaveWorldJsonPacket::decode)
                .consumerMainThread(ArtificialArchitectNetwork::handleSaveWorldJson)
                .add();

        CHANNEL.messageBuilder(OpenActionsDialogPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenActionsDialogPacket::encode)
                .decoder(OpenActionsDialogPacket::decode)
                .consumerMainThread(ArtificialArchitectNetwork::handleOpenActionsDialog)
                .add();

        CHANNEL.messageBuilder(SubmitActionsJsonPacket.class, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SubmitActionsJsonPacket::encode)
                .decoder(SubmitActionsJsonPacket::decode)
                .consumerMainThread(ArtificialArchitectNetwork::handleSubmitActionsJson)
                .add();
    }

    public static boolean canTransfer(String json) {
        return json != null && json.length() <= MAX_JSON_CHARS;
    }

    public static void openSaveDialog(ServerPlayer player, String json) {
        if (!canTransfer(json)) {
            throw new IllegalArgumentException(
                    "world.json がファイルダイアログ転送上限 (" + MAX_JSON_CHARS + " chars) を超えました。radius を小さくしてください。"
            );
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SaveWorldJsonPacket(json));
    }

    public static void openActionsDialog(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenActionsDialogPacket());
    }

    public static void submitActionsToServer(String json) {
        if (!canTransfer(json)) {
            throw new IllegalArgumentException(
                    "actions.json が転送上限 (" + MAX_JSON_CHARS + " chars) を超えています。"
            );
        }
        CHANNEL.sendToServer(new SubmitActionsJsonPacket(json));
    }

    private static void handleSaveWorldJson(SaveWorldJsonPacket message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientFileDialogs.saveWorldJson(message.json()));
    }

    private static void handleOpenActionsDialog(OpenActionsDialogPacket message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientFileDialogs::openActionsJson);
    }

    private static void handleSubmitActionsJson(SubmitActionsJsonPacket message, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender == null) {
            return;
        }

        try {
            ActionExecutor.ApplyResult result = ActionExecutor.apply(sender.serverLevel(), message.json());
            sender.sendSystemMessage(Component.literal(
                    "Artificial Architect: apply 完了 | actions=" + result.actionCount()
                            + " | requested=" + result.requestedPlacements()
                            + " | changed=" + result.changedBlocks()
            ));
        } catch (IOException | ActionExecutor.ValidationException e) {
            sender.sendSystemMessage(Component.literal("Artificial Architect apply rejected: " + e.getMessage()));
        } catch (Exception e) {
            sender.sendSystemMessage(Component.literal("Artificial Architect apply failed: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    public record SaveWorldJsonPacket(String json) {
        private static void encode(SaveWorldJsonPacket message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.json, MAX_JSON_CHARS);
        }

        private static SaveWorldJsonPacket decode(FriendlyByteBuf buffer) {
            return new SaveWorldJsonPacket(buffer.readUtf(MAX_JSON_CHARS));
        }
    }

    public record OpenActionsDialogPacket() {
        private static void encode(OpenActionsDialogPacket message, FriendlyByteBuf buffer) {
        }

        private static OpenActionsDialogPacket decode(FriendlyByteBuf buffer) {
            return new OpenActionsDialogPacket();
        }
    }

    public record SubmitActionsJsonPacket(String json) {
        private static void encode(SubmitActionsJsonPacket message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.json, MAX_JSON_CHARS);
        }

        private static SubmitActionsJsonPacket decode(FriendlyByteBuf buffer) {
            return new SubmitActionsJsonPacket(buffer.readUtf(MAX_JSON_CHARS));
        }
    }
}
