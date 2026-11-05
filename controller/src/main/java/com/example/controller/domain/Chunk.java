package com.example.controller.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    @Id
    private String chunkId;

    private String objectId;
    private int chunkIndex;
    private long size;
    private String checksum;
}
