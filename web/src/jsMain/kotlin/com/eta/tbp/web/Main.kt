package com.eta.tbp.web

import kotlinx.browser.document

fun main() {
    val app = document.getElementById("app") ?: return
    app.textContent = "City Experiment web UI — scaffold only, no logic yet."
}
