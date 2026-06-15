package com.researchgateway.service;

import com.researchgateway.domain.Provider;
import com.researchgateway.dto.ProviderDto;
import com.researchgateway.dto.ProviderRequest;
import com.researchgateway.llm.LlmClient;
import com.researchgateway.repository.ProviderRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class ProviderService {

    private final ProviderRepository repository;
    private final LlmClient llmClient;

    public ProviderService(ProviderRepository repository, LlmClient llmClient) {
        this.repository = repository;
        this.llmClient = llmClient;
    }

    @Transactional(readOnly = true)
    public List<ProviderDto> list() {
        return repository.findAllByOrderByNameAsc().stream().map(ProviderDto::from).toList();
    }

    public ProviderDto create(ProviderRequest req) {
        Provider p = new Provider();
        apply(p, req, true);
        return ProviderDto.from(repository.save(p));
    }

    public ProviderDto update(UUID id, ProviderRequest req) {
        Provider p = find(id);
        apply(p, req, false);
        return ProviderDto.from(repository.save(p));
    }

    public void delete(UUID id) {
        repository.delete(find(id));
    }

    public Map<String, Object> test(UUID id) {
        Provider p = find(id);
        String error = llmClient.ping(p);
        return error == null
                ? Map.of("ok", true, "message", "Connected to " + p.getModel())
                : Map.of("ok", false, "message", error);
    }

    private void apply(Provider p, ProviderRequest req, boolean creating) {
        p.setName(req.name());
        p.setType(req.type() != null ? req.type() : "vllm");
        p.setBaseUrl(req.baseUrl());
        p.setModel(req.model());
        if (req.contextSize() != null) p.setContextSize(req.contextSize());
        if (req.maxTokens() != null) p.setMaxTokens(req.maxTokens());
        if (req.temperature() != null) p.setTemperature(req.temperature());
        if (req.enabled() != null) p.setEnabled(req.enabled());
        else if (creating) p.setEnabled(false);
        // Only overwrite the key when a new value is supplied; blank keeps the existing one.
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKey(req.apiKey());
        }
        if (p.getTemperature() == null) p.setTemperature(new BigDecimal("0.20"));
    }

    private Provider find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Provider not found: " + id));
    }
}
