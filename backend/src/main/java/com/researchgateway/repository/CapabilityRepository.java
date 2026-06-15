package com.researchgateway.repository;

import com.researchgateway.domain.Capability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapabilityRepository extends JpaRepository<Capability, UUID> {
    List<Capability> findAllByOrderByTypeAscNameAsc();
    List<Capability> findByTypeOrderByNameAsc(String type);
    Optional<Capability> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
