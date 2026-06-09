package com.secops.service;

import com.secops.controller.ScanProgressWebSocketHandler;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.TargetType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 扫描引擎编排服务
 * 负责执行扫描流水线，支持 FULL/SUBDOMAIN/PORT/VULN 四种类型
 * 当工具不可用时自动降级为 Java 原生探测
 */
@Service
public class ScannerEngineService {

    private final SubfinderScanner subfinderScanner;
    private final NaabuScanner naabuScanner;
    private final HttpxScanner httpxScanner;
    private final NucleiScanner nucleiScanner;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final ScanProgressWebSocketHandler webSocketHandler;

    // 全局并发控制：最多同时执行 3 个扫描任务
    private final Semaphore scanSemaphore = new Semaphore(3);

    public ScannerEngineService(SubfinderScanner subfinderScanner, NaabuScanner naabuScanner,
                                HttpxScanner httpxScanner, NucleiScanner nucleiScanner,
                                ScanTaskRepository scanTaskRepository,
                                VulnerabilityRepository vulnerabilityRepository,
                                ScanProgressWebSocketHandler webSocketHandler) {
        this.subfinderScanner = subfinderScanner;
        this.naabuScanner = naabuScanner;
        this.httpxScanner = httpxScanner;
        this.nucleiScanner = nucleiScanner;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.webSocketHandler = webSocketHandler;
    }

    // ========== 四种扫描入口 ==========

    @Async
    public void runFullScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            boolean isIp = task.getTarget().getType() == TargetType.IP;
            StringBuilder rawOutput = new StringBuilder();

            String subdomains = domain;

            // Step 1: Subfinder
            if (!isIp) {
                subdomains = runSubfinderStage(task, rawOutput);
            } else {
                pushProgress(task, "SUBDOMAIN_SCAN", "IP 目标跳过子域名发现", 30);
                rawOutput.append("Subfinder: skipped for IP target\n");
                saveTask(task, rawOutput.toString());
            }

            // Step 2: Naabu
            runNaabuStage(task, subdomains, rawOutput);

            // Step 3: Httpx
            String aliveUrls = runHttpxStage(task, subdomains, rawOutput);

            // Step 4: Nuclei
            runNucleiStage(task, aliveUrls, rawOutput);

            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runSubdomainScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            StringBuilder rawOutput = new StringBuilder();
            runSubfinderStage(task, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runPortScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            StringBuilder rawOutput = new StringBuilder();
            runNaabuStage(task, domain, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runVulnScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            StringBuilder rawOutput = new StringBuilder();
            runNucleiStage(task, domain, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    // ========== 阶段执行方法 ==========

    private String runSubfinderStage(ScanTask task, StringBuilder rawOutput) {
        String domain = task.getTarget().getDomain();
        String subdomains = domain;

        if (subfinderScanner.isAvailable()) {
            try {
                ScanResult subResult = subfinderScanner.scan(domain, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder: " + subResult.getFindings().size() + " subdomains", 30);
                rawOutput.append("Subfinder: ").append(subResult.getFindings().size()).append(" subdomains\n");
                saveTask(task, rawOutput.toString());

                subdomains = subResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (subdomains.isEmpty()) subdomains = domain;
            } catch (Exception e) {
                pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder 执行失败: " + e.getMessage(), 30);
                rawOutput.append("Subfinder: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder 不可用，使用 DNS 降级探测", 30);
            rawOutput.append("Subfinder: 工具不可用，使用 DNS 降级探测\n");
            List<String> found = probeSubdomains(domain);
            rawOutput.append("DNS probe: ").append(found.size()).append(" subdomains\n");
            saveTask(task, rawOutput.toString());
            subdomains = found.isEmpty() ? domain : String.join("\n", found);
        }
        return subdomains;
    }

    private void runNaabuStage(ScanTask task, String targets, StringBuilder rawOutput) {
        if (naabuScanner.isAvailable()) {
            try {
                ScanResult portResult = naabuScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "PORT_SCAN", "Naabu: " + portResult.getFindings().size() + " ports", 50);
                rawOutput.append("Naabu: ").append(portResult.getFindings().size()).append(" ports\n");
                saveTask(task, rawOutput.toString());
            } catch (Exception e) {
                pushProgress(task, "PORT_SCAN", "Naabu 执行失败: " + e.getMessage(), 50);
                rawOutput.append("Naabu: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "PORT_SCAN", "Naabu 不可用，使用 Socket 降级探测", 50);
            rawOutput.append("Naabu: 工具不可用，使用 Socket 降级探测\n");
            List<String> found = probePorts(targets);
            rawOutput.append("Socket probe: ").append(found.size()).append(" open ports\n");
            saveTask(task, rawOutput.toString());
        }
    }

    private String runHttpxStage(ScanTask task, String targets, StringBuilder rawOutput) {
        String domain = task.getTarget().getDomain();
        String aliveUrls = domain;

        if (httpxScanner.isAvailable()) {
            try {
                ScanResult aliveResult = httpxScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "HTTP_PROBE", "Httpx: " + aliveResult.getFindings().size() + " alive hosts", 70);
                rawOutput.append("Httpx: ").append(aliveResult.getFindings().size()).append(" alive hosts\n");
                saveTask(task, rawOutput.toString());

                aliveUrls = aliveResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (aliveUrls.isEmpty()) aliveUrls = domain;
            } catch (Exception e) {
                pushProgress(task, "HTTP_PROBE", "Httpx 执行失败: " + e.getMessage(), 70);
                rawOutput.append("Httpx: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "HTTP_PROBE", "Httpx 不可用，使用 HTTP 降级探测", 70);
            rawOutput.append("Httpx: 工具不可用，使用 HTTP 降级探测\n");
            List<String> found = probeHttpAlive(targets);
            rawOutput.append("HTTP probe: ").append(found.size()).append(" alive hosts\n");
            saveTask(task, rawOutput.toString());
            aliveUrls = found.isEmpty() ? domain : String.join("\n", found);
        }
        return aliveUrls;
    }

    private void runNucleiStage(ScanTask task, String targets, StringBuilder rawOutput) {
        if (nucleiScanner.isAvailable()) {
            try {
                ScanResult vulnResult = nucleiScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "VULN_SCAN", "Nuclei: " + vulnResult.getFindings().size() + " findings", 90);
                rawOutput.append("Nuclei: ").append(vulnResult.getFindings().size()).append(" findings\n");
                saveTask(task, rawOutput.toString());

                for (ScanResult.Finding finding : vulnResult.getFindings()) {
                    Vulnerability v = new Vulnerability();
                    v.setName(finding.getName());
                    v.setSeverity(parseSeverity(finding.getSeverity()));
                    v.setDescription(finding.getDescription());
                    v.setMatched(finding.getMatched());
                    v.setTarget(task.getTarget().getDomain());
                    v.setScanner("nuclei");
                    v.setScanTask(task);
                    vulnerabilityRepository.save(v);
                }
            } catch (Exception e) {
                pushProgress(task, "VULN_SCAN", "Nuclei 执行失败: " + e.getMessage(), 90);
                rawOutput.append("Nuclei: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "VULN_SCAN", "Nuclei 不可用，使用 HTTP 降级漏洞探测", 90);
            rawOutput.append("Nuclei: 工具不可用，使用 HTTP 降级漏洞探测\n");
            List<ScanResult.Finding> findings = probeVulnerabilities(targets);
            rawOutput.append("HTTP vuln probe: ").append(findings.size()).append(" findings\n");
            saveTask(task, rawOutput.toString());

            for (ScanResult.Finding finding : findings) {
                Vulnerability v = new Vulnerability();
                v.setName(finding.getName());
                v.setSeverity(parseSeverity(finding.getSeverity()));
                v.setDescription(finding.getDescription());
                v.setMatched(finding.getMatched());
                v.setTarget(task.getTarget().getDomain());
                v.setScanner("http-probe");
                v.setScanTask(task);
                vulnerabilityRepository.save(v);
            }
        }
    }

    // ========== 任务生命周期 ==========

    private void executeWithSemaphore(ScanTask task, Runnable scanLogic) {
        boolean acquired = false;
        try {
            if (!scanSemaphore.tryAcquire()) {
                task.setStatus(ScanStatus.QUEUED);
                scanTaskRepository.save(task);
                scanSemaphore.acquire();
            }
            acquired = true;

            task.setStatus(ScanStatus.RUNNING);
            task.setStartTime(LocalDateTime.now());
            task.setProgress(10);
            scanTaskRepository.save(task);

            scanLogic.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTask(task, "扫描被中断");
        } catch (Exception e) {
            failTask(task, e.getMessage());
        } finally {
            if (acquired) {
                scanSemaphore.release();
            }
        }
    }

    private void completeTask(ScanTask task, String rawOutput) {
        task.setStatus(ScanStatus.COMPLETED);
        task.setProgress(100);
        task.setEndTime(LocalDateTime.now());
        task.setRawOutput(rawOutput);
        scanTaskRepository.save(task);
        pushProgress(task, "COMPLETED", "扫描完成", 100);
    }

    private void failTask(ScanTask task, String error) {
        task.setStatus(ScanStatus.FAILED);
        task.setErrorMessage(error);
        task.setEndTime(LocalDateTime.now());
        scanTaskRepository.save(task);
        pushProgress(task, "FAILED", "扫描失败: " + error, task.getProgress());
    }

    private void saveTask(ScanTask task, String rawOutput) {
        task.setRawOutput(rawOutput);
        scanTaskRepository.save(task);
    }

    private void pushProgress(ScanTask task, String stage, String message, int progress) {
        task.setProgress(progress);
        webSocketHandler.broadcastProgress(
                task.getTaskId(),
                task.getStatus().name(),
                progress,
                stage,
                message
        );
    }

    // ========== 降级探测方法（保持不变）==========

    private List<String> probeSubdomains(String domain) {
        String[] common = {"www", "api", "mail", "blog", "dev", "test", "staging", "admin"};
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String prefix : common) {
            String sub = prefix + "." + domain;
            try {
                InetAddress.getByName(sub);
                found.add(sub);
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    private List<String> probePorts(String targets) {
        int[] commonPorts = {80, 443, 8080, 8443, 22, 3306, 5432, 6379};
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String host : targets.split("\n")) {
            host = host.trim();
            if (host.isEmpty()) continue;
            for (int port : commonPorts) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    found.add(host + ":" + port);
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private List<String> probeHttpAlive(String targets) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String host : targets.split("\n")) {
            host = host.trim();
            if (host.isEmpty()) continue;
            for (String scheme : new String[]{"http", "https"}) {
                try {
                    URL url = new URL(scheme + "://" + host);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("HEAD");
                    int code = conn.getResponseCode();
                    if (code > 0) {
                        found.add(url.toString());
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private List<ScanResult.Finding> probeVulnerabilities(String urls) {
        java.util.List<ScanResult.Finding> findings = new java.util.ArrayList<>();
        String[] probes = {
            "/.git/config|Git Configuration Exposure|HIGH|Git configuration file was exposed, potentially leaking repository metadata and credentials.",
            "/.env|Environment Variable File Exposure|CRITICAL|Environment file (.env) was exposed, containing sensitive credentials and configuration.",
            "/admin|Unauthenticated Admin Panel|HIGH|Admin panel is accessible without authentication.",
            "/actuator/env|Spring Boot Actuator Exposed|HIGH|Spring Boot Actuator endpoint exposed, potentially leaking environment variables.",
            "/.htaccess|Apache .htaccess Exposure|MEDIUM|Apache configuration file exposed.",
            "/robots.txt|Robots.txt Exposure|INFO|robots.txt may reveal hidden paths.",
        };

        for (String baseUrl : urls.split("\n")) {
            baseUrl = baseUrl.trim();
            if (baseUrl.isEmpty()) continue;
            for (String probe : probes) {
                String[] parts = probe.split("\\|", 4);
                String path = parts[0];
                String name = parts[1];
                String severity = parts[2];
                String description = parts[3];
                try {
                    URL url = new URL(baseUrl + path);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        ScanResult.Finding f = new ScanResult.Finding();
                        f.setId(java.util.UUID.randomUUID().toString());
                        f.setName(name);
                        f.setSeverity(severity);
                        f.setDescription(description);
                        f.setMatched(url.toString());
                        findings.add(f);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return findings;
    }

    private Severity parseSeverity(String severity) {
        return switch (severity != null ? severity.toLowerCase() : "info") {
            case "critical" -> Severity.CRITICAL;
            case "high" -> Severity.HIGH;
            case "medium" -> Severity.MEDIUM;
            case "low" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }
}
