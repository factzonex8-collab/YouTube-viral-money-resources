package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgentConfigureRequest
import com.example.data.AgentStateResponse
import com.example.data.ClipForgeApi
import com.example.data.ClipForgeRepository
import com.example.data.HealthResponse
import com.example.data.JobStatusResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CaptionStyleOption(val apiValue: String, val label: String, val description: String) {
    HORMOZI("hormozi", "Hormozi Pop", "Bold gold highlight + thick outline"),
    NEON("neon", "Neon Emerald", "Crisp uppercase + electric green"),
    MINIMAL("minimal", "Minimal Box", "Clean editorial dark pill")
}

enum class WhisperModelOption(val apiValue: String, val label: String) {
    TINY("tiny", "Tiny (Fastest)"),
    BASE("base", "Base (Balanced)"),
    SMALL("small", "Small (Accurate)")
}

sealed interface BackendConnectionState {
    data object Checking : BackendConnectionState
    data class Connected(val health: HealthResponse) : BackendConnectionState
    data class Unreachable(val message: String) : BackendConnectionState
}

data class ClipForgeUiState(
    val serverUrl: String = ClipForgeRepository.DEFAULT_SERVER_URL,
    val serverUrlDraft: String = ClipForgeRepository.DEFAULT_SERVER_URL,
    val connectionState: BackendConnectionState = BackendConnectionState.Checking,
    val youtubeUrl: String = "",
    val captionStyle: CaptionStyleOption = CaptionStyleOption.HORMOZI,
    val whisperModel: WhisperModelOption = WhisperModelOption.BASE,
    val targetDurationSec: Int = 45,
    val isSubmitting: Boolean = false,
    val activeJob: JobStatusResponse? = null,
    val recentJobs: List<JobStatusResponse> = emptyList(),
    val isLoadingJobs: Boolean = false,
    val isDownloading: Boolean = false,
    val agentState: AgentStateResponse? = null,
    val nicheDraft: String = "AI tools, tech breakthroughs, and productivity",
    val agentIntervalHours: Int = 24,
    val agentPeakHourUtc: Int = 18,
    val agentAutoUpload: Boolean = true,
    val isAgentActionBusy: Boolean = false,
    val errorMessage: String? = null,
    val statusBannerMessage: String? = null
)

class ClipForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ClipForgeRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(ClipForgeUiState())
    val uiState: StateFlow<ClipForgeUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var agentPollJob: Job? = null

    init {
        viewModelScope.launch {
            repository.serverUrlFlow.collect { url ->
                _uiState.update {
                    it.copy(
                        serverUrl = url,
                        serverUrlDraft = url
                    )
                }
                checkBackendHealth(url)
                refreshJobs()
                refreshAgentState(syncDrafts = true)
            }
        }
    }

    fun updateYoutubeUrl(url: String) {
        _uiState.update { it.copy(youtubeUrl = url, errorMessage = null) }
    }

    fun selectCaptionStyle(style: CaptionStyleOption) {
        _uiState.update { it.copy(captionStyle = style) }
    }

    fun selectWhisperModel(model: WhisperModelOption) {
        _uiState.update { it.copy(whisperModel = model) }
    }

    fun updateTargetDuration(seconds: Int) {
        _uiState.update { it.copy(targetDurationSec = seconds.coerceIn(20, 60)) }
    }

    fun updateServerUrlDraft(url: String) {
        _uiState.update { it.copy(serverUrlDraft = url) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBannerMessage = null, errorMessage = null) }
    }

    fun checkBackendHealth(overrideUrl: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = BackendConnectionState.Checking) }
            val result = repository.checkHealth(overrideUrl)
            result.fold(
                onSuccess = { health ->
                    _uiState.update {
                        it.copy(connectionState = BackendConnectionState.Connected(health))
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            connectionState = BackendConnectionState.Unreachable(
                                err.localizedMessage ?: "Cannot reach Flask server"
                            )
                        )
                    }
                }
            )
        }
    }

    fun saveAndTestServerUrl() {
        val draft = _uiState.value.serverUrlDraft
        viewModelScope.launch {
            repository.saveServerUrl(draft)
            val normalized = ClipForgeApi.normalizeBaseUrl(draft).removeSuffix("/")
            _uiState.update {
                it.copy(
                    serverUrl = normalized,
                    serverUrlDraft = normalized,
                    statusBannerMessage = "Backend URL updated to $normalized"
                )
            }
            checkBackendHealth(normalized)
            refreshJobs()
        }
    }

    fun generateShort() {
        val state = _uiState.value
        val trimmedUrl = state.youtubeUrl.trim()
        if (trimmedUrl.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a YouTube URL to generate a short.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    errorMessage = null,
                    statusBannerMessage = null
                )
            }

            val result = repository.submitJob(
                youtubeUrl = trimmedUrl,
                captionStyle = state.captionStyle.apiValue,
                whisperModel = state.whisperModel.apiValue,
                clipDuration = state.targetDurationSec
            )

            result.fold(
                onSuccess = { resp ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            activeJob = JobStatusResponse(
                                jobId = resp.jobId,
                                url = trimmedUrl,
                                status = resp.status,
                                stage = "downloading",
                                progress = 4,
                                message = "Job queued on local engine..."
                            )
                        )
                    }
                    startPolling(resp.jobId)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = "Could not reach Flask backend (${state.serverUrl}): ${err.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            )
        }
    }

    private fun startPolling(jobId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val res = repository.pollStatus(jobId)
                res.fold(
                    onSuccess = { job ->
                        _uiState.update { it.copy(activeJob = job) }
                        if (job.status == "completed") {
                            refreshJobs()
                            return@launch
                        }
                        if (job.status == "failed") {
                            _uiState.update {
                                it.copy(errorMessage = job.error ?: "Processing failed.")
                            }
                            return@launch
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(errorMessage = "Lost connection while polling: ${err.localizedMessage}")
                        }
                        return@launch
                    }
                )
                delay(1500L)
            }
        }
    }

    fun selectJobForPreview(job: JobStatusResponse) {
        _uiState.update { it.copy(activeJob = job, errorMessage = null) }
    }

    fun refreshJobs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingJobs = true) }
            repository.fetchJobs().fold(
                onSuccess = { jobs ->
                    _uiState.update { it.copy(recentJobs = jobs, isLoadingJobs = false) }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingJobs = false) }
                }
            )
        }
    }

    fun downloadActiveClip(job: JobStatusResponse) {
        if (job.status != "completed") return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            val filename = job.downloadFilename ?: "clipforge_${job.jobId}.mp4"
            repository.saveClipToDownloads(job.jobId, filename).fold(
                onSuccess = { msg ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            statusBannerMessage = msg
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = "Download failed: ${err.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            )
        }
    }

    fun updateNicheDraft(niche: String) {
        _uiState.update { it.copy(nicheDraft = niche) }
    }

    fun updateAgentInterval(hours: Int) {
        _uiState.update { it.copy(agentIntervalHours = hours) }
    }

    fun updateAgentPeakHour(hourUtc: Int) {
        _uiState.update { it.copy(agentPeakHourUtc = hourUtc) }
    }

    fun updateAgentAutoUpload(autoUpload: Boolean) {
        _uiState.update { it.copy(agentAutoUpload = autoUpload) }
    }

    fun refreshAgentState(syncDrafts: Boolean = false) {
        viewModelScope.launch {
            repository.fetchAgentState().onSuccess { snap ->
                _uiState.update { state ->
                    state.copy(
                        agentState = snap,
                        nicheDraft = if (syncDrafts && snap.config.niche.isNotBlank()) snap.config.niche else state.nicheDraft,
                        agentIntervalHours = if (syncDrafts) snap.config.intervalHours else state.agentIntervalHours,
                        agentPeakHourUtc = if (syncDrafts) snap.config.peakHourUtc else state.agentPeakHourUtc,
                        agentAutoUpload = if (syncDrafts) snap.config.autoUpload else state.agentAutoUpload
                    )
                }
                if (snap.running) {
                    startAgentPolling()
                }
            }
        }
    }

    fun saveAgentConfig(overrideEnabled: Boolean? = null) {
        val st = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isAgentActionBusy = true) }
            repository.configureAgent(
                AgentConfigureRequest(
                    niche = st.nicheDraft.trim(),
                    intervalHours = st.agentIntervalHours,
                    peakHourUtc = st.agentPeakHourUtc,
                    autoUpload = st.agentAutoUpload,
                    enabled = overrideEnabled
                )
            ).fold(
                onSuccess = { snap ->
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            agentState = snap,
                            statusBannerMessage = if (overrideEnabled == true) {
                                "Autonomous mode activated!"
                            } else if (overrideEnabled == false) {
                                "Autonomous mode paused."
                            } else {
                                "Agent settings saved."
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            errorMessage = "Failed to configure agent: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun runAutonomousCycleNow() {
        val st = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isAgentActionBusy = true) }
            repository.configureAgent(
                AgentConfigureRequest(
                    niche = st.nicheDraft.trim(),
                    intervalHours = st.agentIntervalHours,
                    peakHourUtc = st.agentPeakHourUtc,
                    autoUpload = st.agentAutoUpload,
                    enabled = null
                )
            )
            repository.runAgentNow().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            statusBannerMessage = "Autonomous AI cycle started!"
                        )
                    }
                    refreshAgentState()
                    startAgentPolling()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            errorMessage = "Could not start agent: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun uploadExistingAgentRun(runId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAgentActionBusy = true) }
            repository.uploadAgentRun(runId).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            statusBannerMessage = "Uploaded and scheduled on YouTube!"
                        )
                    }
                    refreshAgentState()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAgentActionBusy = false,
                            errorMessage = "Upload failed: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    private fun startAgentPolling() {
        if (agentPollJob?.isActive == true) return
        agentPollJob = viewModelScope.launch {
            while (isActive) {
                delay(2500L)
                val res = repository.fetchAgentState()
                res.onSuccess { snap ->
                    _uiState.update { it.copy(agentState = snap) }
                    if (!snap.running) {
                        refreshJobs()
                        return@launch
                    }
                }.onFailure {
                    return@launch
                }
            }
        }
    }

    fun buildStreamUrl(relativeOrFull: String): String {
        if (relativeOrFull.startsWith("http://") || relativeOrFull.startsWith("https://")) {
            return relativeOrFull
        }
        val base = _uiState.value.serverUrl.trimEnd('/')
        val path = if (relativeOrFull.startsWith("/")) relativeOrFull else "/$relativeOrFull"
        return "$base$path"
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        agentPollJob?.cancel()
    }
}
