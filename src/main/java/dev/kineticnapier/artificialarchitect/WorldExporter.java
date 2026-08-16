package dev.kineticnapier.artificialarchitect;

import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class WorldExporter {
    private static final int MAX_WORKERS = 8;

    private WorldExporter() {}

    public static ExportResult export(ServerPlayer player, int radius) throws IOException {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        String snapshotId = UUID.randomUUID().toString();
        int sideLength = radius * 2 + 1;

        int minWorldX = origin.getX() - radius;
        int minWorldY = origin.getY() - radius;
        int minWorldZ = origin.getZ() - radius;
        int maxWorldX = origin.getX() + radius;
        int maxWorldY = origin.getY() + radius;
        int maxWorldZ = origin.getZ() + radius;

        long scanStart = System.nanoTime();

        Map<BlockState, Integer> paletteIds = new IdentityHashMap<>();
        List<SerializedState> palette = new ArrayList<>();
        List<SectionSnapshot> snapshots = new ArrayList<>();
        int nonAirCount = 0;
        int scannedSections = 0;
        int skippedAirSections = 0;

        int minChunkX = SectionPos.blockToSectionCoord(minWorldX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxWorldX);
        int minChunkZ = SectionPos.blockToSectionCoord(minWorldZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxWorldZ);
        int minSectionY = SectionPos.blockToSectionCoord(minWorldY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxWorldY);

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int sectionIndex = sectionY - level.getMinSection();
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        continue;
                    }

                    scannedSections++;
                    LevelChunkSection section = sections[sectionIndex];
                    if (section.hasOnlyAir()) {
                        skippedAirSections++;
                        continue;
                    }

                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;

                    int localMinX = Math.max(0, minWorldX - baseX);
                    int localMaxX = Math.min(15, maxWorldX - baseX);
                    int localMinY = Math.max(0, minWorldY - baseY);
                    int localMaxY = Math.min(15, maxWorldY - baseY);
                    int localMinZ = Math.max(0, minWorldZ - baseZ);
                    int localMaxZ = Math.min(15, maxWorldZ - baseZ);

                    if (localMinX > localMaxX || localMinY > localMaxY || localMinZ > localMaxZ) {
                        continue;
                    }

                    int width = localMaxX - localMinX + 1;
                    int height = localMaxY - localMinY + 1;
                    int depth = localMaxZ - localMinZ + 1;
                    int[] states = new int[width * height * depth];
                    int cursor = 0;
                    int sectionNonAir = 0;

                    for (int localY = localMinY; localY <= localMaxY; localY++) {
                        for (int localZ = localMinZ; localZ <= localMaxZ; localZ++) {
                            for (int localX = localMinX; localX <= localMaxX; localX++) {
                                BlockState state = section.getBlockState(localX, localY, localZ);
                                if (state.isAir()) {
                                    states[cursor++] = -1;
                                    continue;
                                }

                                Integer paletteId = paletteIds.get(state);
                                if (paletteId == null) {
                                    SerializedState serialized = serializeState(state);
                                    if (serialized == null) {
                                        states[cursor++] = -1;
                                        continue;
                                    }
                                    paletteId = palette.size();
                                    paletteIds.put(state, paletteId);
                                    palette.add(serialized);
                                }

                                states[cursor++] = paletteId;
                                sectionNonAir++;
                            }
                        }
                    }

                    if (sectionNonAir > 0) {
                        nonAirCount += sectionNonAir;
                        snapshots.add(new SectionSnapshot(
                                baseX + localMinX - origin.getX(),
                                baseY + localMinY - origin.getY(),
                                baseZ + localMinZ - origin.getZ(),
                                width,
                                height,
                                depth,
                                states
                        ));
                    }
                }
            }
        }

        long scanEnd = System.nanoTime();
        long encodeStart = System.nanoTime();

        int workers = Math.max(1, Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "ArtificialArchitect-RLE");
            thread.setDaemon(true);
            return thread;
        });

        List<Future<List<Run>>> futures = new ArrayList<>(snapshots.size());
        try {
            for (SectionSnapshot snapshot : snapshots) {
                futures.add(executor.submit(() -> encodeRuns(snapshot)));
            }

            StringWriter stringWriter = new StringWriter(Math.min(1 << 20, Math.max(4096, snapshots.size() * 256)));
            JsonWriter writer = new JsonWriter(stringWriter);
            writer.setIndent("  ");

            writer.beginObject();
            writer.name("schema").value(2);
            writer.name("snapshotId").value(snapshotId);
            writer.name("dimension").value(level.dimension().location().toString());
            writer.name("facing").value(player.getDirection().getName());
            writer.name("origin");
            writeVecValue(writer, origin.getX(), origin.getY(), origin.getZ());

            writer.name("bounds").beginObject();
            writer.name("min");
            writeVecValue(writer, -radius, -radius, -radius);
            writer.name("max");
            writeVecValue(writer, radius, radius, radius);
            writer.endObject();

            writer.name("defaultBlock").value("minecraft:air");
            writer.name("encoding").value("palette-rle-x-v1");
            writer.name("runFormat").value("[x,y,z,length,paletteIndex], length advances +X");

            writer.name("palette").beginArray();
            for (SerializedState state : palette) {
                writer.beginObject();
                writer.name("block").value(state.blockId());
                if (!state.properties().isEmpty()) {
                    writer.name("state").beginObject();
                    for (StateProperty property : state.properties()) {
                        writer.name(property.name()).value(property.value());
                    }
                    writer.endObject();
                }
                writer.endObject();
            }
            writer.endArray();

            writer.name("runs").beginArray();
            int runCount = 0;
            for (Future<List<Run>> future : futures) {
                List<Run> runs;
                try {
                    runs = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("RLE encode interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException("RLE encode failed", e.getCause());
                }

                for (Run run : runs) {
                    writer.beginArray();
                    writer.value(run.x());
                    writer.value(run.y());
                    writer.value(run.z());
                    writer.value(run.length());
                    writer.value(run.paletteIndex());
                    writer.endArray();
                    runCount++;
                }
            }
            writer.endArray();
            writer.endObject();
            writer.close();

            String json = stringWriter.toString();
            long encodeEnd = System.nanoTime();

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
                    palette.size(),
                    runCount,
                    scannedSections,
                    skippedAirSections,
                    workers,
                    nanosToMillis(scanEnd - scanStart),
                    nanosToMillis(encodeEnd - encodeStart),
                    nanosToMillis(writeEnd - writeStart)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Run> encodeRuns(SectionSnapshot snapshot) {
        List<Run> runs = new ArrayList<>();
        int cursor = 0;

        for (int y = 0; y < snapshot.height(); y++) {
            for (int z = 0; z < snapshot.depth(); z++) {
                int x = 0;
                while (x < snapshot.width()) {
                    int paletteIndex = snapshot.states()[cursor + x];
                    if (paletteIndex < 0) {
                        x++;
                        continue;
                    }

                    int length = 1;
                    while (x + length < snapshot.width()
                            && snapshot.states()[cursor + x + length] == paletteIndex) {
                        length++;
                    }

                    runs.add(new Run(
                            snapshot.minX() + x,
                            snapshot.minY() + y,
                            snapshot.minZ() + z,
                            length,
                            paletteIndex
                    ));
                    x += length;
                }
                cursor += snapshot.width();
            }
        }

        return runs;
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

    private record SectionSnapshot(
            int minX,
            int minY,
            int minZ,
            int width,
            int height,
            int depth,
            int[] states
    ) {}

    private record Run(int x, int y, int z, int length, int paletteIndex) {}

    public record ExportResult(
            Path path,
            String snapshotId,
            int nonAirBlocks,
            int sideLength,
            String json,
            int rawBytes,
            int uniqueStates,
            int runCount,
            int scannedSections,
            int skippedAirSections,
            int workers,
            long scanMillis,
            long encodeMillis,
            long writeMillis
    ) {}
}
