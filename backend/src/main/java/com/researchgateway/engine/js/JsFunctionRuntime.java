package com.researchgateway.engine.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes <b>JavaScript / TypeScript</b> function capabilities in-process via GraalJS.
 *
 * <p>A function's source must define a top-level {@code function handler(args) { ... }} that takes
 * the model-supplied arguments object and returns a JSON-serializable value. TypeScript sources are
 * transpiled to JavaScript (the type annotations are stripped) using the bundled TypeScript compiler.
 *
 * <p>Each invocation runs in a fresh, sandboxed context (no host/class/IO access) with a wall-clock
 * timeout. A small {@code httpGet}/{@code httpRequest} bridge is provided so functions can fetch
 * remote content without opening up the full host.
 */
@Component
public class JsFunctionRuntime {

    private static final int DEFAULT_TIMEOUT_MS = 5_000;
    private static final int MAX_TIMEOUT_MS = 30_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Engine engine = Engine.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "js-fn-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Lazily initialized TypeScript transpiler context (the TS compiler is multi-MB). */
    private Context transpiler;

    // ------------------------------------------------------------------ public API

    /** True if the spec carries runnable source (JS, or transpiled/raw TS). */
    public boolean isExecutable(Map<String, Object> spec) {
        return spec != null && !blank(source(spec));
    }

    /**
     * Run a function capability's spec with the given arguments. Picks the compiled JS for
     * TypeScript when available, otherwise transpiles on the fly. Always returns a value (errors
     * are returned as {@code {"error": ...}} rather than thrown).
     */
    public Object run(Map<String, Object> spec, Map<String, Object> args) {
        String code = runnableCode(spec);
        if (blank(code)) return Map.of("error", "Function has no source code.");
        int timeout = clampTimeout(spec.get("timeout_ms"));
        return execute(code, args == null ? Map.of() : args, timeout);
    }

    /** Transpile TypeScript to JavaScript (type-stripping only — no type checking). */
    public synchronized String transpileTypeScript(String tsSource) {
        if (blank(tsSource)) return tsSource;
        Context ctx = transpilerContext();
        ctx.getBindings("js").putMember("__tsSource__", tsSource);
        Value out = ctx.eval("js",
                "ts.transpileModule(__tsSource__, { compilerOptions: { target: 'ES2020' } }).outputText");
        return out.asString();
    }

    /**
     * Prepare a function spec for storage: for TypeScript sources, transpile and cache the JS under
     * {@code compiled} so runs stay fast. Returns the same map (mutated) for convenience.
     */
    public Map<String, Object> prepareForStorage(Map<String, Object> spec) {
        if (spec == null) return null;
        if (isTypeScript(spec) && !blank(str(spec.get("code")))) {
            try {
                spec.put("compiled", transpileTypeScript(str(spec.get("code"))));
            } catch (Exception ex) {
                spec.remove("compiled");   // fall back to on-the-fly transpile at run time
            }
        } else {
            spec.remove("compiled");
        }
        return spec;
    }

    // ------------------------------------------------------------------ execution

    private Object execute(String code, Map<String, Object> args, int timeoutMs) {
        String argsJson;
        try {
            argsJson = mapper.writeValueAsString(args);
        } catch (Exception e) {
            return Map.of("error", "Could not serialize arguments: " + e.getMessage());
        }

        Context ctx = newContext();
        ScheduledFuture<?> kill = watchdog.schedule(
                () -> { try { ctx.close(true); } catch (Exception ignore) { } },
                timeoutMs, TimeUnit.MILLISECONDS);
        try {
            Value bindings = ctx.getBindings("js");
            bindings.putMember("__argsJson__", argsJson);
            bindings.putMember("httpGet", (ProxyExecutable) a ->
                    doHttp("GET", a.length > 0 ? a[0].asString() : "", null));
            bindings.putMember("httpRequest", (ProxyExecutable) a ->
                    doHttp(a.length > 0 ? a[0].asString() : "GET",
                            a.length > 1 ? a[1].asString() : "",
                            a.length > 2 && !a[2].isNull() ? a[2].asString() : null));

            Value result = ctx.eval(Source.create("js", wrap(code)));
            return mapper.readValue(result.asString(), Object.class);
        } catch (PolyglotException pe) {
            return Map.of("error", pe.isCancelled()
                    ? "Execution timed out after " + timeoutMs + " ms"
                    : "Function error: " + pe.getMessage());
        } catch (Exception e) {
            return Map.of("error", "Function error: " + e.getMessage());
        } finally {
            kill.cancel(false);
            try { ctx.close(); } catch (Exception ignore) { }
        }
    }

    /** Wrap user code so it defines {@code handler(args)}; the script evaluates to a JSON string. */
    private String wrap(String userCode) {
        return "'use strict';\n" + userCode + "\n;(function(){"
                + "var __args = JSON.parse(__argsJson__);"
                + "if (typeof handler !== 'function') {"
                + " throw new Error('Define a top-level function handler(args)'); }"
                + "var __res = handler(__args);"
                + "return JSON.stringify(__res === undefined ? null : __res);"
                + "})();";
    }

    private ProxyObject doHttp(String method, String url, String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            out.put("ok", false);
            out.put("status", 0);
            out.put("body", "");
            out.put("error", "Only http(s) URLs are allowed");
            return ProxyObject.fromMap(out);
        }
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20));
            if ("GET".equalsIgnoreCase(method)) {
                rb.GET();
            } else {
                rb.method(method.toUpperCase(),
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            out.put("ok", resp.statusCode() < 400);
            out.put("status", resp.statusCode());
            out.put("body", resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("status", 0);
            out.put("body", "");
            out.put("error", e.getMessage());
        }
        return ProxyObject.fromMap(out);
    }

    // ------------------------------------------------------------------ helpers

    private String runnableCode(Map<String, Object> spec) {
        if (isTypeScript(spec)) {
            String compiled = str(spec.get("compiled"));
            if (!blank(compiled)) return compiled;
            try {
                return transpileTypeScript(str(spec.get("code")));
            } catch (Exception ex) {
                return str(spec.get("code"));   // last resort: let GraalJS try the raw source
            }
        }
        return source(spec);
    }

    private String source(Map<String, Object> spec) {
        String code = str(spec.get("code"));
        return blank(code) ? str(spec.get("compiled")) : code;
    }

    private boolean isTypeScript(Map<String, Object> spec) {
        return "typescript".equalsIgnoreCase(str(spec.get("language")))
                || "ts".equalsIgnoreCase(str(spec.get("language")));
    }

    private Context transpilerContext() {
        if (transpiler == null) {
            transpiler = newContext();
            transpiler.eval("js", readResource("/js/typescript.js"));
        }
        return transpiler;
    }

    /**
     * Build a sandboxed JS context. All contexts on the shared engine MUST use the identical host
     * configuration — GraalJS NPEs when comparing differing {@link HostAccess} configs on one engine.
     */
    private Context newContext() {
        return Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(s -> false)
                .allowCreateThread(false)
                .allowIO(false)
                .build();
    }

    private String readResource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load " + path + ": " + e.getMessage(), e);
        }
    }

    private int clampTimeout(Object raw) {
        if (raw instanceof Number n) {
            return Math.max(100, Math.min(MAX_TIMEOUT_MS, n.intValue()));
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        try { if (transpiler != null) transpiler.close(true); } catch (Exception ignore) { }
        try { engine.close(); } catch (Exception ignore) { }
    }
}
