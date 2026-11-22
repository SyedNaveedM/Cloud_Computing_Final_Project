package com.example.controller.service;

import com.example.controller.exception.ObjectUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class DeleteService {
    private final ObjectDeletionMetadataService metadata;
    private final ChunkDeletionJobService jobs;
    private final ChunkDeletionProcessor processor;

    @Transactional(propagation = Propagation.NEVER)
    public void deleteObject(String objectId) {
        metadata.begin(objectId);
        // Bounded request work. The background runner continues larger deletions.
        for (var pending : jobs.findDueJobsForObject(objectId, Instant.now())) {
            processor.process(pending);
        }
        if (!metadata.finish(objectId)) {
            throw new ObjectUnavailableException(
                    "Object is unavailable for reads; physical deletion is pending. "
                    + "Cleanup will retry when workers are available. Retry DELETE later.");
        }
    }
}
