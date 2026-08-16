package dev.kineticnapier.artificialarchitect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ActionExecutor {
    public static final int MAX_CHANGES = 4096;

    private ActionExecutor() {}

    /**
     * Debug/compatibility path: apply the cached artificialarchitect/actions.json file.
     */
    public static ApplyResult apply(ServerLevel level) throws IOException, ValidationException {
        Path actionsPath = ArtificialArchitectPaths.actionsJson();
        if (!Files.isRegularFile(actionsPath)) {
            throw new ValidationException("actions.json がありません: " + actionsPath);
        }

        JsonObject world = readCachedWorld();
        JsonObject actionsRoot = readObject(actionsPath);
        return applyParsed(level, world, actionsRoot);
    }

    /**
     * Normal UI path: apply JSON selected on the client and transferred to the server.
     */
    public static ApplyResult apply(ServerLevel level, String actionsJson) throws IOException, ValidationException {
        JsonObject world = readCachedWorld();
        JsonObject actionsRoot = readObject(actionsJson, "actions.json");
        return applyParsed(level, world, actionsRoot);
    }

    private static JsonObject readCachedWorld() throws IOException, ValidationException {
        Path worldPath = ArtificialArchitectPaths.worldJson();
        if (!Files.isRegularFile(worldPath)) {
            throw new ValidationException("world.json がありません。先に /architect dump <radius> を実行してください。");
        }
        return readObject(worldPath);
    }

    private static ApplyResult applyParsed(
            ServerLevel level,
            JsonObject world,
            JsonObject actionsRoot
    ) throws ValidationException {
        requireInt(world, "schema", 1, "world.json");
        requireInt(actionsRoot, "schema", 1, "actions.json");

        String worldSnapshot = requireString(world, "snapshotId", "world.json");
        String actionsSnapshot = requireString(actionsRoot, "snapshotId", "actions.json");
        if (!worldSnapshot.equals(actionsSnapshot)) {
            throw new ValidationException("snapshotId が一致しません。古い actions.json の可能性があります。");
        }

        String dimension = requireString(world, "dimension", "world.json");
        String currentDimension = level.dimension().location().toString();
        if (!dimension.equals(currentDimension)) {
            throw new ValidationException("dimension が一致しません。snapshot=" + dimension + ", current=" + currentDimension);
        }

        Vec3i origin = readVec3(world.get("origin"), "world.origin");
        JsonObject boundsObject = requireObject(world, "bounds", "world.json");
        Vec3i min = readVec3(boundsObject.get("min"), "world.bounds.min");
        Vec3i max = readVec3(boundsObject.get("max"), "world.bounds.max");

        JsonArray actions = requireArray(actionsRoot, "actions", "actions.json");
        List<Placement> placements = new ArrayList<>();

        // 先に全アクションを検証し、問題がなければ初めてワールドを変更する。
        for (int i = 0; i < actions.size(); i++) {
            JsonElement element = actions.get(i);
            if (!element.isJsonObject()) {
                throw new ValidationException("actions[" + i + "] は object である必要があります。");
            }

            JsonObject action = element.getAsJsonObject();
            String type = requireString(action, "type", "actions[" + i + "]");
            BlockState state = resolveBlockState(requireString(action, "block", "actions[" + i + "]"));

            switch (type) {
                case "set" -> {
                    Vec3i p = readVec3(action.get("p"), "actions[" + i + "].p");
                    validateRelative(p, min, max, "actions[" + i + "].p");
                    addPlacement(placements, toWorld(origin, p), state);
                }
                case "fill" -> {
                    Vec3i from = readVec3(action.get("from"), "actions[" + i + "].from");
                    Vec3i to = readVec3(action.get("to"), "actions[" + i + "].to");
                    validateRelative(from, min, max, "actions[" + i + "].from");
                    validateRelative(to, min, max, "actions[" + i + "].to");

                    int minX = Math.min(from.x, to.x);
                    int minY = Math.min(from.y, to.y);
                    int minZ = Math.min(from.z, to.z);
                    int maxX = Math.max(from.x, to.x);
                    int maxY = Math.max(from.y, to.y);
                    int maxZ = Math.max(from.z, to.z);

                    long volume = (long) (maxX - minX + 1)
                            * (maxY - minY + 1)
                            * (maxZ - minZ + 1);
                    if (volume > MAX_CHANGES || placements.size() + volume > MAX_CHANGES) {
                        throw new ValidationException("変更ブロック数が上限 " + MAX_CHANGES + " を超えます。");
                    }

                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            for (int x = minX; x <= maxX; x++) {
                                addPlacement(placements, toWorld(origin, new Vec3i(x, y, z)), state);
                            }
                        }
                    }
                }
                default -> throw new ValidationException("未対応の action type: " + type + " (set / fill のみ対応)");
            }
        }

        for (Placement placement : placements) {
            BlockPos pos = placement.pos;
            if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                throw new ValidationException("建築高度外の座標があります: " + pos.toShortString());
            }
            if (!level.getWorldBorder().isWithinBounds(pos)) {
                throw new ValidationException("world border 外の座標があります: " + pos.toShortString());
            }
            if (!level.hasChunkAt(pos)) {
                throw new ValidationException("未ロードchunkの座標があります: " + pos.toShortString());
            }
        }

        int changed = 0;
        for (Placement placement : placements) {
            if (level.setBlock(placement.pos, placement.state, Block.UPDATE_ALL)) {
                changed++;
            }
        }

        return new ApplyResult(placements.size(), changed, actions.size());
    }

    private static JsonObject readObject(Path path) throws IOException, ValidationException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new ValidationException(path.getFileName() + " のルートは object である必要があります。");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ValidationException(path.getFileName() + " のJSONを解析できません: " + e.getMessage());
        }
    }

    private static JsonObject readObject(String json, String name) throws ValidationException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new ValidationException(name + " のルートは object である必要があります。");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ValidationException(name + " のJSONを解析できません: " + e.getMessage());
        }
    }

    private static void addPlacement(List<Placement> placements, BlockPos pos, BlockState state) throws ValidationException {
        if (placements.size() >= MAX_CHANGES) {
            throw new ValidationException("変更ブロック数が上限 " + MAX_CHANGES + " を超えます。");
        }
        placements.add(new Placement(pos, state));
    }

    private static BlockPos toWorld(Vec3i origin, Vec3i relative) {
        return new BlockPos(origin.x + relative.x, origin.y + relative.y, origin.z + relative.z);
    }

    private static BlockState resolveBlockState(String blockId) throws ValidationException {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) {
            throw new ValidationException("存在しない block ID: " + blockId);
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null) {
            throw new ValidationException("block を取得できません: " + blockId);
        }
        return block.defaultBlockState();
    }

    private static void validateRelative(Vec3i p, Vec3i min, Vec3i max, String name) throws ValidationException {
        if (p.x < min.x || p.x > max.x
                || p.y < min.y || p.y > max.y
                || p.z < min.z || p.z > max.z) {
            throw new ValidationException(name + " がdump範囲外です: [" + p.x + ", " + p.y + ", " + p.z + "]");
        }
    }

    private static Vec3i readVec3(JsonElement element, String name) throws ValidationException {
        if (element == null || !element.isJsonArray()) {
            throw new ValidationException(name + " は [x,y,z] である必要があります。");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new ValidationException(name + " は要素数3である必要があります。");
        }
        try {
            return new Vec3i(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        } catch (RuntimeException e) {
            throw new ValidationException(name + " の座標が整数ではありません。");
        }
    }

    private static String requireString(JsonObject object, String key, String where) throws ValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ValidationException(where + "." + key + " は string である必要があります。");
        }
        return value.getAsString();
    }

    private static void requireInt(JsonObject object, String key, int expected, String where) throws ValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new ValidationException(where + "." + key + " がありません。");
        }
        try {
            int actual = value.getAsInt();
            if (actual != expected) {
                throw new ValidationException(where + "." + key + "=" + actual + " は未対応です。期待値=" + expected);
            }
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new ValidationException(where + "." + key + " は整数である必要があります。");
        }
    }

    private static JsonObject requireObject(JsonObject object, String key, String where) throws ValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new ValidationException(where + "." + key + " は object である必要があります。");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String key, String where) throws ValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new ValidationException(where + "." + key + " は array である必要があります。");
        }
        return value.getAsJsonArray();
    }

    private record Vec3i(int x, int y, int z) {}
    private record Placement(BlockPos pos, BlockState state) {}

    public record ApplyResult(int requestedPlacements, int changedBlocks, int actionCount) {}

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}
