package com.example.controller.service;

import com.example.common.dto.ChunkInfo;
import com.example.common.dto.RegisterRequest;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.service.RegistrationChunkVerifier.Result;
import com.example.controller.service.RegistrationCompletionService.ChunkCheck;
import com.example.controller.service.RegistrationCompletionService.CompletionResult;
import com.example.controller.service.RegistrationMetadataService.RegistrationAttempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Service
public class RegistrationCoordinator {

    private final RegistrationMetadataService metadataService;
    private final WorkerClient workerClient;
    private final RegistrationInventoryValidator inventoryValidator;
    private final WorkerRegistrationService comparisonService;
    private final ChunkRepository chunkRepository;
    private final RegistrationChunkVerifier verifier;
    private final RegistrationCompletionService completionService;

    public RegistrationCoordinator(
            RegistrationMetadataService metadataService,
            WorkerClient workerClient,
            RegistrationInventoryValidator inventoryValidator,
            WorkerRegistrationService comparisonService,
            ChunkRepository chunkRepository,
            RegistrationChunkVerifier verifier,
            RegistrationCompletionService completionService) {
        this.metadataService = metadataService;
        this.workerClient = workerClient;
        this.inventoryValidator = inventoryValidator;
        this.comparisonService = comparisonService;
        this.chunkRepository = chunkRepository;
        this.verifier = verifier;
        this.completionService = completionService;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CompletionResult register(RegisterRequest request) {
        return registerWithToken(request).result();
    }

    @Transactional(propagation = Propagation.NEVER)
    public RegistrationReply registerWithToken(RegisterRequest request) {
        // This separate service commits REGISTERING before any HTTP call.
        RegistrationAttempt attempt = metadataService.begin(request);

        // Keep this attempt's address and token throughout verification.
        Worker target = new Worker();
        target.setWorkerId(attempt.workerId());
        target.setUrl(attempt.url());
        target.setRegistrationToken(attempt.token());
        target.setStatus(WorkerStatus.REGISTERING);

        List<ChunkInfo> confirmedInventory;

        try {
            Map<String, ChunkInfo> normalized =
                    inventoryValidator.validate(
                            workerClient.listChunks(target));

            confirmedInventory = List.copyOf(normalized.values());
        } catch (WorkerUnavailableException | InvalidRequestException error) {
            log.warn(
                    "Inventory check failed for worker {}: {}",
                    attempt.workerId(),
                    error.getMessage());

            return new RegistrationReply(CompletionResult.RETRY_REQUIRED, null);
        }

        var comparison = comparisonService.compareInventory(
                attempt.workerId(), confirmedInventory);

        Set<String> toVerify = new TreeSet<>();
        toVerify.addAll(comparison.matching());
        toVerify.addAll(comparison.mismatched());
        toVerify.addAll(comparison.notReported());

        // Unknown chunks have no authoritative controller metadata.
        // Leave their bytes untouched.
        Map<String, ChunkCheck> checks = new HashMap<>();

        for (Chunk expected : chunkRepository.findAllById(toVerify)) {
            Result result = verifier.verify(target, expected);

            if (result == Result.UNAVAILABLE) {
                log.warn(
                        "Could not verify chunk {} on worker {}",
                        expected.getChunkId(),
                        attempt.workerId());

                return new RegistrationReply(CompletionResult.RETRY_REQUIRED, null);
            }

            checks.put(
                    expected.getChunkId(),
                    new ChunkCheck(
                            expected.getObjectId(),
                            expected.getSize(),
                            expected.getChecksum(),
                            result));
        }

        // This separate service opens the final database transaction.
        CompletionResult result = completionService.complete(
                attempt, confirmedInventory, checks);
        return new RegistrationReply(result,
                result == CompletionResult.COMPLETED ? attempt.token() : null);
    }

    public record RegistrationReply(CompletionResult result, String token) { }
}
