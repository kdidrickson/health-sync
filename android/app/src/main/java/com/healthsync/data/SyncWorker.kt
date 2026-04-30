package com.healthsync.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.healthsync.BuildConfig
import com.healthsync.util.PreferencesHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "health_sync"
        private const val TAG = "SyncWorker"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        if (runAttemptCount >= 3) {
            recordResult("Failed: max retries exceeded")
            return Result.failure()
        }

        val client = try {
            HealthConnectClient.getOrCreate(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect unavailable: ${e.message}")
            recordResult("Failed: Health Connect not available")
            return Result.failure()
        }

        val endTime = Instant.now()
        val startTime = endTime.minus(48, ChronoUnit.HOURS)
        val timeFilter = TimeRangeFilter.between(startTime, endTime)

        val records = JSONArray()

        // --- Daily data (steps, HR, calories, distance) ---
        val dailyData = JSONObject()

        runCatching {
            val response = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeFilter))
            dailyData.put("steps", response.records.sumOf { it.count })
        }.onFailure { Log.w(TAG, "steps: ${it.message}") }

        runCatching {
            val response = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeFilter))
            val samples = response.records.flatMap { it.samples }
            if (samples.isNotEmpty()) {
                dailyData.put("avg_hr_bpm", samples.map { it.beatsPerMinute }.average().toInt())
                val hrSamples = JSONArray()
                samples.forEach { s ->
                    hrSamples.put(JSONObject().apply {
                        put("bpm", s.beatsPerMinute)
                        put("time", s.time.toString())
                    })
                }
                dailyData.put("heart_rate_samples", hrSamples)
            }
        }.onFailure { Log.w(TAG, "heart_rate: ${it.message}") }

        runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeFilter)
            )
            dailyData.put("active_calories_kcal",
                response.records.sumOf { it.energy.inKilocalories })
        }.onFailure { Log.w(TAG, "calories: ${it.message}") }

        runCatching {
            val response = client.readRecords(ReadRecordsRequest(DistanceRecord::class, timeFilter))
            dailyData.put("distance_meters", response.records.sumOf { it.distance.inMeters })
        }.onFailure { Log.w(TAG, "distance: ${it.message}") }

        if (dailyData.length() > 0) {
            records.put(JSONObject().apply {
                put("type", "daily")
                put("data", dailyData)
            })
        }

        // --- Body data (weight, body fat) ---
        val bodyData = JSONObject()

        runCatching {
            val response = client.readRecords(ReadRecordsRequest(WeightRecord::class, timeFilter))
            response.records.lastOrNull()?.let {
                bodyData.put("weight_kg", it.weight.inKilograms)
            }
        }.onFailure { Log.w(TAG, "weight: ${it.message}") }

        runCatching {
            val response = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, timeFilter))
            response.records.lastOrNull()?.let {
                bodyData.put("body_fat_percentage", it.percentage.value)
            }
        }.onFailure { Log.w(TAG, "body_fat: ${it.message}") }

        if (bodyData.length() > 0) {
            records.put(JSONObject().apply {
                put("type", "body")
                put("data", bodyData)
            })
        }

        // --- Sleep ---
        runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, timeFilter)
            )
            if (response.records.isNotEmpty()) {
                val totalSeconds = response.records.sumOf {
                    ChronoUnit.SECONDS.between(it.startTime, it.endTime)
                }
                val stages = JSONArray()
                response.records.flatMap { it.stages }.forEach { stage ->
                    stages.put(JSONObject().apply {
                        put("stage", stage.stage)
                        put("start", stage.startTime.toString())
                        put("end", stage.endTime.toString())
                    })
                }
                val sleepData = JSONObject().apply {
                    put("duration_seconds", totalSeconds)
                    if (stages.length() > 0) put("stages", stages)
                }
                records.put(JSONObject().apply {
                    put("type", "sleep")
                    put("data", sleepData)
                })
            }
        }.onFailure { Log.w(TAG, "sleep: ${it.message}") }

        if (records.length() == 0) {
            recordResult("No data returned from Health Connect")
            return Result.success()
        }

        val envelope = JSONObject().apply {
            put("records", records)
            put("synced_at", Instant.now().toString())
        }

        return try {
            postToWebhook(envelope.toString())
            val msg = "OK — ${records.length()} data type(s) synced"
            recordResult(msg)
            Log.i(TAG, msg)
            Result.success()
        } catch (e: Exception) {
            val msg = "Failed: ${e.message}"
            recordResult(msg)
            Log.e(TAG, msg, e)
            Result.retry()
        }
    }

    private fun postToWebhook(json: String) {
        val request = Request.Builder()
            .url(BuildConfig.WEBHOOK_URL)
            .addHeader("X-Webhook-Secret", BuildConfig.WEBHOOK_SECRET)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
        }
    }

    private fun recordResult(result: String) {
        PreferencesHelper.setLastSync(applicationContext, System.currentTimeMillis(), result)
    }
}
