package com.example.controller.service;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.web.dto.ObjectSummary;
import com.example.controller.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ListService {

    private final StoredObjectRepository objectRepository;

    public ListService(StoredObjectRepository objectRepository) {
        this.objectRepository = objectRepository;
    }

    public PageResponse<ObjectSummary> list(int page, int size) {
        Page<StoredObject> result = objectRepository.findByStatus(ObjectStatus.COMPLETE, PageRequest.of(page, size));
        return new PageResponse<>(result.getContent().stream().map(ObjectSummary::from).toList(),
                page, size, result.getTotalElements());
    }
}
