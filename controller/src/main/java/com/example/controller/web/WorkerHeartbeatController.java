package com.example.controller.web;

import com.example.common.dto.HeartbeatRequest;
import com.example.controller.service.WorkerHeartbeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/workers")
@RequiredArgsConstructor
public class WorkerHeartbeatController {
    private final WorkerHeartbeatService service;

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody HeartbeatRequest request,
            @RequestHeader(value = "X-Registration-Token", required = false) String token) {
        service.heartbeat(request, token);
        return ResponseEntity.noContent().build();
    }
}
