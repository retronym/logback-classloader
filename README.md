# logback-classloader

A minimal Maven sandbox for exploring how to configure **logback in a
multi-classloader environment**, modelled after the classloader isolation used
in Akka Runtime / Akka SDK.

---

## Project structure

```
sdk/        ServiceEndpoint API + logback-classic + slf4j-api
runtime/    RuntimeMain, custom Layout/Filter, master logback.xml
            (includes logback-app.xml from the app layer)
app/        MyServiceEndpoint impl, META-INF/services, logback-app.xml
boot/       Reads generated classpath files, builds layered CLs, launches RuntimeMain
agent/      Java agent that rewrites Loader.getClassLoaderOfObject() (Solution 2 only)
```

The classloader hierarchy mirrors production isolation:

```
PlatformCL
  └── SDK CL        sdk.jar + logback-classic + slf4j-api
       └── Runtime CL   runtime.jar
            └── App CL      app.jar
```

Maven's `package` phase generates three classpath files in `boot/target/`:

| File | Contents |
|---|---|
| `sdk-cp.txt` | `sdk.jar` + logback/slf4j transitives |
| `runtime-cp.txt` | `runtime.jar` only |
| `app-cp.txt` | `app.jar` only |

---

## The problem

Three things in **logback 1.5.x / SLF4J 2.x** break when logback's own
classes are in a _parent_ CL but the configuration artefacts live in a
_child_ CL.

### Problem 1 — `logback.xml` is not found

`DefaultJoranConfigurator.performMultiStepConfigurationFileSearch` calls:

```java
ClassLoader myClassLoader = Loader.getClassLoaderOfObject(this);
// → DefaultJoranConfigurator.class.getClassLoader()  =  SDK CL
```

`logback.xml` lives in `runtime.jar` (Runtime CL).  SDK CL cannot see it.
`DefaultJoranConfigurator` returns `INVOKE_NEXT_IF_ANY`; `BasicConfigurator`
runs instead (default `DEBUG` root level, `TTLLLayout` format).

> **Key finding** — there is **no TCCL fallback** in this path.
> `Loader.getResource(name, cl)` is a two-liner: `return cl.getResource(name)`.
> Contrary to what older logback versions did, logback 1.5.x removed the
> fallback entirely in `Loader.getClassLoaderOfObject`.

### Problem 2 — Layout / Filter classes are not found

`Loader.loadClass(className, context)` in logback 1.5.6:

```java
public static Class<?> loadClass(String clazz, Context context)
        throws ClassNotFoundException {
    ClassLoader cl = getClassLoaderOfObject(context); // → SDK CL
    return cl.loadClass(clazz);                       // no fallback
}
```

`com.example.runtime.layout.CorrelationIdLayout` is in `runtime.jar`.
SDK CL cannot see it → `ClassNotFoundException`, appender silently broken.

### Problem 3 — `<include resource="logback-app.xml">` is not resolved

Joran resolves included resources via `Loader.getResource(name, myClassLoader)`
where `myClassLoader` comes from the same `getClassLoaderOfObject` call.
`logback-app.xml` lives in `app.jar` (App CL).  Not found from SDK CL.
`optional="true"` suppresses any error — it just silently does nothing.

### Observability

All three problems are visible by running the scaffold on `main`:

```bash
mvn install && mvn exec:exec -pl boot
```

| Symptom | Root cause |
|---|---|
| Log format is `[thread] LEVEL logger -- msg` (TTLLLayout) | Problem 1: BasicConfigurator used |
| No `[HH:mm:ss.SSS] [-] [LEVEL]` CorrelationIdLayout format | Problem 2: Layout class not loaded |
| No `DEBUG` line from `MyServiceEndpoint` | Problem 3: `logback-app.xml` not included |

The `boot/` diagnostic output confirms all resources _are_ reachable from
`appCL.getResource(...)` — the issue is purely which classloader logback picks.

---

## Solution 1 — `LogbackClassLoader` (Layer 0 parent CL)

**Branch: [`solution/logback-classloader`](../../tree/solution/logback-classloader)**

> Works when you control the CL hierarchy (non-embedded / production mode).

Insert a custom `LogbackClassLoader` between `PlatformCL` and `SDK CL`.
It holds only `logback-classic + logback-core + slf4j-api`.  Because all
logback and Joran classes are loaded by it, every CL lookup logback makes
(`getClassLoaderOfObject(this)`) returns this CL.  When its own jars cannot
satisfy the request it **reaches down** into registered child CLs.

```
PlatformCL
  └── LogbackClassLoader   logback-classic + logback-core + slf4j-api
       │  ↕  downward child search
       └── SDK CL           sdk.jar
            └── Runtime CL  runtime.jar  ← Layout, Filter, logback.xml
                 └── App CL  app.jar     ← logback-app.xml
```

**Anti-loop mechanism** — A `ThreadLocal<Boolean> IN_DOWNWARD_SEARCH` is set
to `true` for the duration of the child search.  On re-entry (when a child CL
delegates back up through its normal parent chain and reaches
`LogbackClassLoader` again) the flag prevents a second downward search,
breaking the cycle.  The child CL's own `findClass` / `findResource` then
resolves the answer from its own jars.

```java
// boot/BootMain.java (simplified)
LogbackClassLoader logbackCL = new LogbackClassLoader(logbackJars, PlatformCL);
URLClassLoader     sdkCL     = new URLClassLoader(sdkJars,     logbackCL);
URLClassLoader     runtimeCL = new URLClassLoader(runtimeJars, sdkCL);
URLClassLoader     appCL     = new URLClassLoader(appJars,     runtimeCL);

logbackCL.addChild(appCL); // covers full hierarchy via parent delegation
Thread.currentThread().setContextClassLoader(appCL);
// Load RuntimeMain — logback inits, finds everything through logbackCL ✓
```

Run:

```bash
git checkout solution/logback-classloader
mvn install && mvn exec:exec -pl boot
```

Expected output: `[HH:mm:ss.SSS] [-] [INFO ] [logger] message` (CorrelationIdLayout)
and a `[DEBUG]` line confirming `logback-app.xml` was included.

---

## Solution 2 — Java agent rewrites `Loader.getClassLoaderOfObject`

**Branch: [`solution/agent-rewrite`](../../tree/solution/agent-rewrite)**

> Works in **embedded (dev-mode)** too, where SDK + App are on the JVM
> system classloader and the hierarchy cannot be customised.

A Java agent (`agent/`) intercepts `ch.qos.logback.core.util.Loader` at class
load time and **prepends five bytecode instructions** to
`getClassLoaderOfObject(Object)`:

```
// Injected prefix (ASM PrependBridgeCheck visitor):
INVOKESTATIC  com/example/agent/LogbackBridge.get  ()Ljava/lang/ClassLoader;
DUP
IFNULL        L_null
ARETURN                   ← return bridge if registered
L_null: POP               ← fall through to original body
```

The original method body is unchanged and follows immediately — no original
instructions are removed.

`LogbackBridge` is a one-field static registry (`volatile ClassLoader`).  It
is added to the **bootstrap CL** by the agent so it is visible from any CL
that loads the (now-instrumented) `Loader`.

The registered value is a `BridgeClassLoader` — a sidecar CL that is **not**
in the parent-delegation hierarchy but has the same downward child-search
logic as `LogbackClassLoader`:

```
PlatformCL
  └── SharedCL (≈ system CL)   logback + SDK + App
       └── RuntimeCL            runtime.jar

BridgeClassLoader (sidecar — returned by patched getClassLoaderOfObject)
  parent  = SharedCL
  child   = RuntimeCL
```

**Init-order safety** — `LogbackBridge.get()` returns `null` until
`register()` is called.  The instrumented method degrades to the original
behaviour when `null`, so a brief window before `register()` is safe.
In practice `register()` is called before any class with a `static Logger`
field is loaded.

```java
// boot/EmbeddedBootMain.java (simplified)
BridgeClassLoader bridge = new BridgeClassLoader(new URL[0], sharedCL);
bridge.addChild(runtimeCL);
LogbackBridge.register(bridge);           // ← before logback init
// Load RuntimeMain — getClassLoaderOfObject() returns bridge ✓
```

Run:

```bash
git checkout solution/agent-rewrite
mvn install && mvn exec:exec@embedded -pl boot
# or for true system-CL embedded simulation:
./run-embedded.sh
```

---

## Comparison

| | Solution 1 | Solution 2 |
|---|---|---|
| **Mechanism** | Custom parent CL (Layer 0) | Java agent + bytecode rewrite |
| **Embedded mode** | ✗ cannot wrap system CL | ✓ works regardless of CL topology |
| **Complexity** | Low — pure Java, no bytecode | Medium — ASM transformer + agent packaging |
| **Logback version sensitivity** | Robust — intercepts at CL level | Tied to `Loader.getClassLoaderOfObject` signature |
| **Runtime overhead** | Zero after startup | Zero after class load |
| **Key class** | `LogbackClassLoader` | `LoaderTransformer` + `BridgeClassLoader` |

Both solutions share the same **anti-loop pattern**: a `ThreadLocal<Boolean>`
flag prevents the downward child search from re-entering itself when a child
CL's upward parent delegation circles back through the bridge.

---

## Running

```bash
# Prerequisites: Java 17+, Maven 3.8+

# See the problem (main branch):
mvn install && mvn exec:exec -pl boot

# Solution 1:
git checkout solution/logback-classloader
mvn install && mvn exec:exec -pl boot

# Solution 2 (embedded mode):
git checkout solution/agent-rewrite
mvn install && mvn exec:exec@embedded -pl boot
```

### Toggling logback's own diagnostic output

Set `debug="true"` in [`runtime/src/main/resources/logback.xml`](runtime/src/main/resources/logback.xml)
to see the full Joran status trace, which shows exactly which resources were
found or missed and which classes failed to instantiate.
