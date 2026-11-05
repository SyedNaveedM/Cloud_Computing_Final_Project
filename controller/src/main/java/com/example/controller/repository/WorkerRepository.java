package com.example.controller.repository;

import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkerRepository extends JpaRepository<Worker, String> {

    List<Worker> findByStatus(WorkerStatus status);
}
