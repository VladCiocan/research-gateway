package com.researchgateway.repository;

import com.researchgateway.domain.Flow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowRepository extends JpaRepository<Flow, UUID> {
    List<Flow> findAllByOrderByUpdatedAtDesc();
    Optional<Flow> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
