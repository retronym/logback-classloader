package com.example.boot;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Demonstrates the self-attach agent solution: BootMain dynamically loads
 * the logback-rewriting agent at runtime via the Attach API, without
 * requiring {@code -javaagent} on the command line.
 *
 * <p>This is useful for tooling and dev-mode reload scenarios where the
 * agent jar may not be available at JVM startup, or where you want to
 * inject instrumentation into an already-running process.
 *
 * <h3>How it works</h3>
 *
 * <ol>
 *   <li>JVM starts without any agent.</li>
 *   <li>{@code main()} obtains the current process ID via
 *       {@code ManagementFactory.getRuntimeMXBean().getName()}</li>
 *   <li>Attaches to self: {@code VirtualMachine.attach(pid)}</li>
 *   <li>Loads the agent jar: {@code vm.loadAgent(agentJarPath)}</li>
 *   <li>By the time {@code vm.loadAgent()} returns, the agent has:
 *       <ul>
 *         <li>Appended its jar to the bootstrap CL
 *             (making {@code LogbackBridge} visible everywhere)</li>
 *         <li>Registered {@code LoaderTransformer}</li>
 *         <li>Possibly retransformed {@code Loader} if already loaded</li>
 *       </ul></li>
 *   <li>We construct the CL hierarchy and register the
 *       {@code BridgeClassLoader} with {@code LogbackBridge}</li>
 *   <li>Load {@code RuntimeMain} — logback inits with the bridge CL
 *       available</li>
 * </ol>
 *
 * <h3>Init-order safety</h3>
 *
 * This approach is safe even if some logback classes were already loaded
 * before the agent is attached:
 *
 * <ul>
 *   <li>If {@code Loader} hasn't been loaded yet: the transformer fires on
 *       first use (normal), and the bytecode rewrite is applied before any
 *       method is called.</li>
 *   <li>If {@code Loader} was already loaded (e.g., by a utility that
 *       introspects logback): the agent's
 *       {@code Can-Retransform-Classes: true} in the MANIFEST allows it to
 *       retransform the already-loaded class, applying the bytecode patch
 *       retroactively.</li>
 * </ul>
 *
 * In either case, {@code LogbackBridge.get()} returns {@code null} until we
 * call {@code register(bridge)}, so any early use of {@code Loader} still
 * works (it just degrades to the original no-fallback behavior).
 *
 * <h3>Trade-offs vs. other approaches</h3>
 *
 * <ul>
 *   <li>Pro: No {@code -javaagent} command-line flag needed</li>
 *   <li>Pro: Works in hot-attach / already-running scenarios</li>
 *   <li>Con: Depends on {@code tools.jar} (Sun APIs)</li>
 *   <li>Con: Slightly more complex than upfront {@code -javaagent}</li>
 *   <li>Con: Tooling / IDE support less mature than command-line flags</li>
 * </ul>
 */
public class SelfAttachBootMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SelfAttachBootMain <sdk-cp.txt> <runtime-cp.txt> <app-cp.txt> [agent-jar-path]");
            System.exit(1);
        }

        // ── Load the agent dynamically via self-attach ──────────────────────
        String agentJarPath = args.length > 3 ? args[3] : findAgentJar();
        if (agentJarPath == null) {
            System.err.println("ERROR: agent JAR not found — expected at agent/target/agent-*.jar");
            System.exit(1);
        }
        attachAgent(agentJarPath);
        System.out.println("[SelfAttach] Agent loaded successfully");

        // ── Now build the rest of the hierarchy (same as EmbeddedBootMain) ──
        URL[] sdkUrls     = readClasspathFile(args[0]);
        URL[] runtimeUrls = readClasspathFile(args[1]);
        URL[] appUrls     = readClasspathFile(args[2]);

        // Simulate embedded: shared CL holds SDK+App, Runtime is isolated
        java.util.List<URL> sharedUrls = new java.util.ArrayList<>();
        for (URL u : sdkUrls) sharedUrls.add(u);
        for (URL u : appUrls) sharedUrls.add(u);

        URLClassLoader sharedCL = new URLClassLoader(
                "shared-classloader",
                sharedUrls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        URLClassLoader runtimeCL = new URLClassLoader(
                "runtime-classloader", runtimeUrls, sharedCL);

        System.out.println("=== Self-attach embedded-mode ClassLoader Setup ===");
        System.out.printf("[CL] shared   = %s%n", sharedCL);
        System.out.printf("[CL] runtime  = %s%n", runtimeCL);

        // Register the bridge — same as EmbeddedBootMain
        BridgeClassLoader bridge = new BridgeClassLoader("self-attach-bridge", new URL[0], sharedCL);
        bridge.addChild(runtimeCL);
        com.example.agent.LogbackBridge.register(bridge);
        System.out.printf("[Bridge] Registered: %s%n", bridge);
        System.out.println();

        // Load RuntimeMain and run
        Thread.currentThread().setContextClassLoader(runtimeCL);
        Class<?> runtimeMainClass = runtimeCL.loadClass("com.example.runtime.RuntimeMain");
        System.out.println("[Boot] RuntimeMain loaded by: " + runtimeMainClass.getClassLoader());
        System.out.println();

        Method run = runtimeMainClass.getMethod("run", ClassLoader.class);
        run.invoke(null, runtimeCL);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Attaches to the current JVM and loads the agent jar.
     * This is a blocking call — on return the agent has been initialized.
     */
    private static void attachAgent(String agentJarPath) throws Exception {
        System.out.println("[SelfAttach] Attaching to self and loading agent...");

        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agentJarPath);
        } finally {
            vm.detach();
        }
    }

    /**
     * Locates the agent JAR by scanning the expected build output path.
     * If not found, returns null.
     */
    private static String findAgentJar() {
        String buildDir = "agent/target";
        File dir = new File(buildDir);
        if (!dir.isDirectory()) return null;

        File[] jars = dir.listFiles((d, name) -> name.matches("agent-.*\\.jar$"));
        return jars != null && jars.length > 0 ? jars[0].getAbsolutePath() : null;
    }

    private static URL[] readClasspathFile(String filePath) throws Exception {
        String cp = Files.readString(Path.of(filePath)).trim();
        if (cp.isEmpty()) return new URL[0];
        String[] entries = cp.split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) urls[i] = toUrl(entries[i].trim());
        return urls;
    }

    private static URL toUrl(String path) throws Exception {
        return new File(path).toURI().toURL();
    }
}
