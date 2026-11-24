package com.example.data.ai

import android.util.Log
import com.example.data.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "SmartReplyRepo"

class SmartReplyRepository {

    private val chatModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    private val suggestionsSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "suggestions" to Schema.array(
                    items = Schema.string(description = "A concise natural reply under 10 words"),
                    description = "Exactly 3 short contextual reply chips"
                )
            )
        )
    }

    private val suggestionsModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = suggestionsSchema
            }
        )
    }

    suspend fun generateContactReply(
        userName: String,
        contactName: String,
        isGroup: Boolean,
        recentMessages: List<ChatMessage>,
        latestUserMessage: String
    ): Result<String> {
        return try {
            val historySnippet = recentMessages.takeLast(8).joinToString("\n") { msg ->
                val speaker = if (msg.sender == "me") userName else contactName
                "$speaker: ${msg.text}"
            }

            val prompt = buildString {
                appendLine("You are roleplaying as $contactName in a friendly mobile messaging app called Nomi Chat.")
                if (isGroup) {
                    appendLine("This is a lively group chat ($contactName). Reply naturally as one of the friends in the group.")
                } else {
                    appendLine("Reply naturally, warmly, and concisely (1-2 short sentences max) in the same language or script the user used (English, Hindi, or Hinglish/Urdu).")
                }
                if (historySnippet.isNotBlank()) {
                    appendLine("Recent conversation:")
                    appendLine(historySnippet)
                }
                appendLine("User ($userName) just sent: $latestUserMessage")
                appendLine("Write only the reply message text without quotes or prefixes.")
            }

            val response = withTimeout(60_000L) {
                chatModel.generateContent(prompt)
            }
            val reply = response.text?.trim()
            if (!reply.isNullOrBlank()) {
                Result.success(reply)
            } else {
                Result.failure(IllegalStateException("Gemini returned an empty reply."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Smart reply timed out", e)
            Result.failure(IllegalStateException("Smart reply timed out. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Smart reply failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateQuickSuggestions(
        userName: String,
        contactName: String,
        recentMessages: List<ChatMessage>
    ): Result<List<String>> {
        return try {
            val historySnippet = recentMessages.takeLast(6).joinToString("\n") { msg ->
                val speaker = if (msg.sender == "me") userName else contactName
                "$speaker: ${msg.text}"
            }

            val prompt = buildString {
                appendLine("Generate 3 short, natural quick-reply suggestions for $userName replying to $contactName.")
                appendLine("Match the language/tone of the recent messages (English, Hindi, or Hinglish).")
                if (historySnippet.isNotBlank()) {
                    appendLine("Conversation:")
                    appendLine(historySnippet)
                } else {
                    appendLine("Start a friendly conversation.")
                }
            }

            val response = withTimeout(60_000L) {
                suggestionsModel.generateContent(prompt)
            }
            val jsonText = response.text ?: return Result.failure(IllegalStateException("Empty suggestions JSON"))
            val json = JSONObject(jsonText)
            val arr = json.getJSONArray("suggestions")
            val list = (0 until arr.length()).mapNotNull { idx ->
                arr.optString(idx).trim().takeIf { it.isNotEmpty() }
            }.take(3)
            if (list.isNotEmpty()) {
                Result.success(list)
            } else {
                Result.failure(IllegalStateException("No suggestions parsed"))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Suggestions timed out", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate quick suggestions", e)
            Result.failure(e)
        }
    }
}
