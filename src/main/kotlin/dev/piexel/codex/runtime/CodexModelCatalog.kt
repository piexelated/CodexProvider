package dev.piexel.codex.runtime

import dev.piexel.codex.protocol.CodexModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Account status changes are not model-setting changes. Publish only a new catalog. */
internal class CodexModelCatalog {
    private val current = MutableStateFlow<List<CodexModel>?>(null)
    val changes = current.asStateFlow()
    private var refreshJob: Job? = null

    /** JetBrains gives discovery 2.5s. Never tie native startup to that deadline. */
    @Synchronized
    fun getOrRefresh(scope: CoroutineScope, refresh: suspend () -> Unit): List<CodexModel> {
        current.value?.let { return it }
        if (refreshJob?.isActive != true) {
            refreshJob = scope.launch(start = CoroutineStart.LAZY) { refresh() }
            refreshJob!!.start()
        }
        return emptyList()
    }

    @Synchronized
    fun replace(models: List<CodexModel>): Boolean {
        val next = models.distinctBy { it.id }.sortedBy { it.id }
        if (current.value == next) return false
        current.value = next
        return true
    }
}
