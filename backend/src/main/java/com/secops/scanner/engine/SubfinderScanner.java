package com.secops.scanner.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Subfinder 子域名扫描引擎适配器
 * 通过调用 subfinder 二进制文件进行子域名发现
 */
@Component
public class SubfinderScanner implements ScannerEngine {

    @Value("${scanner.subfinder.path:/usr/local/bin/subfinder}")
    private String binaryPath;

    @Override
    public String getName() {
        return "subfinder";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            return p.waitFor() == 0;
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

            try {
                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-d", target, "-all");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
                process.waitFor();
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
