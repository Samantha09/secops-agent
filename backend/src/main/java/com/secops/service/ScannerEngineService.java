package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.TargetType;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 扫描引擎编排服务
 * 负责执行完整的扫描流水线：子域名发现 → 端口扫描 → 存活探测 → 漏洞扫描
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
                if (!subfinderScanner.isAvailable()) {
                    throw new IllegalStateException("subfinder 未安装或不可用");
                }
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
                task.setProgress(30);
                rawOutput.append("Subfinder: skipped for IP target\n");
                task.setRawOutput(rawOutput.toString());
                scanTaskRepository.save(task);
            }

            // Step 2: Naabu
            if (!naabuScanner.isAvailable()) {
                throw new IllegalStateException("naabu 未安装或不可用");
            }
            ScanResult portResult = naabuScanner.scan(subdomains, new ScannerEngine.ScanOptions()).get();
            task.setProgress(50);
            rawOutput.append("Naabu: ").append(portResult.getFindings().size()).append(" ports\n");
            task.setRawOutput(rawOutput.toString());
            scanTaskRepository.save(task);

            String hostPorts = portResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (hostPorts.isEmpty()) hostPorts = domain;

            // Step 3: Httpx
            if (!httpxScanner.isAvailable()) {
                throw new IllegalStateException("httpx 未安装或不可用");
            }
            ScanResult aliveResult = httpxScanner.scan(hostPorts, new ScannerEngine.ScanOptions()).get();
            task.setProgress(70);
            rawOutput.append("Httpx: ").append(aliveResult.getFindings().size()).append(" alive hosts\n");
            task.setRawOutput(rawOutput.toString());
            scanTaskRepository.save(task);

            String aliveUrls = aliveResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (aliveUrls.isEmpty()) aliveUrls = domain;

            // Step 4: Nuclei
            if (!nucleiScanner.isAvailable()) {
                throw new IllegalStateException("nuclei 未安装或不可用");
            }
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
