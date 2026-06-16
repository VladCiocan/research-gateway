package com.researchgateway.repository;

import com.researchgateway.domain.RunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RunStepRepository extends JpaRepository<RunStep, UUID> {
}
