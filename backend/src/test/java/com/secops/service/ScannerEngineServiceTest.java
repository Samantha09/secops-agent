package com.secops.service;

import com.secops.controller.ScanProgressWebSocketHandler;
import com.secops.entity.ScanTask;
import com.secops.entity.Target;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import com.secops.entity.enums.TargetType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScannerEngineServiceTest {

    @Mock SubfinderScanner subfinderScanner;
    @Mock NaabuScanner naabuScanner;
    @Mock HttpxScanner httpxScanner;
    @Mock NucleiScanner nucleiScanner;
    @Mock ScanTaskRepository scanTaskRepository;
    @Mock VulnerabilityRepository vulnerabilityRepository;
    @Mock ScanProgressWebSocketHandler webSocketHandler;

    @InjectMocks
    ScannerEngineService scannerEngineService;

    private ScanTask createTask(ScanType type) {
        Target target = new Target();
        target.setDomain("example.com");
        target.setType(TargetType.DOMAIN);

        ScanTask task = new ScanTask();
        task.setTaskId("SCAN-20260609-0001");
        task.setTarget(target);
        task.setScanType(type);
        task.setStatus(ScanStatus.PENDING);
        return task;
    }

    @BeforeEach
    void setup() {
        ScanResult emptyResult = new ScanResult();
        emptyResult.setSuccess(true);
        emptyResult.setFindings(new java.util.ArrayList<>());

        when(subfinderScanner.isAvailable()).thenReturn(true);
        when(subfinderScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(naabuScanner.isAvailable()).thenReturn(true);
        when(naabuScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(httpxScanner.isAvailable()).thenReturn(true);
        when(httpxScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(nucleiScanner.isAvailable()).thenReturn(true);
        when(nucleiScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
    }

    @Test
    void runFullScan_shouldExecuteAllStages() {
        ScanTask task = createTask(ScanType.FULL);
        scannerEngineService.runFullScan(task);

        verify(subfinderScanner, atLeastOnce()).scan(any(), any());
        verify(naabuScanner, atLeastOnce()).scan(any(), any());
        verify(httpxScanner, atLeastOnce()).scan(any(), any());
        verify(nucleiScanner, atLeastOnce()).scan(any(), any());

        ArgumentCaptor<ScanTask> captor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, atLeastOnce()).save(captor.capture());
        assertEquals(ScanStatus.COMPLETED, captor.getValue().getStatus());
        assertEquals(100, captor.getValue().getProgress());
    }

    @Test
    void runSubdomainScan_shouldExecuteOnlySubfinder() {
        ScanTask task = createTask(ScanType.SUBDOMAIN);
        scannerEngineService.runSubdomainScan(task);

        verify(subfinderScanner, atLeastOnce()).scan(any(), any());
        verify(naabuScanner, never()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, never()).scan(any(), any());
    }

    @Test
    void runPortScan_shouldExecuteOnlyNaabu() {
        ScanTask task = createTask(ScanType.PORT);
        scannerEngineService.runPortScan(task);

        verify(subfinderScanner, never()).scan(any(), any());
        verify(naabuScanner, atLeastOnce()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, never()).scan(any(), any());
    }

    @Test
    void runVulnScan_shouldExecuteOnlyNuclei() {
        ScanTask task = createTask(ScanType.VULN);
        scannerEngineService.runVulnScan(task);

        verify(subfinderScanner, never()).scan(any(), any());
        verify(naabuScanner, never()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, atLeastOnce()).scan(any(), any());
    }

    @Test
    void runFullScan_shouldBroadcastProgressEvents() {
        ScanTask task = createTask(ScanType.FULL);
        scannerEngineService.runFullScan(task);

        verify(webSocketHandler, atLeastOnce()).broadcastProgress(
                eq("SCAN-20260609-0001"), any(), anyInt(), any(), any()
        );
    }
}
