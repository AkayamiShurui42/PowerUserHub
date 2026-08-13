package com.poweruserhub.app.service;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside Shizuku's UserService process, so commands inherit the actual Shizuku
 * server identity (shell, root, or the identity provided by a compatible Shizuku+ backend).
 */
public class PrivilegedUserService extends IPrivilegedUserService.Stub {

    public PrivilegedUserService() {
    }

    @Keep
    public PrivilegedUserService(Context context) {
    }

    @Override
    public void destroy() {
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
}
