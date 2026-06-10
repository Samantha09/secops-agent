package com.secops.repository;

import com.secops.entity.Ticket;
import com.secops.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByStatus(TicketStatus status);
    long countByStatus(TicketStatus status);

    List<Ticket> findByVulnerabilityId(Long vulnerabilityId);
}
