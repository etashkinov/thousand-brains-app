package com.eta.tbp.web

import com.eta.tbp.web.state.AppState
import com.eta.tbp.web.ui.renderEditor
import com.eta.tbp.web.ui.renderSidebar
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

fun main() {
    val app = document.getElementById("app") as? HTMLElement ?: return
    app.innerHTML = ""

    val layout = document.createElement("div") as HTMLElement
    layout.className = "layout"
    val sidebar = document.createElement("aside") as HTMLElement
    sidebar.className = "sidebar"
    val editorPanel = document.createElement("main") as HTMLElement
    editorPanel.className = "editor-panel"
    layout.appendChild(sidebar)
    layout.appendChild(editorPanel)
    app.appendChild(layout)

    lateinit var state: AppState

    fun render() {
        renderSidebar(sidebar, state)
        renderEditor(editorPanel, state)
    }
    state = AppState(onChange = ::render)
    render()
}
