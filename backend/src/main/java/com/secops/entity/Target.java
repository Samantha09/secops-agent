package com.secops.entity;

import com.secops.entity.enums.TargetType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "targets")
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType type = TargetType.DOMAIN;

    @Column(nullable = false, unique = true)
    private String domain;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "txt_record")
    private String txtRecord;

    @Column(name = "txt_verified_at")
    private LocalDateTime txtVerifiedAt;

    private int subdomains;

    private int ports;

    @Column(name = "last_scan_at")
    private LocalDateTime lastScanAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
