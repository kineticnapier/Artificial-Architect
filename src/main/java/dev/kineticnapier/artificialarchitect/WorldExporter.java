package dev.kineticnapier.artificialarchitect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class WorldExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WorldExporter() {}

    public static ExportResult export(ServerPlayer player, int radius) throws IOException {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        String snapshotId = UUID.randomUUID().toString();

        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("snapshotId", snapshotId);
        root.addProperty("dimension", level.dimension().location().toString());
        root.addProperty("facing", player.getDirection().getName());
        root.add("origin", vec(origin.getX(), origin.getY(), origin.getZ()));

        JsonObject bounds = new JsonObject();
        bounds.add("min", vec(-radius, -radius, -radius));
        bounds.add("max", vec(radius, radius, radius));
        root.add("bounds", bounds);

        // blocks に存在しない座標は air と解釈する。
        root.addProperty("defaultBlock", "minecraft:air");

        JsonArray blocks = new JsonArray();
        int nonAirCount = 0;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (key == null) {
                        continue;
                    }

                    JsonObject block = new JsonObject();
                    block.add("p", vec(dx, dy, dz));
                    block.addProperty("block", key.toString());
                    blocks.add(block);
                    nonAirCount++;
                }
            }
        }

        root.add("blocks", blocks);

        String json = GSON.toJson(root);
        Path output = ArtificialArchitectPaths.worldJson();
        Files.writeString(output, json, StandardCharsets.UTF_8);

        return new ExportResult(output, snapshotId, nonAirCount, (radius * 2 + 1), json);
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    public record ExportResult(
            Path path,
            String snapshotId,
            int nonAirBlocks,
            int sideLength,
            String json
    ) {}
}
