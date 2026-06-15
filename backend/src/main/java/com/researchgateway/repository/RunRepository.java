package com.researchgateway.repository;

import com.researchgateway.domain.Run;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
    Page<Run> findAllByOrderByStartedAtDesc(Pageable pageable);
    long countByStatus(String status);
}
