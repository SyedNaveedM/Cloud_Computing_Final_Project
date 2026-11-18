package com.example.worker.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class WorkerHealthController {
    @GetMapping("/internal/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
