package com.example.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A replacement for the JVM system classloader, installed via:
 * <pre>
 *   -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader
 * </pre>
 *
 * <h3>Classloader identity — the tricky part</h3>
 *
 * The JVM loads this class using the built-in AppClassLoader <em>before</em>
 * instantiating it as the new system CL.  If we then also scan
 * {@code java.class.path} and add those same URLs to our own list, we create
 * a second copy of every boot class — including our own class — loaded by
 * {@code this}.  The {@code instanceof} check in {@link SystemCLBootMain}
 * then fails because the two {@code Class} objects for
 * {@code AkkaSystemClassLoader} come from different loaders.
 *
 * <p>Correct design:
 * <ul>
 *   <li>Keep the built-in AppClassLoader as our <em>parent</em>.  Boot classes
 *       ({@code boot/target/classes}) live on the {@code -cp} and are therefore
 *       loaded by AppClassLoader.  {@code AkkaSystemClassLoader.class} and
 *       {@code SystemCLBootMain.class} are both in AppClassLoader, so
 *       {@code instanceof} resolves correctly.</li>
 *   <li>Start with <em>zero own URLs</em>.  {@link SystemCLBootMain} calls
 *       {@link #addJar(URL)} at runtime to register the SDK + App jars.
 *       These are NOT in AppClassLoader, so all logback / SDK / App classes
 *       are loaded by <em>us</em>, giving us control over
 *       {@code getClassLoaderOfObject(configurator)}.</li>
 * </ul>
 *
 * <h3>Why this solves all three logback problems</h3>
 *
 * After {@link SystemCLBootMain} calls {@code addJar()} for sdk + logback + app,
 * logback's own classes are loaded by {@code this}:
 * <pre>
 *   Loader.getClassLoaderOfObject(configurator)
 *     = DefaultJoranConfigurator.class.getClassLoader()
 *     = AkkaSystemClassLoader
 * </pre>
 * When logback calls {@code this.getResource("logback.xml")}, or
 * {@code this.loadClass("CorrelationIdLayout")}, we first search our own
 * jars (sdk + app) and then perform a downward search into the Runtime CL
 * (which holds {@code runtime.jar}) — with the standard ThreadLocal anti-loop
 * guard to prevent re-entrant cycles.
 *
 * No agent, no bridge registry, no sidecar.  The system CL itself is the bridge.
 */
public class AkkaSystemClassLoader extends URLClassLoader {

    private static final ThreadLocal<Boolean> IN_DOWNWARD_SEARCH =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final List<ClassLoader> children =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * Called by the JVM during startup.
     *
     * @param parent the built-in AppClassLoader — kept as our parent so that
     *               boot classes (including this class itself) are loaded once,
     *               by AppClassLoader, avoiding the CL identity split.
     */
    public AkkaSystemClassLoader(ClassLoader parent) throws IOException {
        // Start with NO own URLs — they are added dynamically by SystemCLBootMain.
        // Boot classes (boot/target/classes) stay in AppClassLoader via -cp.
        super("akka-system-classloader", new URL[0], parent);
        System.out.println("[AkkaSystemCL] Installed — parent: " + parent.getClass().getSimpleName());
    }

    // ── Dynamic classpath extension ───────────────────────────────────────────

    /**
     * Add a jar to our search path.  Called by {@link SystemCLBootMain} before
     * logback initialises, to register the SDK + App jars so that their classes
     * (including logback's own) are loaded by {@code this} rather than delegated
     * to the parent AppClassLoader (which doesn't have them).
     */
    public void addJar(URL url) {
        addURL(url);
    }

    // ── Child registration ────────────────────────────────────────────────────

    public void addChild(ClassLoader child) {
        System.out.println("[AkkaSystemCL] Child registered: " + child);
        children.add(child);
    }

    // ── Class loading ─────────────────────────────────────────────────────────

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }

            // 1. Parent (AppClassLoader) — finds boot classes, java.*, platform
            try { c = getParent().loadClass(name); }
            catch (ClassNotFoundException ignored) {}

            // 2. Own jars (sdk + logback + app, added via addJar)
            if (c == null) {
                try { c = findClass(name); }
                catch (ClassNotFoundException ignored) {}
            }

            // 3. Downward into Runtime CL — guarded against re-entry
            if (c == null && !IN_DOWNWARD_SEARCH.get()) {
                IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
                try {
                    for (ClassLoader child : children) {
                        try { c = child.loadClass(name); break; }
                        catch (ClassNotFoundException ignored) {}
                    }
                } finally {
                    IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
                }
            }

            if (c == null) throw new ClassNotFoundException(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    // ── Resource loading ──────────────────────────────────────────────────────

    @Override
    public URL getResource(String name) {
        URL url = super.getResource(name);  // parent + own jars
        if (url == null && !IN_DOWNWARD_SEARCH.get()) {
            IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
            try {
                for (ClassLoader child : children) {
                    url = child.getResource(name);
                    if (url != null) break;
                }
            } finally {
                IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
            }
        }
        return url;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> own = super.getResources(name);
        if (IN_DOWNWARD_SEARCH.get()) return own;

        List<URL> all = new ArrayList<>();
        while (own.hasMoreElements()) all.add(own.nextElement());

        IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
        try {
            for (ClassLoader child : children) {
                Enumeration<URL> more = child.getResources(name);
                while (more.hasMoreElements()) {
                    URL u = more.nextElement();
                    if (!all.contains(u)) all.add(u);
                }
            }
        } finally {
            IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
        }
        return Collections.enumeration(all);
    }
}
