package com.example.controller.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class StoredObject {

    @Id
    private String id;

    private String name;
    private long size;
    private String contentType;
    private Instant createdAt;
    private Instant modifiedAt;
    private String checksum;
    private int chunkCount;

    @Enumerated(EnumType.STRING)
    private ObjectStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "object_metadata", joinColumns = @JoinColumn(name = "object_id"))
    @MapKeyColumn(name = "key_name")
    @Column(name = "value_data")
    private Map<String, String> customMetadata = new HashMap<>();
}
