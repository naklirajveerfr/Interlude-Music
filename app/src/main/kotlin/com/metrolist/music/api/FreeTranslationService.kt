/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Key-free, out-of-the-box lyrics translation.
 *
 * The contract mirrors [DeepLService.translate]: the input is split by `\n` and the result
 * contains exactly one entry per input line (failed lines are padded with ""), so callers can
 * map translations back to lyrics lines by index without any extra plumbing.
 */
object FreeTranslationService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Repeated chorus lines must not trigger duplicate network calls.
    private val cache = ConcurrentHashMap<String, String>()

    // Keep the free endpoints (and their anonymous quotas) from being hammered.
    private val requestLimit = Semaphore(4)

    private val CJK = Regex("[\u4E00-\u9FFF\u3400-\u4DBF]")
    private val KANA = Regex("[\u3040-\u309F\u30A0-\u30FF]")
    private val HANGUL = Regex("[\uAC00-\uD7AF\u1100-\u11FF]")
    private val CYRILLIC = Regex("[\u0400-\u04FF]")

    suspend fun translate(
        text: String,
        targetLanguage: String,
        mode: String = "Literal",
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val lines = text.lines()
        if (lines.isEmpty()) return@withContext Result.success(emptyList())

        val target = normalizeTarget(targetLanguage)
        Timber.d("FreeTranslationService: ${lines.size} lines, target=$target, mode=$mode")

        // Free endpoints cannot transliterate, so "Transcribed"/"Romanized" gracefully
        // degrade to a plain Literal translation instead of failing.
        val uniqueLines = lines.filter { it.isNotBlank() }.distinct()

        val translatedByLine: Map<String, String?> = try {
            coroutineScope {
                val deferred = uniqueLines.associateWith { line ->
                    async {
                        val cacheKey = "$target|$line"
                        cache[cacheKey]?.let { return@async it }
                        requestLimit.withPermit {
                            delay(40)
                            val translated = translateLine(line, target)
                            if (translated != null) cache[cacheKey] = translated
                            translated
                        }
                    }
                }
                deferred.mapValues { (_, d) -> d.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Free translation failed")
            return@withContext Result.failure(Exception("built-in translation unavailable"))
        }

        val anySuccess = translatedByLine.values.any { !it.isNullOrBlank() }
        if (uniqueLines.isNotEmpty() && !anySuccess) {
            return@withContext Result.failure(Exception("built-in translation unavailable"))
        }

        Result.success(
            lines.map { line -> if (line.isBlank()) "" else translatedByLine[line].orEmpty() },
        )
    }

    private fun translateLine(line: String, target: String): String? {
        val source = detectSource(line)
        // Nothing to translate when the lyric is already in the target language
        // (e.g. Chinese lyrics with a Chinese target); avoid a pointless request.
        if (source.equals(target, ignoreCase = true) ||
            (source.startsWith("zh", ignoreCase = true) && target.startsWith("zh", ignoreCase = true))
        ) {
            return line
        }
        return tryMyMemory(line, target) ?: tryGoogle(line, target)
    }

    private fun tryMyMemory(line: String, target: String): String? {
        val langPair = "${detectSource(line)}|$target"
        val url = "https://api.mymemory.translated.net/get" +
            "?q=${URLEncoder.encode(line, "UTF-8")}" +
            "&langpair=${URLEncoder.encode(langPair, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "InterludeMusic/1.0")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("responseStatus", 0) != 200) return null
                if (json.optBoolean("quotaFinished", false)) return null
                val translated = json.optJSONObject("responseData")?.optString("translatedText")
                    ?: return null
                // MyMemory reports quota / length problems inside an otherwise-200 body.
                translated
                    .takeIf {
                        it.isNotBlank() &&
                            !it.startsWith("MYMEMORY WARNING", ignoreCase = true) &&
                            !it.startsWith("QUERY LENGTH LIMIT", ignoreCase = true) &&
                            !it.startsWith("INVALID", ignoreCase = true)
                    }
                    ?.let(::unescapeHtml)
            }
        } catch (e: Exception) {
            Timber.d(e, "MyMemory translation failed")
            null
        }
    }

    private fun tryGoogle(line: String, target: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=${URLEncoder.encode(target, "UTF-8")}&dt=t" +
            "&q=${URLEncoder.encode(line, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "InterludeMusic/1.0")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                // [[["translated","source",null,null,10]], ...] -> [0][0][0]
                JSONArray(body)
                    .optJSONArray(0)
                    ?.optJSONArray(0)
                    ?.optString(0)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::unescapeHtml)
            }
        } catch (e: Exception) {
            Timber.d(e, "Google gtx translation failed")
            null
        }
    }

    /** MyMemory requires an explicit source language and does not accept "auto". */
    private fun detectSource(line: String): String = when {
        KANA.containsMatchIn(line) -> "ja"
        HANGUL.containsMatchIn(line) -> "ko"
        CYRILLIC.containsMatchIn(line) -> "ru"
        CJK.containsMatchIn(line) -> "zh-CN"
        else -> "en"
    }

    private fun normalizeTarget(language: String): String {
        val value = language.trim()
        return when (value.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant" -> "zh-TW"
            "en-us" -> "en-US"
            "en-gb" -> "en-GB"
            "pt-br" -> "pt-BR"
            else -> value.take(2).lowercase()
        }
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
