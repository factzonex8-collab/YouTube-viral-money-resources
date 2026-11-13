package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

interface ClipForgeApi {
    @GET("api/health")
    suspend fun getHealth(): HealthResponse

    @POST("api/process")
    suspend fun startProcessing(@Body body: ProcessRequest): ProcessResponse

    @GET("api/status/{jobId}")
    suspend fun getJobStatus(@Path("jobId") jobId: String): JobStatusResponse

    @GET("api/jobs")
    suspend fun getJobs(): JobsListResponse

    @Streaming
    @GET("api/download/{jobId}")
    suspend fun downloadClip(@Path("jobId") jobId: String): ResponseBody

    @GET("api/agent/state")
    suspend fun getAgentState(): AgentStateResponse

    @POST("api/agent/configure")
    suspend fun configureAgent(@Body body: AgentConfigureRequest): AgentStateResponse

    @POST("api/agent/run-now")
    suspend fun runAgentNow(): GenericActionResponse

    @POST("api/agent/upload/{runId}")
    suspend fun uploadAgentRun(@Path("runId") runId: String): GenericActionResponse

    companion object {
        private val moshi: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()

        fun create(baseUrl: String): ClipForgeApi {
            val normalized = normalizeBaseUrl(baseUrl)
            return Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ClipForgeApi::class.java)
        }

        fun normalizeBaseUrl(rawUrl: String): String {
            var cleaned = rawUrl.trim()
            if (cleaned.isEmpty()) cleaned = "http://10.0.2.2:5000/"
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                cleaned = "http://$cleaned"
            }
            if (!cleaned.endsWith("/")) {
                cleaned = "$cleaned/"
            }
            return cleaned
        }
    }
}
