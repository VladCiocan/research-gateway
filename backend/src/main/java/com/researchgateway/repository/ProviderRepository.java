package com.researchgateway.repository;

import com.researchgateway.domain.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {
    List<Provider> findAllByOrderByNameAsc();
    Optional<Provider> findFirstByEnabledTrueOrderByUpdatedAtDesc();
}
