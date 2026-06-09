package com.secops.scanner.engine;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Subfinder 子域名扫描引擎适配器
 * 通过 docker exec 调用 secops-subfinder 容器内的 subfinder 命令
 */
@Component
public class SubfinderScanner implements ScannerEngine {

    private static final String CONTAINER_NAME = "secops-subfinder";
    private static final String BINARY = "subfinder";

    @Override
    public String getName() {
        return "subfinder";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER_NAME, BINARY, "-version");
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

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", CONTAINER_NAME,
                        BINARY, "-d", target, "-all"
                );
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
                            f.setName("Subdomain");
                            f.setMatched(line);
                            f.setSeverity("info");
                            findings.add(f);
                        }
                    }
                }
                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? BINARY + " exited with code " + process.exitValue() : BINARY + " timed out");
                }
                result.setFindings(findings);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            }
            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
