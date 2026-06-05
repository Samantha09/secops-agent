package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Ticket;
import com.secops.entity.enums.TicketStatus;
import com.secops.service.TicketService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public R<List<Ticket>> list() {
        return R.ok(ticketService.list());
    }

    @PostMapping
    public R<Ticket> create(@RequestBody CreateRequest req) {
        return R.ok(ticketService.create(req.vulnerabilityId));
    }

    @PatchMapping("/{id}")
    public R<Ticket> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return R.ok(ticketService.update(id, req.status, req.assignee));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return R.ok();
    }

    public record CreateRequest(Long vulnerabilityId) {}
    public record UpdateRequest(TicketStatus status, String assignee) {}
}
