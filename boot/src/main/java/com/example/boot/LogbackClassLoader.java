package com.example.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A URLClassLoader that holds only logback + slf4j, positioned as the parent
 * of the SDK ClassLoader.  Its defining capability: when a class or resource
 * is not found through normal upward delegation it reaches <em>down</em> into
 * a registered set of child classloaders.
 *
 * <p>Why this solves all three logback challenges at once:
 *
 * <pre>
 *   PlatformCL
 *     └── LogbackClassLoader  (logback-classic, logback-core, slf4j-api)
 *          └── SDK CL          (sdk.jar)
 *               └── Runtime CL  (runtime.jar  — Layout, Filter, logback.xml)
 *                    └── App CL  (app.jar       — logback-app.xml, ServiceEndpoint impl)
 * </pre>
 *
 * <ul>
 *   <li>logback.xml discovery — {@code DefaultJoranConfigurator} is loaded by
 *       this CL, so {@code Loader.getClassLoaderOfObject(configurator)} ==
 *       {@code this}.  {@code this.getResource("logback.xml")} reaches down
 *       to Runtime CL via the child search ✓</li>
 *   <li>Layout/Filter instantiation — {@code Loader.loadClass(name, context)}
 *       calls {@code context.getClass().getClassLoader().loadClass(name)} ==
 *       {@code this.loadClass(name)}.  Reaches down to Runtime CL ✓</li>
 *   <li>{@code <include resource="logback-app.xml">} — same getResource path,
 *       reaches down to App CL ✓</li>
 * </ul>
 *
 * <h3>Anti-loop mechanism</h3>
 * Without a guard, reaching down to a child CL triggers the child's normal
 * upward delegation, which eventually calls back into this CL, which would
 * reach down again — infinite loop.
 *
 * <p>Guard: a {@link ThreadLocal} boolean {@code IN_DOWNWARD_SEARCH} is set to
 * {@code true} for the duration of the child search.  On re-entry this CL
 * behaves like a plain URLClassLoader (own jars + parent only), breaking the
 * cycle.  The child CL then resolves the class/resource from its own jars or
 * closer ancestors without ever completing the loop.
 *
 * <p>Children are registered <em>after</em> construction (they cannot exist
 * before their own parents do).  Thread-safety: the list is synchronised;
 * the ThreadLocal is per-thread.
 */
public class LogbackClassLoader extends URLClassLoader {

    private static final ThreadLocal<Boolean> IN_DOWNWARD_SEARCH =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final List<ClassLoader> children =
            Collections.synchronizedList(new ArrayList<>());

    public LogbackClassLoader(URL[] urls, ClassLoader parent) {
        super("logback-classloader", urls, parent);
    }

    /** Register a child CL to search when upward delegation fails. */
    public void addChild(ClassLoader child) {
        children.add(child);
    }

    // ── Class loading ────────────────────────────────────────────────────────

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }

            // 1. Normal upward delegation (PlatformCL)
            try { c = getParent().loadClass(name); }
            catch (ClassNotFoundException ignored) {}

            // 2. Own jars (logback-classic, logback-core, slf4j-api)
            if (c == null) {
                try { c = findClass(name); }
                catch (ClassNotFoundException ignored) {}
            }

            // 3. Downward search — guarded against re-entry
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

    // ── Resource loading ─────────────────────────────────────────────────────

    @Override
    public URL getResource(String name) {
        // 1. Platform CL + own jars (standard URLClassLoader behaviour)
        URL url = super.getResource(name);

        // 2. Downward search — guarded against re-entry
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
        // For logback's multi-resource scanning (e.g. service discovery)
        Enumeration<URL> own = super.getResources(name);
        if (IN_DOWNWARD_SEARCH.get()) return own;

        List<URL> all = new ArrayList<>();
        while (own.hasMoreElements()) all.add(own.nextElement());

        IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
        try {
            for (ClassLoader child : children) {
                Enumeration<URL> childUrls = child.getResources(name);
                while (childUrls.hasMoreElements()) {
                    URL u = childUrls.nextElement();
                    if (!all.contains(u)) all.add(u);
                }
            }
        } finally {
            IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
        }
        return Collections.enumeration(all);
    }
}
