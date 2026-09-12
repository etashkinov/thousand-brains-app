package com.eta.tbp.lib.log

/**
 * A minimal, dependency-free logging seam for `lib`: this module stays a
 * plain JVM library, testable without Android (the module boundary this
 * project's docs call out), so it can't depend on `android.util.Log`
 * directly. Real Monty's own instrumentation plays the same role —
 * `MontyExperiment`'s `monty_logger`/`LoggingCallbackHandler` is a
 * pluggable sink every component writes structured events through, not a
 * hardcoded backend — so the `app` module can supply its own [Logger] (e.g.
 * forwarding straight to `android.util.Log`) without `lib` ever knowing
 * Android exists.
 *
 * [tag] is passed per call rather than bound to one [Logger] instance,
 * deliberately mirroring `android.util.Log.d(tag, message)`'s own shape —
 * an Android-backed implementation can forward a call here with no
 * adapting — and it's what lets [CityExperiment][com.eta.tbp.lib.city.CityExperiment]
 * share a single [Logger] across every layer it wires up
 * ([com.eta.tbp.lib.city.CitySensorModule], [com.eta.tbp.lib.lm.EvidenceGraphLM],
 * [com.eta.tbp.lib.lm.Explorer]) while each still logs under its own name.
 * [message] is a lambda so a discarded level (see [None]) never even builds
 * the string.
 */
interface Logger {
    fun debug(
        tag: String,
        message: () -> String,
    )

    fun info(
        tag: String,
        message: () -> String,
    )

    fun warn(
        tag: String,
        message: () -> String,
    )

    /** Prints to stdout — useful for a plain JVM run (a script, a test explicitly opting in) with no real backend wired up. */
    object Console : Logger {
        override fun debug(
            tag: String,
            message: () -> String,
        ) = println("D/$tag: ${message()}")

        override fun info(
            tag: String,
            message: () -> String,
        ) = println("I/$tag: ${message()}")

        override fun warn(
            tag: String,
            message: () -> String,
        ) = println("W/$tag: ${message()}")
    }
}
