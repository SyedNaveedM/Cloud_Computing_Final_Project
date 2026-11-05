package com.example.controller.web.dto;

import com.example.controller.domain.StoredObject;

public record ObjectSummary(String id, String name, long size, String contentType) {

    public static ObjectSummary from(StoredObject object) {
        return new ObjectSummary(object.getId(), object.getName(), object.getSize(), object.getContentType());
    }
}
