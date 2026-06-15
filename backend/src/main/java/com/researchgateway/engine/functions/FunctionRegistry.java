package com.researchgateway.engine.functions;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Indexes all {@link BackendFunction} beans by their capability slug. */
@Component
public class FunctionRegistry {

    private final Map<String, BackendFunction> bySlug;

    public FunctionRegistry(List<BackendFunction> functions) {
        this.bySlug = functions.stream()
                .collect(Collectors.toMap(BackendFunction::slug, Function.identity()));
    }

    public BackendFunction get(String slug) {
        return bySlug.get(slug);
    }

    public boolean has(String slug) {
        return bySlug.containsKey(slug);
    }
}
