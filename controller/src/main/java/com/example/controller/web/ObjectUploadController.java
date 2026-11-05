package com.example.controller.web;

import com.example.controller.domain.StoredObject;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.service.UploadService;
import com.example.controller.web.dto.StoredObjectResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/objects")
public class ObjectUploadController {

    private final UploadService uploadService;

    public ObjectUploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredObjectResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadataJson) {

        if (file.isEmpty()) {
            throw new InvalidRequestException("file is required and must not be empty");
        }

        StoredObject stored;
        try {
            stored = uploadService.upload(file.getInputStream(), resolveName(file), resolveContentType(file), metadataJson);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading uploaded file", e);
        }

        return ResponseEntity.created(URI.create("/api/v1/objects/" + stored.getId()))
                .body(StoredObjectResponse.from(stored));
    }

    private String resolveName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "unnamed" : name;
    }

    private String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return (contentType == null || contentType.isBlank()) ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
    }
}
