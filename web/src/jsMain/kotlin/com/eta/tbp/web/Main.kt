package com.eta.tbp.web

import com.eta.tbp.web.state.AppState
import com.eta.tbp.web.state.MontyState
import com.eta.tbp.web.ui.VisitedCellUi
import com.eta.tbp.web.ui.renderEditor
import com.eta.tbp.web.ui.renderMontyPanel
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
    val montyPanel = document.createElement("aside") as HTMLElement
    montyPanel.className = "monty-panel"
    layout.appendChild(sidebar)
    layout.appendChild(editorPanel)
    layout.appendChild(montyPanel)
    app.appendChild(layout)

    lateinit var appState: AppState
    lateinit var montyState: MontyState

    // The most recent experiment's visited cells, their step number, and the hypothesis
    // state each left the LM in, scoped to the map it ran on — see MontyState.lastResultMapId's
    // doc for why a different open draft must not show them.
    fun visitedCellsForOpenDraft(): Map<Pair<Int, Int>, VisitedCellUi> {
        val draft = appState.draft ?: return emptyMap()
        if (montyState.lastResultMapId != draft.id) return emptyMap()
        return montyState.lastResult
            ?.visitedCells
            .orEmpty()
            .associate { (it.row to it.col) to VisitedCellUi(it.step, it.state) }
    }

    fun renderEditorAndMonty() {
        renderEditor(editorPanel, appState, visitedCellsForOpenDraft())
        renderMontyPanel(montyPanel, montyState, appState)
    }

    fun renderAll() {
        renderSidebar(sidebar, appState)
        renderEditorAndMonty()
    }

    appState = AppState(onChange = ::renderAll)
    montyState = MontyState(onChange = ::renderEditorAndMonty)
    renderAll()
    montyState.refreshMemory()
}
