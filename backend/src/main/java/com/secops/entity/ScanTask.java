package com.secops.entity;

import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "scan_tasks")
@Data
public class ScanTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String taskId;

    @ManyToOne
    @JoinColumn(name = "target_id")
    private Target target;

    @Enumerated(EnumType.STRING)
    private ScanStatus status = ScanStatus.PENDING;

    private int progress = 0;

    @Enumerated(EnumType.STRING)
    private ScanType scanType;

    @Column(length = 50000)
    private String rawOutput;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String errorMessage;
}
