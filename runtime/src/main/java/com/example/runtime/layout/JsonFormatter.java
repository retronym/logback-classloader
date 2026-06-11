package com.example.runtime.layout;

import java.util.Map;

/**
 * Minimal stand-in for {@code ch.qos.logback.contrib.json.JsonFormatter}.
 *
 * <p>Models the <em>nested component</em> that {@link JsonLayout} owns — the
 * real config wires {@code <jsonFormatter class="...JacksonJsonFormatter"/>}
 * inside {@code <layout class="...LogbackJsonLayout">}. We keep our own tiny
 * interface so the sandbox needs no logback-contrib / Jackson dependency; the
 * point is to exercise programmatic wiring of a layout's sub-component, not JSON
 * fidelity.</p>
 */
public interface JsonFormatter {
    String toJson(Map<String, Object> map);
}
