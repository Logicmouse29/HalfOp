package me.egerke001.halfOp;

import me.egerke001.halfOp.command.HalfOpUpdateCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.logging.Level;

/**
 * HalfOp plugin that automatically checks GitHub for updates and
 * downloads the latest release jar into the server's update folder.
 * <p>
 * Repo: https://github.com/Logicmouse29/HalfOp
 */
public final class HalfOp extends JavaPlugin {

    // GitHub API endpoint for the latest release of Logicmouse29/HalfOp
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Logicmouse29/HalfOp/releases/latest";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        boolean autoUpdateEnabled = getConfig().getBoolean("auto-update.enabled", true);

        if (autoUpdateEnabled) {
            getLogger().info("HalfOp starting up (auto-update on startup is enabled)...");
            // Run the update check asynchronously so we don't block the main thread
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> checkForUpdates());
        } else {
            getLogger().info("HalfOp starting up (auto-update on startup is DISABLED in config.yml)...");
        }

        registerCommands();
    }

    @Override
    public void onDisable() {
        getLogger().info("HalfOp shutting down.");
    }

    private void registerCommands() {
        PluginCommand cmd = getCommand("halfopupdate");
        if (cmd == null) {
            getLogger().warning("Command 'halfopupdate' is not defined in plugin.yml");
            return;
        }
        cmd.setExecutor(new HalfOpUpdateCommand(this));
    }

    public void runUpdateCheck(CommandSender initiator) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> checkForUpdates(initiator));
    }

    private void checkForUpdates() {
        checkForUpdates(null);
    }

    private void checkForUpdates(CommandSender initiator) {
        try {
            String currentVersion = getDescription().getVersion();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LATEST_RELEASE_API))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "HalfOp-Updater")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Failed to check for updates from GitHub. HTTP status: " + response.statusCode());
                return;
            }

            String body = response.body();

            String latestVersion = JsonUtils.extractString(body, "tag_name");
            if (latestVersion == null || latestVersion.isBlank()) {
                latestVersion = JsonUtils.extractString(body, "name");
            }

            if (latestVersion == null || latestVersion.isBlank()) {
                getLogger().warning("Could not determine latest version from GitHub response.");
                return;
            }

            if (latestVersion.equalsIgnoreCase(currentVersion)) {
                getLogger().info("HalfOp is up to date (" + currentVersion + ").");
                if (initiator != null) {
                    initiator.sendMessage("HalfOp is up to date (" + currentVersion + ").");
                }
                return;
            }

            String downloadUrl = JsonUtils.extractFirstJarAssetDownloadUrl(body);
            if (downloadUrl == null) {
                getLogger().warning("No .jar asset found in latest GitHub release.");
                return;
            }

            String message = "New HalfOp version available: " + latestVersion + " (current: " + currentVersion + ").";
            getLogger().info(message);
            if (initiator != null) {
                initiator.sendMessage(message);
                initiator.sendMessage("Downloading HalfOp update from GitHub...");
            } else {
                getLogger().info("Downloading update from GitHub...");
            }

            downloadUpdate(downloadUrl);

            String doneMessage = "Update downloaded. It will be applied on the next server restart or reload.";
            getLogger().info(doneMessage);
            if (initiator != null) {
                initiator.sendMessage(doneMessage);
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Error while checking for HalfOp updates", ex);
            if (initiator != null) {
                initiator.sendMessage("An error occurred while checking for HalfOp updates. Check console for details.");
            }
        }
    }

    private void downloadUpdate(String downloadUrl) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofMinutes(1))
                .header("User-Agent", "HalfOp-Updater")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            getLogger().warning("Failed to download HalfOp update. HTTP status: " + response.statusCode());
            return;
        }

        File updateFolder = getServer().getUpdateFolderFile();
        if (!updateFolder.exists() && !updateFolder.mkdirs()) {
            getLogger().warning("Could not create update folder: " + updateFolder.getAbsolutePath());
            return;
        }

        // Put the new jar into the update folder with the same name as this plugin's jar
        File currentJar = getFile();
        File target = new File(updateFolder, currentJar.getName());

        try (InputStream in = response.body();
             FileOutputStream out = new FileOutputStream(target)) {
            in.transferTo(out);
        }

        // Optionally ensure permissions etc.
        try {
            Files.setPosixFilePermissions(target.toPath(), Files.getPosixFilePermissions(currentJar.toPath()));
        } catch (UnsupportedOperationException ignored) {
            // Not on a POSIX file system; ignore.
        }
    }

    /**
     * Very small JSON helper to avoid pulling in external libraries. It is NOT a full JSON parser,
     * just enough for GitHub's latest-release structure.
     */
    static final class JsonUtils {

        static String extractString(String json, String key) {
            String pattern = "\"" + key + "\"" + ":";
            int idx = json.indexOf(pattern);
            if (idx == -1) return null;
            idx = json.indexOf('"', idx + pattern.length());
            if (idx == -1) return null;
            int end = json.indexOf('"', idx + 1);
            if (end == -1) return null;
            return json.substring(idx + 1, end);
        }

        static String extractFirstJarAssetDownloadUrl(String json) {
            String assetsKey = "\"browser_download_url\"";
            int from = 0;
            while (true) {
                int idx = json.indexOf(assetsKey, from);
                if (idx == -1) break;
                int startQuote = json.indexOf('"', idx + assetsKey.length());
                if (startQuote == -1) break;
                int endQuote = json.indexOf('"', startQuote + 1);
                if (endQuote == -1) break;
                String url = json.substring(startQuote + 1, endQuote);
                if (url.endsWith(".jar")) {
                    return url;
                }
                from = endQuote + 1;
            }
            return null;
        }
    }
}
