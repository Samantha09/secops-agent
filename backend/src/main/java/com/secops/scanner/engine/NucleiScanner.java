package com.secops.scanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nuclei 漏洞扫描引擎适配器
 * 通过调用 nuclei 二进制文件执行漏洞检测
 */
@Component
public class NucleiScanner implements ScannerEngine {

    @Value("${scanner.nuclei.path:/usr/local/bin/nuclei}")
    private String binaryPath;

    @Override
    public String getName() {
        return "nuclei";
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
            Path jsonFile = null;

            try {
                tempFile = Files.createTempFile("nuclei-targets-", ".txt");
                Files.writeString(tempFile, target);

                jsonFile = Files.createTempFile("nuclei-output-", ".jsonl");

                ProcessBuilder pb = new ProcessBuilder(
                        binaryPath,
                        "-list", tempFile.toString(),
                        "-jsonl", "-o", jsonFile.toString(),
                        "-rl", "150",
                        "-timeout", String.valueOf(scanOptions.getTimeout())
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // 消费 stdout（错误流已重定向）防止缓冲区满
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // 忽略实时输出，从文件读取结果
                    }
                }

                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? "nuclei exited with code " + process.exitValue() : "nuclei timed out");
                }

                ObjectMapper mapper = new ObjectMapper();
                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                StringBuilder rawOutput = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(jsonFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        rawOutput.append(line).append("\n");
                        try {
                            JsonNode node = mapper.readTree(line);
                            ScanResult.Finding f = new ScanResult.Finding();
                            f.setId(node.has("template-id") ? node.get("template-id").asText() : UUID.randomUUID().toString());
                            f.setName(node.has("info") && node.get("info").has("name") ? node.get("info").get("name").asText() : "Unknown");
                            f.setSeverity(node.has("info") && node.get("info").has("severity") ? node.get("info").get("severity").asText() : "info");
                            f.setDescription(node.has("info") && node.get("info").has("description") ? node.get("info").get("description").asText() : "");
                            f.setMatched(node.has("matched-at") ? node.get("matched-at").asText() : "");
                            f.setMetadata(new HashMap<>());
                            f.getMetadata().put("template", node.has("template") ? node.get("template").asText() : "");
                            findings.add(f);
                        } catch (Exception e) {
                            // 跳过解析失败的行
                        }
                    }
                }

                result.setFindings(findings);
                result.setRawOutput(java.util.Map.of("jsonLines", rawOutput.toString()));
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
                if (jsonFile != null) {
                    try {
                        Files.deleteIfExists(jsonFile);
                    } catch (Exception ignored) {
                    }
                }
            }

            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
