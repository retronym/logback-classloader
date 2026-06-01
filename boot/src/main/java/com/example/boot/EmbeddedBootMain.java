package com.example.boot;

import com.example.agent.LogbackBridge;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulates the "embedded" (dev-mode) Akka runtime launch where SDK and App
 * classes are on the JVM's system ClassLoader — so we cannot customise the CL
 * that {@code Loader.getClassLoaderOfObject()} would normally return.
 *
 * <p>Solution: a Java agent ({@code agent.jar}) rewrites
 * {@code Loader.getClassLoaderOfObject} to first consult
 * {@link LogbackBridge#get()}.  We register a {@link BridgeClassLoader} there
 * before anything triggers logback's initialisation.
 *
 * <p>Topology (simulated — real embedded mode puts SDK+App on the true system CL):
 * <pre>
 *   PlatformCL
 *     └── SharedCL      sdk.jar + logback + slf4j + app.jar   (≈ system CL)
 *          └── RuntimeCL  runtime.jar
 *
 *   BridgeClassLoader (sidecar, NOT in hierarchy)
 *     parent = SharedCL
 *     child  = RuntimeCL
 *     ↑ returned by the agent-patched getClassLoaderOfObject()
 * </pre>
 *
 * <p>Run (after {@code mvn install}):
 * <pre>
 *   # via run-embedded.sh  — puts SDK+App on the actual system CL
 *   # via Maven:
 *   mvn exec:exec@embedded -pl boot
 * </pre>
 *
 * <p>Proof that all three challenges are solved:
 * <ul>
 *   <li>logback.xml found in runtime.jar  → logback status trace shows it</li>
 *   <li>CorrelationIdLayout instantiated   → custom log format in output</li>
 *   <li>logback-app.xml included          → DEBUG line from MyServiceEndpoint</li>
 * </ul>
 */
public class EmbeddedBootMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: EmbeddedBootMain <sdk-cp.txt> <runtime-cp.txt> <app-cp.txt>");
            System.exit(1);
        }

        URL[] sdkUrls     = readClasspathFile(args[0]);
        URL[] runtimeUrls = readClasspathFile(args[1]);
        URL[] appUrls     = readClasspathFile(args[2]);

        // ── Simulate embedded mode: SDK + App in a shared "system-like" CL ──
        // In true embedded mode these would simply be on ClassLoader.getSystemClassLoader().
        List<URL> sharedUrls = new ArrayList<>();
        for (URL u : sdkUrls)  sharedUrls.add(u);
        for (URL u : appUrls)  sharedUrls.add(u);

        URLClassLoader sharedCL = new URLClassLoader(
                "shared-classloader",
                sharedUrls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        // Runtime CL: isolated, parent = shared CL
        URLClassLoader runtimeCL = new URLClassLoader(
                "runtime-classloader", runtimeUrls, sharedCL);

        System.out.println("=== Embedded-mode ClassLoader Setup ===");
        printUrls("Shared  (SDK+App) ", sharedUrls);
        printUrls("Runtime           ", List.of(runtimeUrls));
        System.out.println("\n[CL] " + sharedCL + "  (≈ system CL)");
        System.out.println("[CL] " + runtimeCL);

        // ── Create the BridgeClassLoader and register it ─────────────────────
        // The bridge is returned by the agent-patched Loader.getClassLoaderOfObject().
        // It has no jars of its own; its parent is sharedCL (covers SDK+App).
        // When the shared parent cannot satisfy a lookup it searches runtimeCL.
        BridgeClassLoader bridge = new BridgeClassLoader(
                "logback-bridge", new URL[0], sharedCL);
        bridge.addChild(runtimeCL);

        // Register BEFORE loading any class that has a static Logger field.
        // The agent has already patched Loader.getClassLoaderOfObject() to
        // call LogbackBridge.get() — from this point on all logback lookups
        // go through the bridge.
        LogbackBridge.register(bridge);
        System.out.println("\n[Bridge] Registered: " + bridge);

        // Sanity check: verify bridge can see critical resources
        System.out.println("\n=== Resource visibility via BridgeClassLoader ===");
        probeResource(bridge, "logback.xml");
        probeResource(bridge, "logback-app.xml");
        probeResource(bridge, "com/example/runtime/layout/CorrelationIdLayout.class");
        System.out.println();

        // ── Load and run RuntimeMain ──────────────────────────────────────────
        // RuntimeMain's static Logger field triggers logback init.
        // Loader.getClassLoaderOfObject(configurator) now returns bridge ✓
        Thread.currentThread().setContextClassLoader(runtimeCL);

        Class<?> runtimeMainClass = runtimeCL.loadClass("com.example.runtime.RuntimeMain");
        System.out.println("[Boot] RuntimeMain loaded by: " + runtimeMainClass.getClassLoader());
        System.out.println();

        Method run = runtimeMainClass.getMethod("run", ClassLoader.class);
        run.invoke(null, runtimeCL);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static URL[] readClasspathFile(String filePath) throws Exception {
        String cp = Files.readString(Path.of(filePath)).trim();
        if (cp.isEmpty()) return new URL[0];
        String[] entries = cp.split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) urls[i] = toUrl(entries[i].trim());
        return urls;
    }

    private static URL toUrl(String path) throws MalformedURLException {
        return new File(path).toURI().toURL();
    }

    private static void printUrls(String label, List<URL> urls) {
        System.out.printf("[CP] %s:", label);
        for (URL u : urls) System.out.printf("%n         %s", u);
        System.out.println();
    }

    private static void probeResource(ClassLoader cl, String resource) {
        URL url = cl.getResource(resource);
        System.out.printf("[RES] %-60s → %s%n",
                resource, url != null ? url : "NOT FOUND");
    }
}
