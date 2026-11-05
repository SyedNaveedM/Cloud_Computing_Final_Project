package com.example.controller.config;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.service.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Deletes any object left in UPLOADING status by a controller crash mid-upload: such an
 * object was never acknowledged to a client, so its partial chunks are safe to discard.
 */
@Slf4j
@Component
@Order(1)
public class StartupCleanupRunner implements ApplicationRunner {

    private final StoredObjectRepository objectRepository;
    private final UploadService uploadService;

    public StartupCleanupRunner(StoredObjectRepository objectRepository, UploadService uploadService) {
        this.objectRepository = objectRepository;
        this.uploadService = uploadService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (StoredObject stale : objectRepository.findByStatus(ObjectStatus.UPLOADING)) {
            log.info("Cleaning up stale UPLOADING object {} left over from a previous run", stale.getId());
            uploadService.rollbackUpload(stale.getId());
        }
    }
}
