package com.example.boot;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Constructs a layered classloader hierarchy and launches RuntimeMain.
 *
 * <pre>
 *   PlatformCL
 *     └── LogbackClassLoader  logback-classic + logback-core + slf4j-api
 *          │  ↕ (downward search with anti-loop ThreadLocal guard)
 *          └── SDK CL          sdk.jar
 *               └── Runtime CL  runtime.jar  (Layout, Filter, logback.xml)
 *                    └── App CL  app.jar      (logback-app.xml, ServiceEndpoint)
 * </pre>
 *
 * The LogbackClassLoader is the crux of the solution.  Because all logback
 * and Joran classes are loaded by it, every CL lookup that logback makes
 * (config file, Layout class, Filter class, included resource) is intercepted
 * at the LogbackClassLoader.  When its own jars don't have the answer it
 * reaches down into App CL (which covers the full child hierarchy via normal
 * parent delegation) — with a ThreadLocal guard preventing re-entrant loops.
 *
 * No system properties, no TCCL manipulation, no reflection-based hacks needed.
 */
public class BootMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: BootMain <sdk-cp.txt> <runtime-cp.txt> <app-cp.txt>");
            System.exit(1);
        }

        URL[] sdkUrls     = readClasspathFile(args[0]);
        URL[] runtimeUrls = readClasspathFile(args[1]);
        URL[] appUrls     = readClasspathFile(args[2]);

        // ── Split SDK classpath into logback layer vs SDK-only ───────────────
        // sdk-cp.txt contains sdk.jar + logback-classic + logback-core + slf4j-api.
        // We hoist logback/slf4j into their own parent CL (Layer 0) so that every
        // class and resource lookup logback performs goes through LogbackClassLoader.
        List<URL> logbackLayerUrls = new ArrayList<>();
        List<URL> sdkOnlyUrls     = new ArrayList<>();
        for (URL url : sdkUrls) {
            String filename = Path.of(url.toURI()).getFileName().toString().toLowerCase();
            if (filename.startsWith("logback") || filename.startsWith("slf4j")) {
                logbackLayerUrls.add(url);
            } else {
                sdkOnlyUrls.add(url);
            }
        }

        System.out.println("=== Layered ClassLoader Setup ===");
        printUrls("Logback ", logbackLayerUrls);
        printUrls("SDK     ", sdkOnlyUrls);
        printUrls("Runtime ", Arrays.asList(runtimeUrls));
        printUrls("App     ", Arrays.asList(appUrls));

        // ── Build the hierarchy ───────────────────────────────────────────────

        LogbackClassLoader logbackCL = new LogbackClassLoader(
                logbackLayerUrls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        URLClassLoader sdkCL = new URLClassLoader(
                "sdk-classloader",
                sdkOnlyUrls.toArray(new URL[0]),
                logbackCL);

        URLClassLoader runtimeCL = new URLClassLoader(
                "runtime-classloader", runtimeUrls, sdkCL);

        URLClassLoader appCL = new URLClassLoader(
                "app-classloader", appUrls, runtimeCL);

        // Inject appCL as the downward-search target.
        // appCL covers the full child hierarchy:
        //   appCL.loadClass/getResource → runtimeCL → sdkCL → logbackCL (guard active)
        logbackCL.addChild(appCL);

        System.out.println("\n[CL] " + logbackCL + "  (downward → appCL)");
        System.out.println("[CL] " + sdkCL);
        System.out.println("[CL] " + runtimeCL);
        System.out.println("[CL] " + appCL);

        // Resource visibility sanity check
        System.out.println("\n=== Resource visibility via LogbackClassLoader ===");
        probeResource(logbackCL, "logback.xml");
        probeResource(logbackCL, "logback-app.xml");
        probeResource(logbackCL, "com/example/runtime/layout/CorrelationIdLayout.class");

        System.out.println();

        // Set TCCL for application code (ServiceLoader etc.) — not needed by logback.
        Thread.currentThread().setContextClassLoader(appCL);

        // Load RuntimeMain — its static Logger field boots logback.
        // No system properties or TCCL tricks needed: LogbackClassLoader intercepts
        // everything logback asks for.
        Class<?> runtimeMainClass = runtimeCL.loadClass("com.example.runtime.RuntimeMain");
        System.out.println("[Boot] RuntimeMain loaded by: " + runtimeMainClass.getClassLoader());
        System.out.println();

        Method run = runtimeMainClass.getMethod("run", ClassLoader.class);
        run.invoke(null, appCL);
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
        System.out.printf("[RES] %-60s → %s%n", resource, url != null ? url : "NOT FOUND");
    }
}
