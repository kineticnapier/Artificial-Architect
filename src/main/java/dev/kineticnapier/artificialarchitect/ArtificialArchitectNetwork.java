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
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 1_800_000;

    private static final String PROTOCOL_VERSION = "2";
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

    public static int compressedSize(String json) {
        return compressForTransfer(json, "JSON").length;
    }

    public static int openSaveDialog(ServerPlayer player, String json) {
        byte[] compressed = compressForTransfer(json, "world.json");
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SaveWorldJsonPacket(compressed));
        return compressed.length;
    }

    public static void openActionsDialog(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenActionsDialogPacket());
    }

    public static void submitActionsToServer(String json) {
        byte[] compressed = compressForTransfer(json, "actions.json");
        CHANNEL.sendToServer(new SubmitActionsJsonPacket(compressed));
    }

    private static byte[] compressForTransfer(String json, String name) {
        try {
            byte[] compressed = JsonGzip.compress(json, MAX_JSON_BYTES);
            if (compressed.length > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException(
                        name + " のgzip転送サイズが上限を超えています: "
                                + compressed.length + " > " + MAX_COMPRESSED_BYTES + " bytes"
                );
            }
            return compressed;
        } catch (IOException e) {
            throw new IllegalArgumentException(name + " をgzip圧縮できません: " + e.getMessage(), e);
        }
    }

    private static void handleSaveWorldJson(SaveWorldJsonPacket message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                String json = JsonGzip.decompress(message.compressedJson(), MAX_JSON_BYTES);
                ClientFileDialogs.saveWorldJson(json);
            } catch (Exception e) {
                ClientFileDialogs.clientMessage(
                        "Artificial Architect: world.json のgzip展開に失敗しました: " + e.getMessage()
                );
                e.printStackTrace();
            }
        });
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
            String json = JsonGzip.decompress(message.compressedJson(), MAX_JSON_BYTES);
            ActionExecutor.ApplyResult result = ActionExecutor.apply(sender.serverLevel(), json);
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

    public record SaveWorldJsonPacket(byte[] compressedJson) {
        private static void encode(SaveWorldJsonPacket message, FriendlyByteBuf buffer) {
            buffer.writeByteArray(message.compressedJson);
        }

        private static SaveWorldJsonPacket decode(FriendlyByteBuf buffer) {
            return new SaveWorldJsonPacket(buffer.readByteArray(MAX_COMPRESSED_BYTES));
        }
    }

    public record OpenActionsDialogPacket() {
        private static void encode(OpenActionsDialogPacket message, FriendlyByteBuf buffer) {
        }

        private static OpenActionsDialogPacket decode(FriendlyByteBuf buffer) {
            return new OpenActionsDialogPacket();
        }
    }

    public record SubmitActionsJsonPacket(byte[] compressedJson) {
        private static void encode(SubmitActionsJsonPacket message, FriendlyByteBuf buffer) {
            buffer.writeByteArray(message.compressedJson);
        }

        private static SubmitActionsJsonPacket decode(FriendlyByteBuf buffer) {
            return new SubmitActionsJsonPacket(buffer.readByteArray(MAX_COMPRESSED_BYTES));
        }
    }
}
