package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val ok: Boolean = false,
    val ffmpeg: Boolean = false,
    val ffprobe: Boolean = false,
    @Json(name = "gemini_configured") val geminiConfigured: Boolean = false,
    @Json(name = "cuda_available") val cudaAvailable: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ProcessRequest(
    val url: String,
    @Json(name = "caption_style") val captionStyle: String,
    @Json(name = "whisper_model") val whisperModel: String,
    @Json(name = "clip_duration") val clipDuration: Int
)

@JsonClass(generateAdapter = true)
data class ProcessResponse(
    @Json(name = "job_id") val jobId: String,
    val status: String
)

@JsonClass(generateAdapter = true)
data class CaptionCue(
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = ""
)

@JsonClass(generateAdapter = true)
data class ClipResult(
    @Json(name = "hook_title") val hookTitle: String = "",
    @Json(name = "viral_score") val viralScore: Int = 0,
    val reason: String = "",
    @Json(name = "start_time") val startTime: Double = 0.0,
    @Json(name = "end_time") val endTime: Double = 0.0,
    @Json(name = "start_formatted") val startFormatted: String = "00:00",
    @Json(name = "end_formatted") val endFormatted: String = "00:00",
    val duration: Double = 0.0,
    @Json(name = "highlight_keywords") val highlightKeywords: List<String> = emptyList(),
    @Json(name = "captions_preview") val captionsPreview: List<CaptionCue> = emptyList(),
    @Json(name = "download_url") val downloadUrl: String = "",
    @Json(name = "stream_url") val streamUrl: String = ""
)

@JsonClass(generateAdapter = true)
data class JobStatusResponse(
    @Json(name = "job_id") val jobId: String = "",
    val url: String = "",
    @Json(name = "caption_style") val captionStyle: String = "hormozi",
    @Json(name = "whisper_model") val whisperModel: String = "base",
    @Json(name = "target_duration") val targetDuration: Int = 45,
    val status: String = "queued",
    val stage: String = "queued",
    val progress: Int = 0,
    val message: String = "",
    @Json(name = "video_title") val videoTitle: String = "",
    val uploader: String = "",
    @Json(name = "video_duration") val videoDuration: Double = 0.0,
    val clip: ClipResult? = null,
    val error: String? = null,
    @Json(name = "download_filename") val downloadFilename: String? = null
)

@JsonClass(generateAdapter = true)
data class JobsListResponse(
    val jobs: List<JobStatusResponse> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AgentConfigDto(
    val niche: String = "",
    val enabled: Boolean = false,
    @Json(name = "interval_hours") val intervalHours: Int = 24,
    @Json(name = "peak_hour_utc") val peakHourUtc: Int = 18,
    @Json(name = "caption_style") val captionStyle: String = "hormozi",
    @Json(name = "whisper_model") val whisperModel: String = "base",
    @Json(name = "target_duration") val targetDuration: Int = 45,
    @Json(name = "auto_upload") val autoUpload: Boolean = true,
    @Json(name = "next_run_at") val nextRunAt: Double? = null
)

@JsonClass(generateAdapter = true)
data class YouTubeChannelDto(
    @Json(name = "channel_id") val channelId: String = "",
    val title: String = "",
    val handle: String = "",
    @Json(name = "subscriber_count") val subscriberCount: String = "0",
    @Json(name = "video_count") val videoCount: String = "0"
)

@JsonClass(generateAdapter = true)
data class TrendIdeaDto(
    val topic: String = "",
    @Json(name = "viral_angle") val viralAngle: String = "",
    @Json(name = "youtube_search_query") val youtubeSearchQuery: String = ""
)

@JsonClass(generateAdapter = true)
data class SeoPlanDto(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    @Json(name = "category_id") val categoryId: String = "22",
    @Json(name = "publish_at") val publishAt: String? = null
)

@JsonClass(generateAdapter = true)
data class YouTubeUploadDto(
    @Json(name = "video_id") val videoId: String = "",
    @Json(name = "studio_url") val studioUrl: String = "",
    @Json(name = "shorts_url") val shortsUrl: String = "",
    @Json(name = "publish_at") val publishAt: String? = null
)

@JsonClass(generateAdapter = true)
data class AgentRunDto(
    @Json(name = "run_id") val runId: String = "",
    @Json(name = "job_id") val jobId: String? = null,
    val niche: String = "",
    @Json(name = "trend_topic") val trendTopic: String = "",
    @Json(name = "source_title") val sourceTitle: String = "",
    val status: String = "",
    val seo: SeoPlanDto? = null,
    val youtube: YouTubeUploadDto? = null,
    @Json(name = "download_url") val downloadUrl: String? = null,
    @Json(name = "stream_url") val streamUrl: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class AgentStateResponse(
    val config: AgentConfigDto = AgentConfigDto(),
    val running: Boolean = false,
    @Json(name = "current_step") val currentStep: String = "idle",
    @Json(name = "current_message") val currentMessage: String = "",
    @Json(name = "youtube_connected") val youtubeConnected: Boolean = false,
    @Json(name = "has_client_secrets") val hasClientSecrets: Boolean = false,
    val channel: YouTubeChannelDto? = null,
    @Json(name = "last_trends") val lastTrends: List<TrendIdeaDto> = emptyList(),
    val runs: List<AgentRunDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AgentConfigureRequest(
    val niche: String,
    @Json(name = "interval_hours") val intervalHours: Int,
    @Json(name = "peak_hour_utc") val peakHourUtc: Int,
    @Json(name = "auto_upload") val autoUpload: Boolean,
    val enabled: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GenericActionResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
