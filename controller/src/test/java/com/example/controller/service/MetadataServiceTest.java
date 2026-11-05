package com.example.controller.service;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.exception.ObjectConflictException;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.web.dto.ObjectMetadataResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("fake")
class MetadataServiceTest {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private MetadataService metadataService;
    @Autowired
    private StoredObjectRepository objectRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jacksonPreservesJsonNullAsMapValue() {
        Map<String, String> map = objectMapper.readValue("{\"a\":\"1\",\"b\":null}", new TypeReference<Map<String, String>>() {
        });
        assertTrue(map.containsKey("b"));
        assertEquals(null, map.get("b"));
    }

    @Test
    void patchAddsUpdatesAndRemovesKeysWithoutTouchingChunks() {
        Map<String, String> initial = new HashMap<>();
        initial.put("owner", "alice");
        StoredObject object = uploadService.upload(
                new ByteArrayInputStream(randomBytes(1000)), "f.bin", "application/octet-stream",
                toJson(initial));
        var before = metadataService.getMetadata(object.getId());

        Map<String, String> patch = new HashMap<>();
        patch.put("owner", "bob");
        patch.put("project", "cloud");
        ObjectMetadataResponse afterAdd = metadataService.patchMetadata(object.getId(), patch);

        assertEquals("bob", afterAdd.customMetadata().get("owner"));
        assertEquals("cloud", afterAdd.customMetadata().get("project"));
        assertTrue(afterAdd.modifiedAt().isAfter(before.modifiedAt()) || afterAdd.modifiedAt().equals(before.modifiedAt()));
        assertEquals(before.chunks().size(), afterAdd.chunks().size());

        Map<String, String> removal = new HashMap<>();
        removal.put("owner", null);
        ObjectMetadataResponse afterRemove = metadataService.patchMetadata(object.getId(), removal);

        assertFalse(afterRemove.customMetadata().containsKey("owner"));
        assertEquals("cloud", afterRemove.customMetadata().get("project"));
    }

    @Test
    void patchOnDeletingObjectIsConflict() {
        StoredObject uploaded = uploadService.upload(
                new ByteArrayInputStream(randomBytes(10)), "f.bin", "application/octet-stream", null);
        StoredObject object = objectRepository.findById(uploaded.getId()).orElseThrow();
        object.setStatus(ObjectStatus.DELETING);
        objectRepository.save(object);

        assertThrows(ObjectConflictException.class,
                () -> metadataService.patchMetadata(object.getId(), Map.of("k", "v")));
    }

    private String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(3).nextBytes(data);
        return data;
    }
}
