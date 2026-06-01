package com.example.sdk;

/**
 * Public API loaded by the SDK ClassLoader.
 * Both RUNTIME and APP see this type via parent delegation.
 */
public interface ServiceEndpoint {
    String name();
    void handle(String request);
}
