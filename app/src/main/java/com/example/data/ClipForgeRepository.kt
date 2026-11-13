package com.example.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.clipForgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clipforge_prefs"
)

class ClipForgeRepository(private val context: Context) {

    private val serverUrlKey = stringPreferencesKey("server_url")

    val serverUrlFlow: Flow<String> = context.clipForgeDataStore.data.map { prefs ->
        prefs[serverUrlKey] ?: DEFAULT_SERVER_URL
    }

    suspend fun getServerUrl(): String = serverUrlFlow.first()

    suspend fun saveServerUrl(url: String) {
        val normalized = ClipForgeApi.normalizeBaseUrl(url).removeSuffix("/")
        context.clipForgeDataStore.edit { prefs ->
            prefs[serverUrlKey] = normalized
        }
    }

    suspend fun checkHealth(baseUrl: String? = null): Result<HealthResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = baseUrl ?: getServerUrl()
                ClipForgeApi.create(url).getHealth()
            }
        }

    suspend fun submitJob(
        youtubeUrl: String,
        captionStyle: String,
        whisperModel: String,
        clipDuration: Int
    ): Result<ProcessResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val api = ClipForgeApi.create(getServerUrl())
            api.startProcessing(
                ProcessRequest(
                    url = youtubeUrl,
                    captionStyle = captionStyle,
                    whisperModel = whisperModel,
                    clipDuration = clipDuration
                )
            )
        }
    }

    suspend fun pollStatus(jobId: String): Result<JobStatusResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).getJobStatus(jobId)
            }
        }

    suspend fun fetchJobs(): Result<List<JobStatusResponse>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).getJobs().jobs
            }
        }

    suspend fun fetchAgentState(): Result<AgentStateResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).getAgentState()
            }
        }

    suspend fun configureAgent(request: AgentConfigureRequest): Result<AgentStateResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).configureAgent(request)
            }
        }

    suspend fun runAgentNow(): Result<GenericActionResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).runAgentNow()
            }
        }

    suspend fun uploadAgentRun(runId: String): Result<GenericActionResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                ClipForgeApi.create(getServerUrl()).uploadAgentRun(runId)
            }
        }

    suspend fun saveClipToDownloads(jobId: String, filename: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val api = ClipForgeApi.create(getServerUrl())
                val body = api.downloadClip(jobId)
                val safeName = if (filename.endsWith(".mp4")) filename else "$filename.mp4"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/ClipForge"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create MediaStore entry")

                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Unable to open MediaStore output stream")

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "Saved to Downloads/ClipForge/$safeName"
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        ?: context.filesDir
                    val outFile = File(dir, safeName)
                    outFile.outputStream().use { out ->
                        body.byteStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    "Saved to ${outFile.absolutePath}"
                }
            }
        }

    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:5000"
    }
}
