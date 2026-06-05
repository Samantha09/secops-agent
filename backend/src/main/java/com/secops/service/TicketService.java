package com.secops.service;

import com.secops.entity.Ticket;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.TicketStatus;
import com.secops.repository.TicketRepository;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final VulnerabilityRepository vulnerabilityRepository;

    public TicketService(TicketRepository ticketRepository, VulnerabilityRepository vulnerabilityRepository) {
        this.ticketRepository = ticketRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    public List<Ticket> list() {
        return ticketRepository.findAll();
    }

    public Ticket create(Long vulnerabilityId) {
        Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId)
                .orElseThrow(() -> new IllegalArgumentException("漏洞不存在"));
        Ticket ticket = new Ticket();
        ticket.setTitle(vuln.getName());
        ticket.setPriority(vuln.getSeverity());
        ticket.setVulnerability(vuln);
        return ticketRepository.save(ticket);
    }

    public Ticket update(Long id, TicketStatus status, String assignee) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在"));
        if (status != null) ticket.setStatus(status);
        if (assignee != null) ticket.setAssignee(assignee);
        return ticketRepository.save(ticket);
    }

    public void delete(Long id) {
        ticketRepository.deleteById(id);
    }
}
