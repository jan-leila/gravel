package com.anopticlabs.gravel.gitsource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The add dialog's steps: the address and settings, then (with several PBWs) which one, then what the PBW declares. */
sealed interface AddStep {
    val form: AddForm

    data class Enter(override val form: AddForm, val error: String? = null, val loading: Boolean = false) : AddStep
    data class ChooseAsset(override val form: AddForm, val preview: AddPreview.SeveralAssets, val loading: Boolean = false) : AddStep
    data class Confirm(override val form: AddForm, val prepared: PreparedInstall, val installing: Boolean = false) : AddStep
}

data class AddForm(val url: String = "", val includePrereleases: Boolean = false, val autoUpdate: Boolean = false)

class GitSourcesViewModel(private val updater: GitSourceUpdater) : ViewModel() {
    val sources: StateFlow<List<GitSourceView>?> = updater.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _addStep = MutableStateFlow<AddStep?>(null)
    val addStep: StateFlow<AddStep?> = _addStep.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun refresh() = updater.refresh()

    fun startAdd() {
        _addStep.value = AddStep.Enter(AddForm())
    }

    fun editForm(form: AddForm) {
        _addStep.update { (it as? AddStep.Enter)?.copy(form = form, error = null) ?: it }
    }

    fun cancelAdd() {
        (_addStep.value as? AddStep.Confirm)?.let { updater.discard(it.prepared) }
        _addStep.value = null
    }

    fun submitUrl() {
        val step = _addStep.value as? AddStep.Enter ?: return
        _addStep.value = step.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val preview = updater.preview(step.form.url, step.form.includePrereleases, assetPattern = null)) {
                AddPreview.InvalidUrl -> _addStep.value = step.copy(error = INVALID_URL_MESSAGE)
                is AddPreview.Failed -> _addStep.value = step.copy(error = preview.error.message())
                is AddPreview.SeveralAssets -> _addStep.value = AddStep.ChooseAsset(step.form, preview)
                is AddPreview.Ready -> prepare(step.form, preview, back = step)
            }
        }
    }

    fun chooseAsset(name: String) {
        val step = _addStep.value as? AddStep.ChooseAsset ?: return
        val asset = step.preview.release.assets.firstOrNull { it.name == name } ?: return
        _addStep.value = step.copy(loading = true)
        viewModelScope.launch {
            val ready = AddPreview.Ready(step.preview.repo, step.preview.release, asset, assetPatternFor(name))
            prepare(step.form, ready, back = AddStep.Enter(step.form))
        }
    }

    private suspend fun prepare(form: AddForm, ready: AddPreview.Ready, back: AddStep.Enter) {
        _addStep.value = when (val prepared = updater.prepare(ready)) {
            is PrepareResult.Ready -> AddStep.Confirm(form, prepared.prepared)
            is PrepareResult.Failed -> back.copy(error = prepared.error.message(), loading = false)
        }
    }

    fun confirmAdd() {
        val step = _addStep.value as? AddStep.Confirm ?: return
        _addStep.value = step.copy(installing = true)
        viewModelScope.launch {
            val result = updater.completeAdd(step.prepared, step.form.includePrereleases, step.form.autoUpdate)
            _addStep.value = null
            _messages.emit(
                when (result) {
                    InstallResult.Installed -> "Installed ${step.prepared.facts.title}"
                    is InstallResult.Failed -> result.error.message()
                    is InstallResult.NeedsApproval -> "This release asks for more access: ${result.newCapabilities.joinToString { capabilityLabel(it) }}"
                },
            )
        }
    }

    fun check(view: GitSourceView) = runFor(view) { updater.check(it)?.message() }

    fun checkAll() {
        sources.value.orEmpty().forEach { check(it) }
    }

    /** Installs the available release; [approved] are the new capabilities the user just accepted, if any. */
    fun install(view: GitSourceView, approved: Set<String> = emptySet()) = runFor(view) {
        when (val result = updater.install(it, approved)) {
            InstallResult.Installed -> "Installed ${view.source.appTitle} ${view.source.availableTag}"
            is InstallResult.NeedsApproval -> "${view.source.appTitle} ${view.source.availableTag} asks for more access; review it first"
            is InstallResult.Failed -> result.error.message()
        }
    }

    fun setOptions(view: GitSourceView, includePrereleases: Boolean, autoUpdate: Boolean, assetPattern: String?) = runFor(view) {
        updater.setOptions(it, includePrereleases, autoUpdate, assetPattern)
        updater.check(it)?.message()
    }

    fun forget(view: GitSourceView) = runFor(view) {
        updater.forget(it)
        "Stopped following ${view.repo.displayName}"
    }

    /** Runs [action] for one source, one action per source at a time, and shows its message. */
    private fun runFor(view: GitSourceView, action: suspend (Uuid) -> String?) {
        val key = view.source.uuid
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                action(Uuid.parse(key))?.let { _messages.emit(it) }
            } finally {
                _busy.update { it - key }
            }
        }
    }

    override fun onCleared() {
        (_addStep.value as? AddStep.Confirm)?.let { updater.discard(it.prepared) }
    }
}
