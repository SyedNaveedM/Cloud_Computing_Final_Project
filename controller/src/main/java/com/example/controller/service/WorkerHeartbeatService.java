package com.example.controller.service;

import com.example.common.dto.HeartbeatRequest;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.exception.ObjectConflictException;
import com.example.controller.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WorkerHeartbeatService {
    private final WorkerRepository workers;

    @Transactional
    public void heartbeat(HeartbeatRequest request, String token) {
        if (request == null || request.workerId() == null || request.workerId().isBlank()
                || request.usedBytes() < 0 || request.chunkCount() < 0) {
            throw new InvalidRequestException("Valid worker ID and nonnegative counters are required");
        }
        Worker worker = workers.findByIdForUpdate(request.workerId()).orElse(null);
        if (worker == null || worker.getStatus() != WorkerStatus.ALIVE
                || token == null || token.isBlank()
                || !Objects.equals(token, worker.getRegistrationToken())) {
            throw new ObjectConflictException("Worker must register again before sending heartbeats");
        }
        worker.setLastHeartbeat(Instant.now());
        worker.setUsedBytes(request.usedBytes());
        worker.setChunkCount(request.chunkCount());
        workers.save(worker);
    }

    @Transactional
    public int expireBefore(Instant cutoff) {
        int expired = 0;
        for (Worker snapshot : workers.findByStatus(WorkerStatus.ALIVE).stream()
                .sorted(java.util.Comparator.comparing(Worker::getWorkerId)).toList()) {
            Worker worker = workers.findByIdForUpdate(snapshot.getWorkerId()).orElse(null);
            if (worker != null && worker.getStatus() == WorkerStatus.ALIVE
                    && (worker.getLastHeartbeat() == null || worker.getLastHeartbeat().isBefore(cutoff))) {
                worker.setStatus(WorkerStatus.DEAD);
                workers.save(worker);
                expired++;
            }
        }
        return expired;
    }

    @Transactional
    public void requireRegistrationAfterControllerRestart() {
        for (Worker worker : workers.findAll()) {
            worker.setStatus(WorkerStatus.DEAD);
            workers.save(worker);
        }
    }
}
