package com.secops.repository;

import com.secops.entity.ScanTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanTaskRepository extends JpaRepository<ScanTask, Long> {
    List<ScanTask> findByTargetId(Long targetId);
}
