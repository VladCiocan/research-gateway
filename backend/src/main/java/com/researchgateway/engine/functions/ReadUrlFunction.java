package com.researchgateway.engine.functions;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ReadUrlFunction implements BackendFunction {

    @Override
    public String slug() { return "read_url_fn"; }

    @Override
    public String description() {
        return "Fetch a URL over HTTP(S) and return its readable text content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("url", Map.of("type", "string")),
                "required", List.of("url"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String url = String.valueOf(args.getOrDefault("url", ""));
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return Map.of("error", "A valid http(s) url is required");
        }
        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
            String html = RestClient.builder().requestFactory(factory).build()
                    .get().uri(url).retrieve().body(String.class);
            if (html == null) return Map.of("url", url, "text", "");
            String text = html
                    .replaceAll("(?s)<script.*?</script>", " ")
                    .replaceAll("(?s)<style.*?</style>", " ")
                    .replaceAll("(?s)<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (text.length() > 4000) text = text.substring(0, 4000) + "…";
            return Map.of("url", url, "text", text);
        } catch (Exception ex) {
            return Map.of("url", url, "error", ex.getMessage());
        }
    }
}
