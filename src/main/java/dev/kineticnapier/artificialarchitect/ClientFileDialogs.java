package dev.kineticnapier.artificialarchitect;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ClientFileDialogs {
    private ClientFileDialogs() {}

    public static void saveWorldJson(String json) {
        runDialogTask(() -> {
            try {
                Path selected = showWindowsSaveDialog(
                        "Save Artificial Architect world.json",
                        "world.json",
                        "JSON files (*.json)|*.json|All files (*.*)|*.*"
                );
                if (selected == null) {
                    clientMessage("Artificial Architect: world.json の保存をキャンセルしました。");
                    return;
                }

                Files.writeString(selected, json, StandardCharsets.UTF_8);
                clientMessage("Artificial Architect: world.json を保存しました: " + selected);
            } catch (Exception e) {
                clientMessage("Artificial Architect: world.json の保存に失敗しました: " + describe(e));
                e.printStackTrace();
            }
        });
    }

    public static void openActionsJson() {
        runDialogTask(() -> {
            try {
                Path selected = showWindowsOpenDialog(
                        "Open Artificial Architect actions.json",
                        "JSON files (*.json)|*.json|All files (*.*)|*.*"
                );
                if (selected == null) {
                    clientMessage("Artificial Architect: actions.json の読み込みをキャンセルしました。");
                    return;
                }

                long size = Files.size(selected);
                if (size > ArtificialArchitectNetwork.MAX_JSON_CHARS * 4L) {
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
            } catch (Exception e) {
                clientMessage("Artificial Architect: actions.json の読み込みに失敗しました: " + describe(e));
                e.printStackTrace();
            }
        });
    }

    private static void runDialogTask(Runnable task) {
        Thread thread = new Thread(task, "ArtificialArchitect-FileDialog");
        thread.setDaemon(true);
        thread.start();
    }

    private static Path showWindowsSaveDialog(String title, String fileName, String filter)
            throws IOException, InterruptedException {
        ensureWindows();
        String script = "Add-Type -AssemblyName System.Windows.Forms; "
                + "$d=New-Object System.Windows.Forms.SaveFileDialog; "
                + "$d.Title='" + psQuote(title) + "'; "
                + "$d.FileName='" + psQuote(fileName) + "'; "
                + "$d.Filter='" + psQuote(filter) + "'; "
                + "$d.InitialDirectory='" + psQuote(defaultDirectory().toString()) + "'; "
                + "$d.OverwritePrompt=$true; "
                + "if($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){"
                + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                + "[Console]::Write($d.FileName)}";
        return runPowerShellDialog(script);
    }

    private static Path showWindowsOpenDialog(String title, String filter)
            throws IOException, InterruptedException {
        ensureWindows();
        String script = "Add-Type -AssemblyName System.Windows.Forms; "
                + "$d=New-Object System.Windows.Forms.OpenFileDialog; "
                + "$d.Title='" + psQuote(title) + "'; "
                + "$d.Filter='" + psQuote(filter) + "'; "
                + "$d.InitialDirectory='" + psQuote(defaultDirectory().toString()) + "'; "
                + "$d.CheckFileExists=$true; $d.Multiselect=$false; "
                + "if($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){"
                + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                + "[Console]::Write($d.FileName)}";
        return runPowerShellDialog(script);
    }

    private static Path runPowerShellDialog(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                powershellExecutable(),
                "-NoProfile",
                "-NonInteractive",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
        ).redirectErrorStream(false).start();

        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();

        String error = new String(stderr, StandardCharsets.UTF_8).trim();
        if (exitCode != 0) {
            throw new IOException(
                    "PowerShell file dialog failed (exit=" + exitCode + ")"
                            + (error.isEmpty() ? "" : ": " + error)
            );
        }

        String selected = new String(stdout, StandardCharsets.UTF_8).trim();
        if (selected.startsWith("\uFEFF")) {
            selected = selected.substring(1);
        }
        if (selected.isEmpty()) {
            return null;
        }
        return Path.of(selected);
    }

    private static String powershellExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path full = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(full)) {
                return full.toString();
            }
        }
        return "powershell.exe";
    }

    private static void ensureWindows() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new IOException("このファイルダイアログ実装は現在 Windows のみ対応です。");
        }
    }

    private static String psQuote(String value) {
        return value.replace("'", "''");
    }

    private static Path defaultDirectory() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Path.of(home, "Downloads");
        if (Files.isDirectory(downloads)) {
            return downloads;
        }
        return Path.of(home);
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static void clientMessage(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), false);
            }
        });
    }
}
