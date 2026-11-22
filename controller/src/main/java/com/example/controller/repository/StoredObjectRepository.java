package com.example.controller.repository;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface StoredObjectRepository extends JpaRepository<StoredObject, String> {

    Page<StoredObject> findByStatus(ObjectStatus status, Pageable pageable);

    List<StoredObject> findByStatus(ObjectStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StoredObject o where o.id = :objectId")
    Optional<StoredObject> findByIdForUpdate(
            @Param("objectId") String objectId);
}
