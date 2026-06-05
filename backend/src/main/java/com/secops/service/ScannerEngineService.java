package com.secops.service;

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
import java.util.stream.Collectors;

/**
 * 扫描引擎编排服务
 * 负责执行完整的扫描流水线：子域名发现 → 端口扫描 → 存活探测 → 漏洞扫描
 * 当工具不可用时，自动降级为基于 Java 原生能力的简化探测
 */
@Service
public class ScannerEngineService {

    private final SubfinderScanner subfinderScanner;
    private final NaabuScanner naabuScanner;
    private final HttpxScanner httpxScanner;
    private final NucleiScanner nucleiScanner;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;

    public ScannerEngineService(SubfinderScanner subfinderScanner, NaabuScanner naabuScanner,
                                HttpxScanner httpxScanner, NucleiScanner nucleiScanner,
                                ScanTaskRepository scanTaskRepository, VulnerabilityRepository vulnerabilityRepository) {
        this.subfinderScanner = subfinderScanner;
        this.naabuScanner = naabuScanner;
        this.httpxScanner = httpxScanner;
        this.nucleiScanner = nucleiScanner;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @Async
    public void runFullScan(ScanTask task) {
        try {
            task.setStatus(ScanStatus.RUNNING);
            task.setStartTime(LocalDateTime.now());
            task.setProgress(10);
            scanTaskRepository.save(task);

            String domain = task.getTarget().getDomain();
            boolean isIp = task.getTarget().getType() == TargetType.IP;
            StringBuilder rawOutput = new StringBuilder();

            String subdomains = domain;

            // Step 1: Subfinder (跳过 IP 目标)
            if (!isIp) {
                if (subfinderScanner.isAvailable()) {
                    ScanResult subResult = subfinderScanner.scan(domain, new ScannerEngine.ScanOptions()).get();
                    task.setProgress(30);
                    rawOutput.append("Subfinder: ").append(subResult.getFindings().size()).append(" subdomains\n");
                    task.setRawOutput(rawOutput.toString());
                    scanTaskRepository.save(task);

                    subdomains = subResult.getFindings().stream()
                            .map(ScanResult.Finding::getMatched)
                            .collect(Collectors.joining("\n"));
                    if (subdomains.isEmpty()) subdomains = domain;
                } else {
                    // 降级：DNS 探测常见子域名
                    rawOutput.append("Subfinder: 工具不可用，使用 DNS 降级探测\n");
                    List<String> found = probeSubdomains(domain);
                    rawOutput.append("DNS probe: ").append(found.size()).append(" subdomains\n");
                    task.setProgress(30);
                    task.setRawOutput(rawOutput.toString());
                    scanTaskRepository.save(task);
                    subdomains = found.isEmpty() ? domain : String.join("\n", found);
                }
            } else {
                task.setProgress(30);
                rawOutput.append("Subfinder: skipped for IP target\n");
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);
            }

            // Step 2: Naabu
            if (naabuScanner.isAvailable()) {
                ScanResult portResult = naabuScanner.scan(subdomains, new ScannerEngine.ScanOptions()).get();
                task.setProgress(50);
                rawOutput.append("Naabu: ").append(portResult.getFindings().size()).append(" ports\n");
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);

                String hostPorts = portResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (hostPorts.isEmpty()) hostPorts = domain;
            } else {
                // 降级：Socket 探测常见端口
                rawOutput.append("Naabu: 工具不可用，使用 Socket 降级探测\n");
                List<String> found = probePorts(subdomains);
                rawOutput.append("Socket probe: ").append(found.size()).append(" open ports\n");
                task.setProgress(50);
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);
            }

            // Step 3: Httpx
            String aliveUrls = domain;
            if (httpxScanner.isAvailable()) {
                ScanResult aliveResult = httpxScanner.scan(subdomains, new ScannerEngine.ScanOptions()).get();
                task.setProgress(70);
                rawOutput.append("Httpx: ").append(aliveResult.getFindings().size()).append(" alive hosts\n");
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);

                aliveUrls = aliveResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (aliveUrls.isEmpty()) aliveUrls = domain;
            } else {
                // 降级：HTTP 存活探测
                rawOutput.append("Httpx: 工具不可用，使用 HTTP 降级探测\n");
                List<String> found = probeHttpAlive(subdomains);
                rawOutput.append("HTTP probe: ").append(found.size()).append(" alive hosts\n");
                task.setProgress(70);
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);
                aliveUrls = found.isEmpty() ? domain : String.join("\n", found);
            }

            // Step 4: Nuclei
            if (nucleiScanner.isAvailable()) {
                ScanResult vulnResult = nucleiScanner.scan(aliveUrls, new ScannerEngine.ScanOptions()).get();
                task.setProgress(90);
                rawOutput.append("Nuclei: ").append(vulnResult.getFindings().size()).append(" findings\n");
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);

                // Save vulnerabilities
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
            } else {
                // 降级：HTTP 漏洞探测
                rawOutput.append("Nuclei: 工具不可用，使用 HTTP 降级漏洞探测\n");
                List<ScanResult.Finding> findings = probeVulnerabilities(aliveUrls);
                rawOutput.append("HTTP vuln probe: ").append(findings.size()).append(" findings\n");
                task.setProgress(90);
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);

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

            task.setStatus(ScanStatus.COMPLETED);
            task.setProgress(100);
            task.setEndTime(LocalDateTime.now());
            scanTaskRepository.save(task);

        } catch (Exception e) {
            task.setStatus(ScanStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setEndTime(LocalDateTime.now());
            scanTaskRepository.save(task);
        }
    }

    // ========== 降级探测方法 ==========

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
