package com.poweruserhub.app.service;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside Shizuku's UserService process, so commands inherit the actual Shizuku
 * server identity (shell, root, or the identity provided by a compatible Shizuku+ backend).
 *
 * The service also hosts the keep-alive watchdog. Keeping the watchdog in the privileged
 * UserService means it can continue reconciling protected services if the Power User Hub UI
 * process is reclaimed. It still depends on the Shizuku/Shizuku+ server itself remaining up.
 */
public class PrivilegedUserService extends IPrivilegedUserService.Stub {

    private static final long WATCHDOG_PERIOD_SECONDS = 2L;
    private static final long MAX_RETRY_DELAY_MS = 60_000L;
    private static final int MAX_EVENTS = 200;

    private final Map<String, ProtectedTarget> protectedTargets = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> protectionEvents = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PowerHub-ServiceWatchdog");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean destroyed = false;

    public PrivilegedUserService() {
        startWatchdog();
    }

    @Keep
    public PrivilegedUserService(Context context) {
        startWatchdog();
    }

    private void startWatchdog() {
        watchdog.scheduleWithFixedDelay(
                this::watchdogPassSafely,
                WATCHDOG_PERIOD_SECONDS,
                WATCHDOG_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    public void destroy() {
        destroyed = true;
        protectedTargets.clear();
        watchdog.shutdownNow();
        System.exit(0);
    }

    @Override
    public int getUid() {
        return Os.getuid();
    }

    @Override
    public String[] execute(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new String[]{"0", "", ""};
        }

        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            final Process runningProcess = process;

            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> readStream(runningProcess.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> readStream(runningProcess.getErrorStream()));

            boolean exited = process.waitFor(15, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new String[]{
                        "-3",
                        safeFuture(stdout),
                        "Execution timed out after 15 seconds" + appendOutput(safeFuture(stderr))
                };
            }

            return new String[]{
                    Integer.toString(process.exitValue()),
                    safeFuture(stdout).trim(),
                    safeFuture(stderr).trim()
            };
        } catch (Throwable t) {
            return new String[]{
                    "-2",
                    "",
                    t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "unknown error" : t.getMessage())
            };
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (Throwable ignored) {}
                try { process.getOutputStream().close(); } catch (Throwable ignored) {}
                try { process.getErrorStream().close(); } catch (Throwable ignored) {}
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public String[] setProtectedService(String packageName, String componentName, boolean enabled) {
        if (packageName == null || packageName.trim().isEmpty() ||
                componentName == null || componentName.trim().isEmpty()) {
            return new String[]{"-1", "", "Package and component are required."};
        }

        String pkg = packageName.trim();
        String component = componentName.trim();
        String key = targetKey(pkg, component);

        if (!enabled) {
            ProtectedTarget removed = protectedTargets.remove(key);
            if (removed != null) {
                addEvent("UNPROTECTED", removed, "Keep-alive disabled by user");
            }
            return new String[]{"0", "Protection disabled", ""};
        }

        ProtectedTarget target = protectedTargets.computeIfAbsent(
                key,
                ignored -> new ProtectedTarget(pkg, component)
        );
        target.nextAttemptAt = 0L;
        applyPackageProtection(target.packageName);

        boolean alreadyRunning = isServiceRunning(target);
        target.lastSeenRunning = alreadyRunning;
        if (alreadyRunning) {
            target.failureCount = 0;
            addEvent("PROTECTED", target, "Service already running");
            return new String[]{"0", "Protected and running", ""};
        }

        RestartResult restart = restartTarget(target, "Protection enabled");
        return restart.success
                ? new String[]{"0", "Protected and started", ""}
                : new String[]{"-2", restart.stdout, restart.stderr};
    }

    @Override
    public boolean isProtectedService(String packageName, String componentName) {
        if (packageName == null || componentName == null) return false;
        return protectedTargets.containsKey(targetKey(packageName.trim(), componentName.trim()));
    }

    @Override
    public String[] getProtectedServices() {
        List<String> rows = new ArrayList<>();
        for (ProtectedTarget target : protectedTargets.values()) {
            rows.add(target.packageName + "/" + target.componentName);
        }
        return rows.toArray(new String[0]);
    }

    @Override
    public String[] drainProtectionEvents() {
        List<String> rows = new ArrayList<>();
        String row;
        while ((row = protectionEvents.poll()) != null) {
            rows.add(row);
        }
        return rows.toArray(new String[0]);
    }

    private void watchdogPassSafely() {
        if (destroyed) return;
        try {
            watchdogPass();
        } catch (Throwable t) {
            addRawEvent("WATCHDOG_ERROR", "", "", t.getClass().getSimpleName() + ": " + safeText(t.getMessage()));
        }
    }

    private void watchdogPass() {
        long now = System.currentTimeMillis();
        for (ProtectedTarget target : protectedTargets.values()) {
            if (now < target.nextAttemptAt) continue;

            boolean running = isServiceRunning(target);
            if (running) {
                target.lastSeenRunning = true;
                target.failureCount = 0;
                target.nextAttemptAt = now + WATCHDOG_PERIOD_SECONDS * 1000L;
                continue;
            }

            if (target.lastSeenRunning) {
                addEvent("DISAPPEARED", target, "Protected service is no longer present in ActivityManager");
            }
            target.lastSeenRunning = false;
            restartTarget(target, "Watchdog reconciliation");
        }
    }

    private RestartResult restartTarget(ProtectedTarget target, String reason) {
        applyPackageProtection(target.packageName);
        String spec = target.packageName + "/" + target.componentName;
        String quotedSpec = shellQuote(spec);

        String[] result = execute("am start-service --user current -n " + quotedSpec);
        if (!isSuccess(result)) {
            String[] foregroundResult = execute("am start-foreground-service --user current -n " + quotedSpec);
            if (isSuccess(foregroundResult)) {
                result = foregroundResult;
            }
        }

        try {
            Thread.sleep(350L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        boolean running = isServiceRunning(target);
        if (running) {
            target.lastSeenRunning = true;
            target.failureCount = 0;
            target.nextAttemptAt = System.currentTimeMillis() + WATCHDOG_PERIOD_SECONDS * 1000L;
            addEvent("AUTO_RESTARTED", target, reason);
            return new RestartResult(true, result.length > 1 ? result[1] : "", "");
        }

        target.failureCount = Math.min(target.failureCount + 1, 8);
        long delay = Math.min(MAX_RETRY_DELAY_MS, (1L << Math.min(target.failureCount, 5)) * 1000L);
        target.nextAttemptAt = System.currentTimeMillis() + delay;
        String stdout = result.length > 1 ? safeText(result[1]) : "";
        String stderr = result.length > 2 ? safeText(result[2]) : "Service did not become active";
        addEvent("RESTART_FAILED", target, "Retry in " + (delay / 1000L) + "s · " + stderr);
        return new RestartResult(false, stdout, stderr);
    }

    private boolean isServiceRunning(ProtectedTarget target) {
        String[] result = execute("dumpsys activity services " + shellQuote(target.packageName));
        if (!isSuccess(result)) return false;

        String output = result.length > 1 ? result[1] : "";
        if (output == null || output.isEmpty()) return false;

        String fullSpec = target.packageName + "/" + target.componentName;
        String shortSpec = null;
        String prefix = target.packageName + ".";
        if (target.componentName.startsWith(prefix)) {
            shortSpec = target.packageName + "/." + target.componentName.substring(prefix.length());
        }

        if (output.contains(fullSpec)) return true;
        if (shortSpec != null && output.contains(shortSpec)) return true;
        return output.contains(target.packageName) && output.contains(target.componentName);
    }

    private void applyPackageProtection(String packageName) {
        String quotedPackage = shellQuote(packageName);
        // Each command is intentionally independent. OEMs and privilege providers can deny one
        // control while allowing another; a denied advisory control must not disable the watchdog.
        execute("dumpsys deviceidle whitelist +" + quotedPackage);
        execute("am set-standby-bucket " + quotedPackage + " active");
        execute("cmd appops set " + quotedPackage + " RUN_IN_BACKGROUND allow");
        execute("cmd appops set " + quotedPackage + " RUN_ANY_IN_BACKGROUND allow");
    }

    private void addEvent(String type, ProtectedTarget target, String details) {
        addRawEvent(type, target.packageName, target.componentName, details);
    }

    private void addRawEvent(String type, String packageName, String componentName, String details) {
        protectionEvents.add(
                System.currentTimeMillis() + "\t" +
                        safeText(type) + "\t" +
                        safeText(packageName) + "\t" +
                        safeText(componentName) + "\t" +
                        safeText(details).replace('\t', ' ')
        );
        while (protectionEvents.size() > MAX_EVENTS) {
            protectionEvents.poll();
        }
    }

    private static boolean isSuccess(String[] result) {
        if (result == null || result.length == 0) return false;
        try {
            return Integer.parseInt(result[0]) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String targetKey(String packageName, String componentName) {
        return packageName + "/" + componentName;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    private static String readStream(java.io.InputStream stream) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static String safeFuture(CompletableFuture<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String appendOutput(String text) {
        return text == null || text.trim().isEmpty() ? "" : ": " + text.trim();
    }

    private static final class ProtectedTarget {
        final String packageName;
        final String componentName;
        volatile boolean lastSeenRunning;
        volatile int failureCount;
        volatile long nextAttemptAt;

        ProtectedTarget(String packageName, String componentName) {
            this.packageName = packageName;
            this.componentName = componentName;
        }
    }

    private static final class RestartResult {
        final boolean success;
        final String stdout;
        final String stderr;

        RestartResult(boolean success, String stdout, String stderr) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
