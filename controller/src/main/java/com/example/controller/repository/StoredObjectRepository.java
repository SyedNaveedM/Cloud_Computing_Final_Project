package com.example.controller.repository;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoredObjectRepository extends JpaRepository<StoredObject, String> {

    Page<StoredObject> findByStatus(ObjectStatus status, Pageable pageable);

    List<StoredObject> findByStatus(ObjectStatus status);
}
