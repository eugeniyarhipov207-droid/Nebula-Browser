package com.example.api

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiService {
    private const val TAG = "GeminiService"
    private const val MODEL = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateResponse(
        prompt: String,
        contextText: String? = null,
        chatHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNullOrEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Ошибка кнопочного ИИ: Ключ API GEMINI не найден. Задайте его в панели Secrets в Google AI Studio."
        }

        val url = "$BASE_URL?key=$apiKey"

        try {
            val root = JSONObject()
            val contentsArray = JSONArray()

            // System instructions
            val systemInstruction = JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", 
                    "Вы — встроенный ИИ-помощник Gemini Copilot в Nebula Browser. " +
                    "Вы помогаете пользователю работать с сайтами, отвечать на вопросы, переводить статьи. " +
                    "Вы можете читать текст веб-страницы, переданный в контексте. Отвечайте всегда вежливо, научно и понятно на русском языке."
                )))
            root.put("systemInstruction", systemInstruction)

            // Feed history
            for (turn in chatHistory) {
                val userPart = JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.first)))
                contentsArray.put(userPart)
                
                val modelPart = JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.second)))
                contentsArray.put(modelPart)
            }

            // Current turn with optional webpage body content
            val messageWithContext = if (!contextText.isNullOrEmpty()) {
                "КОНТЕКСТ СТРАНИЦЫ (ТЕКСТ САЙТА):\n\"\"\"\n$contextText\n\"\"\"\n\nВОПРОС ПОЛЬЗОВАТЕЛЯ ПО САЙТУ:\n$prompt"
            } else {
                prompt
            }

            val currentTurn = JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", messageWithContext)))
            contentsArray.put(currentTurn)

            root.put("contents", contentsArray)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = root.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errText = response.body?.string() ?: ""
                    Log.e(TAG, "Request failed code: ${response.code}, details: $errText")
                    return@withContext "Ошибка: Gemini API вернул код ${response.code}. Проверьте статус ключа."
                }

                val responseString = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val contentObj = firstCandidate.optJSONObject("content")
                    val partsArray = contentObj?.optJSONArray("parts")
                    if (partsArray != null && partsArray.length() > 0) {
                        return@withContext partsArray.getJSONObject(0).optString("text")
                    }
                }
                return@withContext "Интеллектуальный помощник не смог сгенерировать текст."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error", e)
            return@withContext "Запрос завершился сбоем: ${e.localizedMessage ?: "Сетевая ошибка"}"
        }
    }
}
