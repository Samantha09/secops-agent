package com.secops.service;

import com.secops.entity.Ticket;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.TicketStatus;
import com.secops.repository.TicketRepository;
import com.secops.repository.VulnerabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
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

    public Ticket createAutoTicket(Long vulnerabilityId, String remediationAdvice) {
        Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId)
                .orElseThrow(() -> new IllegalArgumentException("漏洞不存在"));

        // 检查是否已存在该漏洞的 OPEN 工单
        List<Ticket> existing = ticketRepository.findByVulnerabilityId(vulnerabilityId);
        boolean hasOpenTicket = existing.stream()
                .anyMatch(t -> t.getStatus() != TicketStatus.CLOSED);
        if (hasOpenTicket) {
            log.info("漏洞 {} 已存在未关闭工单，跳过自动创建", vulnerabilityId);
            return null;
        }

        Ticket ticket = new Ticket();
        ticket.setTitle("[Auto] " + vuln.getName());
        ticket.setPriority(vuln.getSeverity());
        ticket.setVulnerability(vuln);
        ticket.setStatus(TicketStatus.OPEN);
        return ticketRepository.save(ticket);
    }
}
