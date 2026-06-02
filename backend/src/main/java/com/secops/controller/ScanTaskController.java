package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanType;
import com.secops.repository.VulnerabilityRepository;
import com.secops.service.ScanTaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 扫描任务 REST API
 */
@RestController
@RequestMapping("/api/scans")
public class ScanTaskController {

    private final ScanTaskService scanTaskService;
    private final VulnerabilityRepository vulnerabilityRepository;

    public ScanTaskController(ScanTaskService scanTaskService, VulnerabilityRepository vulnerabilityRepository) {
        this.scanTaskService = scanTaskService;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @GetMapping
    public R<List<ScanTask>> list() {
        return R.ok(scanTaskService.listAll());
    }

    @PostMapping
    public R<ScanTask> create(@RequestBody Map<String, Object> body) {
        Long targetId = Long.valueOf(body.get("targetId").toString());
        ScanType type = ScanType.valueOf(body.get("scanType").toString());
        return R.ok(scanTaskService.createScanTask(targetId, type));
    }

    @GetMapping("/{id}")
    public R<ScanTask> get(@PathVariable Long id) {
        return R.ok(scanTaskService.getTask(id));
    }

    @GetMapping("/{id}/vulns")
    public R<List<Vulnerability>> vulns(@PathVariable Long id) {
        return R.ok(vulnerabilityRepository.findByScanTaskId(id));
    }
}
