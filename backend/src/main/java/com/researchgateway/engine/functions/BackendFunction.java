package com.researchgateway.engine.functions;

import java.util.Map;

/** A native function that runs in the backend (vs. an external tool). */
public interface BackendFunction {

    /** Capability slug this function is registered under. */
    String slug();

    String description();

    /** JSON Schema describing the input arguments. */
    Map<String, Object> inputSchema();

    /** Execute with the model-provided arguments and return a JSON-serializable result. */
    Object execute(Map<String, Object> args);
}
