package dev.kineticnapier.artificialarchitect;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientFileDialogs {
    private ClientFileDialogs() {}

    public static void saveWorldJson(String json) {
        try {
            FileDialog dialog = new FileDialog((Frame) null, "Save Artificial Architect world.json", FileDialog.SAVE);
            dialog.setDirectory(defaultDirectory().toString());
            dialog.setFile("world.json");
            dialog.setVisible(true);

            Path selected = selectedPath(dialog);
            dialog.dispose();
            if (selected == null) {
                clientMessage("Artificial Architect: world.json の保存をキャンセルしました。");
                return;
            }

            Files.writeString(selected, json, StandardCharsets.UTF_8);
            clientMessage("Artificial Architect: world.json を保存しました: " + selected);
        } catch (Exception e) {
            clientMessage("Artificial Architect: world.json の保存に失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void openActionsJson() {
        try {
            FileDialog dialog = new FileDialog((Frame) null, "Open Artificial Architect actions.json", FileDialog.LOAD);
            dialog.setDirectory(defaultDirectory().toString());
            dialog.setFile("*.json");
            dialog.setVisible(true);

            Path selected = selectedPath(dialog);
            dialog.dispose();
            if (selected == null) {
                clientMessage("Artificial Architect: actions.json の読み込みをキャンセルしました。");
                return;
            }

            long size = Files.size(selected);
            if (size > ArtificialArchitectNetwork.MAX_JSON_CHARS) {
                clientMessage(
                        "Artificial Architect: actions.json が転送上限を超えています: "
                                + size + " bytes"
                );
                return;
            }

            String json = Files.readString(selected, StandardCharsets.UTF_8);
            if (!ArtificialArchitectNetwork.canTransfer(json)) {
                clientMessage("Artificial Architect: actions.json が転送上限を超えています。");
                return;
            }

            ArtificialArchitectNetwork.submitActionsToServer(json);
            clientMessage("Artificial Architect: actions.json を読み込みました: " + selected.getFileName());
        } catch (IOException | RuntimeException e) {
            clientMessage("Artificial Architect: actions.json の読み込みに失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Path defaultDirectory() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Path.of(home, "Downloads");
        if (Files.isDirectory(downloads)) {
            return downloads;
        }
        return Path.of(home);
    }

    private static Path selectedPath(FileDialog dialog) {
        String directory = dialog.getDirectory();
        String file = dialog.getFile();
        if (directory == null || file == null) {
            return null;
        }
        return Path.of(directory, file);
    }

    private static void clientMessage(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
