package com.example.runtime.layout;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

/**
 * Sandbox analog of {@code kalix.runtime.LogbackJsonLayout}.
 *
 * <p>The third variety: a custom {@link LayoutBase} with a <b>nested component</b>
 * ({@link #setJsonFormatter}) and a <b>scalar property</b> ({@link #setLogOrigin}),
 * mirroring:</p>
 *
 * <pre>{@code
 * <layout class="kalix.runtime.LogbackJsonLayout">
 *   <jsonFormatter class="ch.qos.logback.contrib.jackson.JacksonJsonFormatter"/>
 *   <logOrigin>sdk</logOrigin>
 * </layout>
 * }</pre>
 *
 * <p>A layout is <em>not</em> a converter, so there is no supplier registry for
 * it — and {@code <layout class="...">} would make logback resolve this class by
 * name through its own (SDK) classloader, which can't see runtime.jar. So this
 * variety is configured purely programmatically: {@code new JsonLayout()}, then
 * {@code setJsonFormatter(...)} / {@code setLogOrigin(...)}, then {@code start()}.
 * See {@code SupplierBasedLogbackConfig}.</p>
 */
public class JsonLayout extends LayoutBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private JsonFormatter jsonFormatter;
    private String logOrigin = "unknown";

    /** The nested component, mirroring {@code <jsonFormatter class="..."/>}. */
    public void setJsonFormatter(JsonFormatter jsonFormatter) {
        this.jsonFormatter = jsonFormatter;
    }

    /** The scalar property, mirroring {@code <logOrigin>sdk</logOrigin>}. */
    public void setLogOrigin(String logOrigin) {
        this.logOrigin = logOrigin;
    }

    @Override
    public void start() {
        if (jsonFormatter == null) {
            addError("No jsonFormatter set for JsonLayout");
            return;
        }
        super.start();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        if (!isStarted()) {
            return "";
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", FMT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        fields.put("level", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("correlationId", event.getMDCPropertyMap().getOrDefault("correlationId", "-"));
        fields.put("logOrigin", logOrigin);
        fields.put("message", event.getFormattedMessage());
        return jsonFormatter.toJson(fields) + System.lineSeparator();
    }
}
