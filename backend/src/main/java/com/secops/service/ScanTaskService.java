package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Target;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.TargetRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 扫描任务业务服务
 */
@Service
public class ScanTaskService {

    private final ScanTaskRepository scanTaskRepository;
    private final TargetRepository targetRepository;
    private final ScannerEngineService scannerEngineService;

    public ScanTaskService(ScanTaskRepository scanTaskRepository, TargetRepository targetRepository,
                           ScannerEngineService scannerEngineService) {
        this.scanTaskRepository = scanTaskRepository;
        this.targetRepository = targetRepository;
        this.scannerEngineService = scannerEngineService;
    }

    public ScanTask createScanTask(Long targetId, ScanType scanType) {
        Target target = targetRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("目标不存在"));

        ScanTask task = new ScanTask();
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        task.setTaskId("SCAN-" + dateStr + "-" + String.format("%04d", System.currentTimeMillis() % 10000));
        task.setTarget(target);
        task.setScanType(scanType);
        task.setStatus(ScanStatus.PENDING);
        scanTaskRepository.save(task);

        scannerEngineService.runFullScan(task);
        return task;
    }

    public List<ScanTask> listAll() {
        return scanTaskRepository.findAll();
    }

    public ScanTask getTask(Long id) {
        return scanTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }
}
