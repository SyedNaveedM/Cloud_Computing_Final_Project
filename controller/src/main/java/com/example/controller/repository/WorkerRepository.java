package com.example.controller.repository;

import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkerRepository extends JpaRepository<Worker, String> {

    List<Worker> findByStatus(WorkerStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Worker w where w.workerId = :workerId")
    Optional<Worker> findByIdForUpdate(
            @Param("workerId") String workerId);
}