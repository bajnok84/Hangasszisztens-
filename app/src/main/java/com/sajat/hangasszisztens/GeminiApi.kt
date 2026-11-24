package com.sajat.hangasszisztens

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object GeminiApi {
    private val models = listOf(
        "gemini-3.8-flash",
        "gemini-3.8-flash-lite",
        "gemini-2.5-flash-lite"
    )

    fun generateContent(apiKey: String, prompt: String): String {
        var lastFailure: Exception? = null

        models.forEachIndexed { index, model ->
            try {
                return request(apiKey, model, prompt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                lastFailure = e
                if (index < models.lastIndex) {
                    try {
                        Thread.sleep(400)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    }
                }
            }
        }

        throw IOException(
            "All Gemini models failed: ${lastFailure?.message ?: "unknown error"}",
            lastFailure
        )
    }

    private fun request(apiKey: String, model: String, prompt: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("x-goog-api-key", apiKey)
            doOutput = true
        }

        try {
            val body = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
                    ?: throw IOException("Gemini HTTP $responseCode")
            }
            val responseBody = responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) {
                throw IOException("Gemini HTTP $responseCode: $responseBody")
            }

            return JSONObject(responseBody)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: throw IOException("Gemini returned an empty response")
        } finally {
            connection.disconnect()
        }
    }
}
