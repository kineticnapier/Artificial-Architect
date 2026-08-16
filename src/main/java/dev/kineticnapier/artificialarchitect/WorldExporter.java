package dev.kineticnapier.artificialarchitect;

import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorldExporter {
    private WorldExporter() {}

    public static ExportResult export(ServerPlayer player, int radius) throws IOException {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        String snapshotId = UUID.randomUUID().toString();
        int sideLength = radius * 2 + 1;

        long buildStart = System.nanoTime();

        StringWriter stringWriter = new StringWriter(Math.min(1 << 20, sideLength * sideLength * 32));
        JsonWriter writer = new JsonWriter(stringWriter);
        writer.setIndent("  ");

        Map<BlockState, SerializedState> stateCache = new IdentityHashMap<>();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int nonAirCount = 0;

        writer.beginObject();
        writer.name("schema").value(1);
        writer.name("snapshotId").value(snapshotId);
        writer.name("dimension").value(level.dimension().location().toString());
        writer.name("facing").value(player.getDirection().getName());
        writeVec(writer, origin.getX(), origin.getY(), origin.getZ());

        writer.name("bounds").beginObject();
        writer.name("min");
        writeVecValue(writer, -radius, -radius, -radius);
        writer.name("max");
        writeVecValue(writer, radius, radius, radius);
        writer.endObject();

        // blocks に存在しない座標は air と解釈する。
        writer.name("defaultBlock").value("minecraft:air");
        writer.name("blocks").beginArray();

        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        for (int dy = -radius; dy <= radius; dy++) {
            int y = originY + dy;
            for (int dz = -radius; dz <= radius; dz++) {
                int z = originZ + dz;
                for (int dx = -radius; dx <= radius; dx++) {
                    mutablePos.set(originX + dx, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) {
                        continue;
                    }

                    SerializedState serialized = stateCache.get(state);
                    if (serialized == null) {
                        serialized = serializeState(state);
                        if (serialized == null) {
                            continue;
                        }
                        stateCache.put(state, serialized);
                    }

                    writer.beginObject();
                    writer.name("p");
                    writeVecValue(writer, dx, dy, dz);
                    writer.name("block").value(serialized.blockId());

                    if (!serialized.properties().isEmpty()) {
                        writer.name("state").beginObject();
                        for (StateProperty property : serialized.properties()) {
                            writer.name(property.name()).value(property.value());
                        }
                        writer.endObject();
                    }

                    writer.endObject();
                    nonAirCount++;
                }
            }
        }

        writer.endArray();
        writer.endObject();
        writer.close();

        String json = stringWriter.toString();
        long buildEnd = System.nanoTime();

        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        Path output = ArtificialArchitectPaths.worldJson();
        long writeStart = System.nanoTime();
        Files.write(output, utf8);
        long writeEnd = System.nanoTime();

        return new ExportResult(
                output,
                snapshotId,
                nonAirCount,
                sideLength,
                json,
                utf8.length,
                stateCache.size(),
                nanosToMillis(buildEnd - buildStart),
                nanosToMillis(writeEnd - writeStart)
        );
    }

    private static SerializedState serializeState(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return null;
        }

        List<StateProperty> properties = new ArrayList<>(state.getProperties().size());
        for (Property<?> property : state.getProperties()) {
            addProperty(properties, state, property);
        }
        return new SerializedState(key.toString(), List.copyOf(properties));
    }

    private static <T extends Comparable<T>> void addProperty(
            List<StateProperty> out,
            BlockState state,
            Property<T> property
    ) {
        T value = state.getValue(property);
        out.add(new StateProperty(property.getName(), property.getName(value)));
    }

    private static void writeVec(JsonWriter writer, int x, int y, int z) throws IOException {
        writer.name("origin");
        writeVecValue(writer, x, y, z);
    }

    private static void writeVecValue(JsonWriter writer, int x, int y, int z) throws IOException {
        writer.beginArray();
        writer.value(x);
        writer.value(y);
        writer.value(z);
        writer.endArray();
    }

    private static long nanosToMillis(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    private record StateProperty(String name, String value) {}

    private record SerializedState(String blockId, List<StateProperty> properties) {}

    public record ExportResult(
            Path path,
            String snapshotId,
            int nonAirBlocks,
            int sideLength,
            String json,
            int rawBytes,
            int uniqueStates,
            long buildJsonMillis,
            long writeMillis
    ) {}
}
