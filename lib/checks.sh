#!/usr/bin/env bash
# Shared integration-test checks for all logback-classloader run scripts.
# Source this file, then call:  check_output "$OUTPUT"

check_output() {
    local output="$1"
    local failed=0

    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  Integration checks"
    echo "════════════════════════════════════════════════════════════"

    # ── Check 1: CorrelationIdLayout format ──────────────────────────────
    # The custom layout produces lines starting with [HH:mm:ss.SSS].
    # If logback falls back to BasicConfigurator (TTLLLayout) the lines
    # instead start with a bare timestamp: HH:mm:ss.SSS [thread] LEVEL ...
    # Root cause when failing: Loader.loadClass("CorrelationIdLayout", ctx)
    #   calls ctx.getClass().getClassLoader() = SDK/system CL, which cannot
    #   see runtime.jar where CorrelationIdLayout lives.  The Layout fails to
    #   instantiate, the ConsoleAppender has no encoder, and logback silently
    #   falls back to its default TTLLLayout output.
    if echo "$output" | grep -qE '^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}\]'; then
        echo "✓  CorrelationIdLayout format applied"
        echo "   (log lines start with [HH:mm:ss.SSS] as expected)"
    else
        echo "✗  FAIL — CorrelationIdLayout format not found"
        echo "   Expected: [HH:mm:ss.SSS] [-] [LEVEL] [logger] message"
        echo "   Got     : $(echo "$output" | grep -E '^[0-9]{2}:[0-9]{2}' | head -1 || echo '(no log lines found)')"
        echo "   Cause   : Loader.loadClass uses SDK/system CL for class"
        echo "             instantiation; runtime.jar is invisible from there."
        echo "             ConsoleAppender starts without an encoder → fallback."
        failed=$((failed + 1))
    fi

    # ── Check 2: logback-app.xml <include> was resolved ──────────────────
    # MyServiceEndpoint logs a DEBUG line only when com.example.app is set
    # to DEBUG, which only happens if logback-app.xml was successfully
    # included.  The message text is the canary.
    # Root cause when failing: Joran resolves <include resource="..."> via
    #   Loader.getResource(name, myClassLoader) where myClassLoader =
    #   SDK/system CL.  logback-app.xml lives in app.jar, invisible from
    #   SDK CL.  optional="true" suppresses any error.
    if echo "$output" | grep -q "logback-app.xml include worked"; then
        echo "✓  <include resource=\"logback-app.xml\"> resolved"
        echo "   (DEBUG line from MyServiceEndpoint visible)"
    else
        echo "✗  FAIL — logback-app.xml not included"
        echo "   Expected: DEBUG line containing 'logback-app.xml include worked'"
        echo "   Cause   : Joran's <include resource> uses SDK/system CL to"
        echo "             locate resources; logback-app.xml in app.jar is"
        echo "             invisible.  optional=\"true\" hides the failure."
        failed=$((failed + 1))
    fi

    # ── Check 3: No logback configuration errors ─────────────────────────
    # With debug="true" in logback.xml, failed class instantiations appear
    # as |-ERROR lines in the status output.  A clean configuration has none.
    local errors
    errors=$(echo "$output" | grep "|-ERROR" || true)
    if [ -z "$errors" ]; then
        echo "✓  No logback configuration errors (no |-ERROR in status output)"
    else
        echo "✗  FAIL — logback status contains ERROR entries:"
        echo "$errors" | sed 's/^/      /'
        echo "   Cause   : Classes referenced in logback.xml (Layout, Filter)"
        echo "             could not be loaded from the CL logback used."
        failed=$((failed + 1))
    fi

    echo "════════════════════════════════════════════════════════════"
    if [ $failed -eq 0 ]; then
        echo "  All checks passed ✓"
        echo "════════════════════════════════════════════════════════════"
        return 0
    else
        echo "  $failed check(s) FAILED — see diagnostics above ✗"
        echo "════════════════════════════════════════════════════════════"
        return 1
    fi
}

# Make check_output available to scripts that source this file.
export -f check_output 2>/dev/null || true
