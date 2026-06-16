package com.researchgateway.engine.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsFunctionRuntimeTest {

    private static final JsFunctionRuntime JS = new JsFunctionRuntime();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertInstanceOf(Map.class, o);
        return (Map<String, Object>) o;
    }

    @Test
    void runsJavaScriptHandler() {
        Map<String, Object> spec = Map.of("language", "javascript",
                "code", "function handler(a){ return { sum: a.x + a.y, items: [a.x, a.y] }; }");
        Map<String, Object> r = asMap(JS.run(spec, Map.of("x", 2, "y", 3)));
        assertEquals(5, r.get("sum"));
        assertEquals(java.util.List.of(2, 3), r.get("items"));
    }

    @Test
    void returnsErrorWhenHandlerMissing() {
        Map<String, Object> spec = Map.of("language", "javascript",
                "code", "var x = 1;");   // no handler defined
        Map<String, Object> r = asMap(JS.run(spec, Map.of()));
        assertTrue(String.valueOf(r.get("error")).contains("handler"), () -> r.toString());
    }

    @Test
    void httpBridgeRejectsNonHttpUrls() {
        Map<String, Object> spec = Map.of("language", "javascript",
                "code", "function handler(a){ return httpGet('file:///etc/passwd'); }");
        Map<String, Object> r = asMap(JS.run(spec, Map.of()));
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).contains("http"));
    }

    @Test
    void transpilesAndRunsTypeScriptOnTheFly() {
        Map<String, Object> spec = Map.of("language", "typescript",
                "code", "interface Args { name: string }\n"
                        + "function handler(args: Args): { greeting: string } {\n"
                        + "  return { greeting: 'hi ' + args.name };\n"
                        + "}");
        Map<String, Object> r = asMap(JS.run(spec, Map.of("name", "Vlad")));
        assertEquals("hi Vlad", r.get("greeting"));
    }

    @Test
    void prepareForStorageCachesCompiledJavaScript() {
        Map<String, Object> spec = new HashMap<>();
        spec.put("language", "typescript");
        spec.put("code", "function handler(a: { n: number }) { return { doubled: a.n * 2 }; }");
        JS.prepareForStorage(spec);

        assertTrue(spec.containsKey("compiled"));
        String compiled = String.valueOf(spec.get("compiled"));
        assertFalse(compiled.contains(": number"), "type annotations should be stripped");
        Map<String, Object> r = asMap(JS.run(spec, Map.of("n", 21)));
        assertEquals(42, r.get("doubled"));
    }

    @Test
    @Timeout(20)
    void enforcesExecutionTimeout() {
        Map<String, Object> spec = Map.of("language", "javascript",
                "code", "function handler(a){ var i = 0; while (true) { i++; } }",
                "timeout_ms", 600);
        Map<String, Object> r = asMap(JS.run(spec, Map.of()));
        assertTrue(String.valueOf(r.get("error")).toLowerCase().contains("timed out"), () -> r.toString());
    }
}
