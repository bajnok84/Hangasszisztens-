package com.sajat.hangasszisztens

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object GeminiApi {
    private val openRouterModels = listOf(
        "openrouter/free:online",
        "openrouter/free"
    )

    private val models = listOf(
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash"
    )

    fun generateContent(
        apiKey: String,
        prompt: String,
        systemPrompt: String = "Te NEO vagy, egy magyar hangasszisztens."
    ): String {
        return when {
            apiKey.startsWith("sk-or-") -> generateOpenRouterContent(apiKey, prompt, systemPrompt)
            apiKey.startsWith("sk-") -> chatCompletion(
                "https://api.openai.com/v1/chat/completions",
                apiKey,
                "gpt-4o-mini",
                prompt,
                "OpenAI",
                systemPrompt
            )
            apiKey.startsWith("gsk_") -> chatCompletion(
                "https://api.groq.com/openai/v1/chat/completions",
                apiKey,
                "llama-3.3-70b-versatile",
                prompt,
                "Groq",
                systemPrompt
            )
            apiKey.startsWith("AIza") || apiKey.startsWith("AQ.") ->
                generateGeminiContent(apiKey, prompt, systemPrompt)
            else -> throw IOException("Unsupported API key format")
        }
    }

    fun generateVisionContent(apiKey: String, prompt: String, imageBase64: String): String {
        if (!apiKey.startsWith("sk-or-")) {
            throw IOException("A kamerás AI-látáshoz OpenRouter API-kulcs szükséges")
        }
        return chatCompletionWithImage(
            apiKey,
            prompt,
            imageBase64,
            "google/gemma-3-27b-it:free"
        )
    }

    private fun generateOpenRouterContent(
        apiKey: String,
        prompt: String,
        systemPrompt: String
    ): String {
        var lastFailure: Exception? = null
        openRouterModels.forEach { model ->
            try {
                return chatCompletion(
                    "https://openrouter.ai/api/v1/chat/completions",
                    apiKey,
                    model,
                    prompt,
                    "OpenRouter",
                    systemPrompt
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IOException(
            "All OpenRouter models failed: ${lastFailure?.message ?: "unknown error"}",
            lastFailure
        )
    }

    private fun generateGeminiContent(
        apiKey: String,
        prompt: String,
        systemPrompt: String
    ): String {
        var lastFailure: Exception? = null
        var throttled = false

        for (round in 0..1) {
            models.forEachIndexed { index, model ->
                try {
                    return request(apiKey, model, prompt, systemPrompt)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    lastFailure = e
                    val rateLimited = e is GeminiHttpException &&
                        (e.statusCode == 429 || e.statusCode == 503)
                    throttled = throttled || rateLimited

                    val hasNextModel = index < models.lastIndex
                    val hasAnotherRound = round == 0 && throttled
                    if (hasNextModel || hasAnotherRound) {
                        try {
                            Thread.sleep(if (rateLimited) 1_200 else 400)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw interrupted
                        }
                    }
                }
            }

            if (round == 0 && !throttled) break
        }

        throw IOException(
            "All Gemini models failed: ${lastFailure?.message ?: "unknown error"}",
            lastFailure
        )
    }

    private fun chatCompletion(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: String,
        provider: String,
        systemPrompt: String
    ): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        try {
            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
                    ?: throw ChatCompletionHttpException(provider, responseCode, "")
            }
            val responseBody = responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) {
                throw ChatCompletionHttpException(provider, responseCode, responseBody)
            }

            return JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: throw IOException("$provider returned an empty response")
        } finally {
            connection.disconnect()
        }
    }

    private fun chatCompletionWithImage(
        apiKey: String,
        prompt: String,
        imageBase64: String,
        model: String
    ): String {
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                        )
                )
            val body = JSONObject().put("model", model).put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
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
                    ?: throw ChatCompletionHttpException("OpenRouter", responseCode, "")
            }
            val responseBody = responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) {
                throw ChatCompletionHttpException("OpenRouter", responseCode, responseBody)
            }
            return JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: throw IOException("OpenRouter returned an empty vision response")
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): String {
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
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
                .put(
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
                    ?: throw GeminiHttpException(responseCode, "")
            }
            val responseBody = responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) {
                throw GeminiHttpException(responseCode, responseBody)
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

    private class GeminiHttpException(
        val statusCode: Int,
        responseBody: String
    ) : IOException("Gemini HTTP $statusCode: $responseBody")

    private class ChatCompletionHttpException(
        provider: String,
        statusCode: Int,
        responseBody: String
    ) : IOException("$provider HTTP $statusCode: $responseBody")
}
