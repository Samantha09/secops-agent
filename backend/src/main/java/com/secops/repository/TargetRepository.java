package com.secops.repository;

import com.secops.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TargetRepository extends JpaRepository<Target, Long> {
    Optional<Target> findByDomain(String domain);
    boolean existsByDomain(String domain);
}
