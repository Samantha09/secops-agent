package com.secops.controller;

import com.secops.dto.CreateTargetRequest;
import com.secops.service.TargetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TargetController.class)
@AutoConfigureMockMvc(addFilters = false)
class TargetControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TargetService targetService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void list_shouldReturnOk() throws Exception {
        mockMvc.perform(get("/api/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void create_shouldReturnOk() throws Exception {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");

        mockMvc.perform(post("/api/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void create_shouldReturnBadRequest_whenDomainInvalid() throws Exception {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("invalid");

        mockMvc.perform(post("/api/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verify_shouldReturnOk() throws Exception {
        mockMvc.perform(post("/api/targets/1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void delete_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/api/targets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
