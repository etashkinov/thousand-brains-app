package com.eta.tbp.lib.log

/** Test double that records every call instead of printing, so a test can assert on tags/messages without capturing stdout. */
class CollectingLogger : Logger {
    private val mutableEntries = mutableListOf<Entry>()
    val entries: List<Entry> get() = mutableEntries

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
    )

    override fun debug(
        tag: String,
        message: () -> String,
    ) {
        mutableEntries += Entry("D", tag, message())
    }

    override fun info(
        tag: String,
        message: () -> String,
    ) {
        mutableEntries += Entry("I", tag, message())
    }

    override fun warn(
        tag: String,
        message: () -> String,
    ) {
        mutableEntries += Entry("W", tag, message())
    }
}
