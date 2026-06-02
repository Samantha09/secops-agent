package com.secops.controller;

import com.secops.entity.ScanTask;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import com.secops.repository.VulnerabilityRepository;
import com.secops.service.ScanTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class ScanTaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ScanTaskService scanTaskService;

    @MockBean
    VulnerabilityRepository vulnerabilityRepository;

    @MockBean
    com.secops.security.JwtUtil jwtUtil;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void list_shouldReturnOk() throws Exception {
        when(scanTaskService.listAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void create_shouldReturnOk() throws Exception {
        ScanTask task = new ScanTask();
        task.setId(1L);
        task.setTaskId("SCAN-20250602-0001");
        task.setStatus(ScanStatus.PENDING);
        task.setScanType(ScanType.FULL);

        when(scanTaskService.createScanTask(eq(1L), eq(ScanType.FULL))).thenReturn(task);

        mockMvc.perform(post("/api/scans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":1,\"scanType\":\"FULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("SCAN-20250602-0001"));
    }

    @Test
    void get_shouldReturnOk() throws Exception {
        ScanTask task = new ScanTask();
        task.setId(1L);
        task.setTaskId("SCAN-20250602-0001");

        when(scanTaskService.getTask(1L)).thenReturn(task);

        mockMvc.perform(get("/api/scans/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void vulns_shouldReturnOk() throws Exception {
        when(vulnerabilityRepository.findByScanTaskId(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/scans/1/vulns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
