package com.secops.scanner.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Httpx 存活探测引擎适配器
 * 通过调用 httpx 二进制文件探测主机存活状态
 */
@Component
public class HttpxScanner implements ScannerEngine {

    @Value("${scanner.httpx.path:/usr/local/bin/httpx}")
    private String binaryPath;

    @Override
    public String getName() {
        return "httpx";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<ScanResult> scan(String target, ScanOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult();
            result.setScanner(getName());
            result.setTarget(target);
            result.setStartTime(LocalDateTime.now());
            result.setSuccess(true);

            ScanOptions scanOptions = options != null ? options : new ScanOptions();
            Path tempFile = null;

            try {
                tempFile = Files.createTempFile("httpx-targets-", ".txt");
                Files.writeString(tempFile, target);

                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-list", tempFile.toString(), "-silent");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            ScanResult.Finding f = new ScanResult.Finding();
                            f.setId(UUID.randomUUID().toString());
                            f.setName("Alive Host");
                            f.setMatched(line);
                            f.setSeverity("info");
                            findings.add(f);
                        }
                    }
                }

                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? "httpx exited with code " + process.exitValue() : "httpx timed out");
                }
                result.setFindings(findings);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (Exception ignored) {
                    }
                }
            }

            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
