package com.example.worker.web;

import com.example.common.dto.ChunkInfo;
import com.example.worker.storage.ChunkStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/internal/workers")
public class WorkerInventoryController {

    private final ChunkStore store;
    private final String workerId;

    public WorkerInventoryController(
            ChunkStore store,
            @Value("${worker.id:worker1}") String workerId) {
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("Worker ID must not be blank");
        }
        this.store = store;
        this.workerId = workerId;
    }

    @GetMapping("/{workerId}/chunks")
    public List<ChunkInfo> listChunks(
            @PathVariable("workerId") String requestedWorkerId)
            throws IOException {

        if (!workerId.equals(requestedWorkerId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Unknown worker: " + requestedWorkerId);
        }

        return store.listChunks();
    }
}