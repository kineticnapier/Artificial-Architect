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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class ArtificialArchitectNetwork {
    public static final int MAX_JSON_BYTES = 128 * 1024 * 1024;
    public static final int MAX_ACTIONS_JSON_BYTES = 16 * 1024 * 1024;

    // Keep each Forge packet comfortably below the old single-packet ceiling.
    public static final int WORLD_TRANSFER_CHUNK_BYTES = 900_000;
    public static final int MAX_WORLD_COMPRESSED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_WORLD_TRANSFER_CHUNKS = 64;
    public static final int MAX_ACTIONS_COMPRESSED_BYTES = 1_800_000;

    private static final String PROTOCOL_VERSION = "3";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ArtificialArchitectMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    // Accessed only from the client main-thread packet consumer.
    private static final Map<UUID, IncomingWorldTransfer> INCOMING_WORLD_TRANSFERS = new HashMap<>();

    private ArtificialArchitectNetwork() {}

    public static void register() {
        int id = 0;

        CHANNEL.messageBuilder(SaveWorldJsonChunkPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SaveWorldJsonChunkPacket::encode)
                .decoder(SaveWorldJsonChunkPacket::decode)
                .consumerMainThread(ArtificialArchitectNetwork::handleSaveWorldJsonChunk)
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

    public static WorldTransferStats openSaveDialog(ServerPlayer player, String json) {
        byte[] compressed = compressWorldForTransfer(json);
        int chunkCount = Math.max(1, (compressed.length + WORLD_TRANSFER_CHUNK_BYTES - 1) / WORLD_TRANSFER_CHUNK_BYTES);
        if (chunkCount > MAX_WORLD_TRANSFER_CHUNKS) {
            throw new IllegalArgumentException(
                    "world.json の転送chunk数が上限を超えています: "
                            + chunkCount + " > " + MAX_WORLD_TRANSFER_CHUNKS
            );
        }

        UUID transferId = UUID.randomUUID();
        for (int index = 0; index < chunkCount; index++) {
            int from = index * WORLD_TRANSFER_CHUNK_BYTES;
            int to = Math.min(compressed.length, from + WORLD_TRANSFER_CHUNK_BYTES);
            byte[] chunk = Arrays.copyOfRange(compressed, from, to);
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new SaveWorldJsonChunkPacket(transferId, index, chunkCount, compressed.length, chunk)
            );
        }
        return new WorldTransferStats(compressed.length, chunkCount);
    }

    public static void openActionsDialog(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenActionsDialogPacket());
    }

    public static void submitActionsToServer(String json) {
        byte[] compressed = compressActionsForTransfer(json);
        CHANNEL.sendToServer(new SubmitActionsJsonPacket(compressed));
    }

    private static byte[] compressWorldForTransfer(String json) {
        try {
            byte[] compressed = JsonGzip.compress(json, MAX_JSON_BYTES);
            if (compressed.length > MAX_WORLD_COMPRESSED_BYTES) {
                throw new IllegalArgumentException(
                        "world.json のgzip転送サイズが上限を超えています: "
                                + compressed.length + " > " + MAX_WORLD_COMPRESSED_BYTES + " bytes"
                );
            }
            return compressed;
        } catch (IOException e) {
            throw new IllegalArgumentException("world.json をgzip圧縮できません: " + e.getMessage(), e);
        }
    }

    private static byte[] compressActionsForTransfer(String json) {
        try {
            byte[] compressed = JsonGzip.compress(json, MAX_ACTIONS_JSON_BYTES);
            if (compressed.length > MAX_ACTIONS_COMPRESSED_BYTES) {
                throw new IllegalArgumentException(
                        "actions.json のgzip転送サイズが上限を超えています: "
                                + compressed.length + " > " + MAX_ACTIONS_COMPRESSED_BYTES + " bytes"
                );
            }
            return compressed;
        } catch (IOException e) {
            throw new IllegalArgumentException("actions.json をgzip圧縮できません: " + e.getMessage(), e);
        }
    }

    private static void handleSaveWorldJsonChunk(
            SaveWorldJsonChunkPacket message,
            Supplier<NetworkEvent.Context> context
    ) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                validateWorldChunkHeader(message);
                IncomingWorldTransfer transfer = INCOMING_WORLD_TRANSFERS.get(message.transferId());
                if (transfer == null) {
                    transfer = new IncomingWorldTransfer(message.chunkCount(), message.totalCompressedBytes());
                    INCOMING_WORLD_TRANSFERS.put(message.transferId(), transfer);
                } else if (transfer.chunkCount != message.chunkCount()
                        || transfer.totalCompressedBytes != message.totalCompressedBytes()) {
                    INCOMING_WORLD_TRANSFERS.remove(message.transferId());
                    throw new IOException("world.json transfer metadata mismatch");
                }

                transfer.add(message.chunkIndex(), message.chunk());
                if (!transfer.complete()) {
                    return;
                }

                INCOMING_WORLD_TRANSFERS.remove(message.transferId());
                byte[] compressed = transfer.join();
                String json = JsonGzip.decompress(compressed, MAX_JSON_BYTES);
                ClientFileDialogs.saveWorldJson(json);
            } catch (Exception e) {
                INCOMING_WORLD_TRANSFERS.remove(message.transferId());
                ClientFileDialogs.clientMessage(
                        "Artificial Architect: world.json のchunk転送/展開に失敗しました: " + e.getMessage()
                );
                e.printStackTrace();
            }
        });
    }

    private static void validateWorldChunkHeader(SaveWorldJsonChunkPacket message) throws IOException {
        if (message.chunkCount() <= 0 || message.chunkCount() > MAX_WORLD_TRANSFER_CHUNKS) {
            throw new IOException("invalid world transfer chunk count: " + message.chunkCount());
        }
        if (message.chunkIndex() < 0 || message.chunkIndex() >= message.chunkCount()) {
            throw new IOException("invalid world transfer chunk index: " + message.chunkIndex());
        }
        if (message.totalCompressedBytes() <= 0 || message.totalCompressedBytes() > MAX_WORLD_COMPRESSED_BYTES) {
            throw new IOException("invalid world transfer compressed size: " + message.totalCompressedBytes());
        }
        if (message.chunk().length <= 0 || message.chunk().length > WORLD_TRANSFER_CHUNK_BYTES) {
            throw new IOException("invalid world transfer chunk size: " + message.chunk().length);
        }
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
            String json = JsonGzip.decompress(message.compressedJson(), MAX_ACTIONS_JSON_BYTES);
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

    public record SaveWorldJsonChunkPacket(
            UUID transferId,
            int chunkIndex,
            int chunkCount,
            int totalCompressedBytes,
            byte[] chunk
    ) {
        private static void encode(SaveWorldJsonChunkPacket message, FriendlyByteBuf buffer) {
            buffer.writeUUID(message.transferId);
            buffer.writeVarInt(message.chunkIndex);
            buffer.writeVarInt(message.chunkCount);
            buffer.writeVarInt(message.totalCompressedBytes);
            buffer.writeByteArray(message.chunk);
        }

        private static SaveWorldJsonChunkPacket decode(FriendlyByteBuf buffer) {
            UUID transferId = buffer.readUUID();
            int chunkIndex = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            int totalCompressedBytes = buffer.readVarInt();
            byte[] chunk = buffer.readByteArray(WORLD_TRANSFER_CHUNK_BYTES);
            return new SaveWorldJsonChunkPacket(
                    transferId,
                    chunkIndex,
                    chunkCount,
                    totalCompressedBytes,
                    chunk
            );
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
            return new SubmitActionsJsonPacket(buffer.readByteArray(MAX_ACTIONS_COMPRESSED_BYTES));
        }
    }

    public record WorldTransferStats(int compressedBytes, int chunkCount) {}

    private static final class IncomingWorldTransfer {
        private final int chunkCount;
        private final int totalCompressedBytes;
        private final byte[][] chunks;
        private int receivedChunks;
        private int receivedBytes;

        private IncomingWorldTransfer(int chunkCount, int totalCompressedBytes) {
            this.chunkCount = chunkCount;
            this.totalCompressedBytes = totalCompressedBytes;
            this.chunks = new byte[chunkCount][];
        }

        private void add(int index, byte[] chunk) throws IOException {
            if (chunks[index] != null) {
                return;
            }
            if ((long) receivedBytes + chunk.length > totalCompressedBytes) {
                throw new IOException("world transfer received more bytes than declared");
            }
            chunks[index] = chunk;
            receivedChunks++;
            receivedBytes += chunk.length;
        }

        private boolean complete() {
            return receivedChunks == chunkCount;
        }

        private byte[] join() throws IOException {
            if (!complete() || receivedBytes != totalCompressedBytes) {
                throw new IOException(
                        "world transfer size mismatch: " + receivedBytes + " != " + totalCompressedBytes
                );
            }
            byte[] joined = new byte[totalCompressedBytes];
            int cursor = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    throw new IOException("world transfer is missing a chunk");
                }
                System.arraycopy(chunk, 0, joined, cursor, chunk.length);
                cursor += chunk.length;
            }
            return joined;
        }
    }
}
