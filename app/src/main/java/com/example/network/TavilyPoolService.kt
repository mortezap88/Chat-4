package com.example.network

import android.util.Log
import com.example.data.ApiKeyItem
import com.example.data.FailoverEvent
import com.example.data.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service managing Tavily Web Search API with automated key pooling and failover.
 * Executes live search queries, rotates keys on rate-limits, and returns structured search results.
 */
class TavilyPoolService(
    private var tavilyKeys: List<ApiKeyItem>,
    private var onKeyStatusChanged: (List<ApiKeyItem>) -> Unit = {},
    private var onFailover: (FailoverEvent) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeKeyIndex = 0

    init {
        val firstUsable = tavilyKeys.indexOfFirst { it.status != KeyStatus.FAILED }
        if (firstUsable >= 0) {
            activeKeyIndex = firstUsable
        }
    }

    fun updateKeys(newKeys: List<ApiKeyItem>) {
        tavilyKeys = newKeys
        if (activeKeyIndex >= tavilyKeys.size) {
            activeKeyIndex = (tavilyKeys.size - 1).coerceAtLeast(0)
        }
    }

    fun hasAvailableKeys(): Boolean {
        return tavilyKeys.any { it.status != KeyStatus.FAILED && it.key.isNotBlank() }
    }

    suspend fun search(
        query: String,
        thoroughness: String = "Balanced",
        maxResults: Int = 5
    ): TavilySearchResult? = withContext(Dispatchers.IO) {
        if (tavilyKeys.isEmpty() || query.isBlank()) return@withContext null

        val searchDepth = when (thoroughness.lowercase()) {
            "fast" -> "basic"
            "comprehensive" -> "advanced"
            else -> "basic"
        }

        val effectiveMaxResults = when (thoroughness.lowercase()) {
            "fast" -> maxResults.coerceIn(1, 3)
            "comprehensive" -> maxResults.coerceIn(5, 8)
            else -> maxResults.coerceIn(3, 5)
        }

        val totalKeys = tavilyKeys.size
        var attempts = 0

        while (attempts < totalKeys) {
            val keyItem = tavilyKeys.getOrNull(activeKeyIndex) ?: break

            try {
                val jsonPayload = JSONObject().apply {
                    put("api_key", keyItem.key)
                    put("query", query)
                    put("search_depth", searchDepth)
                    put("max_results", effectiveMaxResults)
                    put("include_answer", true)
                    put("include_raw_content", false)
                }

                val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val root = JSONObject(responseBody)
                    val answer = if (root.has("answer") && !root.isNull("answer")) root.getString("answer") else null
                    val resultsList = mutableListOf<TavilySource>()

                    val resultsArray = root.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until resultsArray.length()) {
                        val itemObj = resultsArray.getJSONObject(i)
                        resultsList.add(
                            TavilySource(
                                title = itemObj.optString("title", "Untitled Source"),
                                url = itemObj.optString("url", ""),
                                content = itemObj.optString("content", ""),
                                score = itemObj.optDouble("score", 0.0)
                            )
                        )
                    }

                    // Key succeeded
                    if (keyItem.status != KeyStatus.WORKING) {
                        updateKeyStatus(keyItem.id, KeyStatus.WORKING, null)
                    }

                    return@withContext TavilySearchResult(
                        query = query,
                        answer = answer,
                        results = resultsList
                    )
                } else {
                    val code = response.code
                    val errorMsg = "Tavily HTTP $code: ${responseBody.take(120)}"
                    Log.w("TavilyPoolService", "Key fail on ${keyItem.label}: $errorMsg")

                    failoverToNextKey(keyItem, errorMsg)
                    attempts++
                }
            } catch (e: Exception) {
                Log.w("TavilyPoolService", "Network exception on ${keyItem.label}: ${e.message}")
                failoverToNextKey(keyItem, e.message ?: "Network error")
                attempts++
            }
        }

        null
    }

    private fun failoverToNextKey(failedItem: ApiKeyItem, reason: String) {
        val updated = tavilyKeys.toMutableList()
        val idx = updated.indexOfFirst { it.id == failedItem.id }
        if (idx >= 0) {
            val curr = updated[idx]
            updated[idx] = curr.copy(
                status = KeyStatus.FAILED,
                errorMessage = reason,
                failCount = curr.failCount + 1
            )
        }
        tavilyKeys = updated
        onKeyStatusChanged(updated)

        // Select next available key
        val nextUsable = tavilyKeys.indexOfFirst { it.status != KeyStatus.FAILED }
        if (nextUsable >= 0) {
            activeKeyIndex = nextUsable
            val toKey = tavilyKeys[nextUsable]
            onFailover(
                FailoverEvent(
                    fromKeyMasked = failedItem.maskedKey,
                    toKeyMasked = toKey.maskedKey,
                    reason = reason
                )
            )
        } else {
            // Loop round-robin if all marked failed
            activeKeyIndex = (activeKeyIndex + 1) % tavilyKeys.size.coerceAtLeast(1)
        }
    }

    private fun updateKeyStatus(id: String, status: KeyStatus, error: String?) {
        val updated = tavilyKeys.map {
            if (it.id == id) it.copy(status = status, errorMessage = error) else it
        }
        tavilyKeys = updated
        onKeyStatusChanged(updated)
    }
}
