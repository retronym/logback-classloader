package com.example.runtime.layout;

import java.util.Map;

/**
 * Trivial dependency-free {@link JsonFormatter} — serializes a flat map to a
 * one-line JSON object. Stands in for {@code JacksonJsonFormatter} so the
 * sandbox stays self-contained.
 */
public class DefaultJsonFormatter implements JsonFormatter {

    @Override
    public String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
              .append(escape(String.valueOf(e.getValue()))).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
