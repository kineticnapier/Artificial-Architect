package dev.kineticnapier.artificialarchitect;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ArtificialArchitectPaths {
    private ArtificialArchitectPaths() {}

    public static Path root() throws IOException {
        Path root = FMLPaths.GAMEDIR.get().resolve("artificialarchitect");
        Files.createDirectories(root);
        return root;
    }

    public static Path worldJson() throws IOException {
        return root().resolve("world.json");
    }

    public static Path actionsJson() throws IOException {
        return root().resolve("actions.json");
    }
}
