package com.example.controller.web;

import com.example.controller.service.MetadataService;
import com.example.controller.web.dto.ObjectMetadataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/objects/{id}/metadata")
public class ObjectMetadataController {

    private final MetadataService metadataService;

    public ObjectMetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping
    public ObjectMetadataResponse getMetadata(@PathVariable String id) {
        return metadataService.getMetadata(id);
    }

    @PatchMapping
    public ObjectMetadataResponse patchMetadata(@PathVariable String id, @RequestBody Map<String, String> patch) {
        return metadataService.patchMetadata(id, patch);
    }
}
