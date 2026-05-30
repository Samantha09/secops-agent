package com.secops.controller;

import com.secops.common.R;
import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.service.TargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService targetService;

    @GetMapping
    public R<List<TargetDTO>> list() {
        return R.ok(targetService.listAll());
    }

    @PostMapping
    public R<TargetDTO> create(@Valid @RequestBody CreateTargetRequest request) {
        return R.ok(targetService.create(request));
    }

    @PostMapping("/{id}/verify")
    public R<TargetDTO> verify(@PathVariable Long id) {
        return R.ok(targetService.verify(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        targetService.delete(id);
        return R.ok();
    }
}
