package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.repository.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TargetRepository targetRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final TicketRepository ticketRepository;

    public StatsController(TargetRepository targetRepository,
                           ScanTaskRepository scanTaskRepository,
                           VulnerabilityRepository vulnerabilityRepository,
                           TicketRepository ticketRepository) {
        this.targetRepository = targetRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.ticketRepository = ticketRepository;
    }

    @GetMapping
    public R<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        data.put("targetCount", targetRepository.count());
        data.put("scanTaskCount", scanTaskRepository.count());
        data.put("vulnCount", vulnerabilityRepository.count());
        data.put("ticketCount", ticketRepository.count());

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            severityCounts.put(s.name(), vulnerabilityRepository.countBySeverity(s));
        }
        data.put("severityCounts", severityCounts);

        data.put("recentVulns", vulnerabilityRepository.findTop5ByOrderByFoundAtDesc());

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            long count = vulnerabilityRepository.findAll().stream()
                    .filter(v -> v.getFoundAt() != null && !v.getFoundAt().isBefore(start) && v.getFoundAt().isBefore(end))
                    .count();
            Map<String, Object> point = new HashMap<>();
            point.put("date", date.toString());
            point.put("count", count);
            trend.add(point);
        }
        data.put("dailyVulnTrend", trend);

        return R.ok(data);
    }
}
