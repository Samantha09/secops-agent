package com.secops.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TargetDTO {
    private Long id;
    private String domain;
    private boolean verified;
    private String txtRecord;
    private LocalDateTime txtVerifiedAt;
    private int subdomains;
    private int ports;
    private LocalDateTime lastScanAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
