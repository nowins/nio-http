package com.nowin.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A lightweight template engine that loads templates from the classpath
 * and replaces {{key}} placeholders with provided values.
 */
public class SimpleTemplateEngine {
    private final String template;

    public SimpleTemplateEngine(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Template not found: " + resourcePath);
            }
            this.template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String render(Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }
}
