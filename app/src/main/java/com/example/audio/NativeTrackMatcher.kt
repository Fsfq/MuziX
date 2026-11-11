package com.example.audio

import android.util.Log

// Нативный парсер и нечеткий матчер треков на C++20 / NDK
object NativeTrackMatcher {

    private const val TAG = "NativeTrackMatcher"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("track_matcher")
            isNativeLoaded = true
            Log.i(TAG, "Native C++ track_matcher library loaded successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "Native track_matcher library not loaded, using fallback", e)
            isNativeLoaded = false
        }
    }

    // Внешние нативные методы JNI
    @JvmStatic
    private external fun nativeMatchTracks(
        a1: String, t1: String,
        a2: String, t2: String,
        dur1: Long, dur2: Long,
        threshold: Float
    ): Float

    @JvmStatic
    private external fun nativeIsJunk(
        artist: String, title: String, path: String
    ): Boolean

    @JvmStatic
    private external fun nativeCleanTrack(
        rawArtist: String, rawTitle: String
    ): Array<String>

    @JvmStatic
    private external fun nativeTransliterate(input: String): String

    // Проверка на телефонные звонки, диктофон, нейрослоп (Suno/Udio) и звуковой шум
    fun isJunk(artist: String, title: String, path: String = ""): Boolean {
        if (isNativeLoaded) {
            try {
                return nativeIsJunk(artist, title, path)
            } catch (e: Throwable) {
                Log.w(TAG, "nativeIsJunk call failed, fallback used", e)
            }
        }
        val lower = "${artist.lowercase()} ${title.lowercase()} ${path.lowercase()}"
        if (lower.contains("recordings") || lower.contains("callrecordings") || lower.contains("/calls/") ||
            lower.contains("/recorder/") || lower.contains("soundrecorder") || lower.contains("sound_recorder") ||
            lower.contains("audiorecorder") || lower.contains("диктофон") || lower.contains("voice memo") ||
            lower.contains("запись разговора") || lower.contains("запись звонка") || lower.contains("входящий вызов") ||
            lower.contains("исходящий вызов")) return true

        if (lower.contains("+7") || lower.contains("89") || lower.contains("_2026") || lower.contains("_2025") || lower.contains("_2024")) {
            if (lower.contains("байшев") || lower.contains("айгуль") || lower.contains("мама") ||
                lower.contains("тимур") || lower.contains("артём") || lower.contains("+79") ||
                lower.contains("890") || lower.contains("891") || lower.contains("892") ||
                lower.contains("893") || lower.contains("895") || lower.contains("896") ||
                lower.contains("898") || lower.contains("899")) return true
        }

        if (lower.contains("sunofix") || lower.contains("suno.ai") || lower.contains("suno") ||
            lower.contains("udio.com") || lower.contains("нейросеть") || lower.contains("ai song") ||
            lower.contains("ai cover") || lower.contains("ai generated")) return true

        val soundJunk = listOf(
            "шум дождя", "для сна", "медитаци", "желобок каникулы", "белый шум",
            "sleep sound", "white noise", "relaxing sound", "nature sounds",
            "караоке", "минусовка", "ringtone", "рингтон", "sound effect",
            "гитар релакс", "гитара релакс", "guitar relax", "relaxing guitar",
            "acoustic guitar chill", "type beat", "trap beat", "free beat",
            "call of duty", "jack wall", "game ost"
        )
        if (soundJunk.any { lower.contains(it) }) return true

        val aLow = artist.trim().lowercase()
        return aLow == "<unknown>" || aLow == "unknown" || aLow == "unknown artist" || aLow == "неизвестный исполнитель" || aLow == "track"
    }

    // Сопоставление двух треков с нечетким скорингом (0.0 .. 1.0)
    fun matchTracks(
        artist1: String, title1: String,
        artist2: String, title2: String,
        dur1: Long = 0L, dur2: Long = 0L,
        threshold: Float = 0.70f
    ): Float {
        if (isNativeLoaded) {
            try {
                return nativeMatchTracks(artist1, title1, artist2, title2, dur1, dur2, threshold)
            } catch (e: Throwable) {
                Log.w(TAG, "nativeMatchTracks call failed, fallback used", e)
            }
        }
        // Запасной быстрый матчер
        val t1 = title1.lowercase().replace(Regex("[^a-zа-яё0-9]+"), "")
        val t2 = title2.lowercase().replace(Regex("[^a-zа-яё0-9]+"), "")
        if (t1.isEmpty() || t2.isEmpty()) return 0f
        return if (t1 == t2 || t1.contains(t2) || t2.contains(t1)) 0.85f else 0f
    }

    // Очистка спам-тегов и имен файлов
    fun cleanTrack(rawArtist: String, rawTitle: String): Pair<String, String> {
        if (isNativeLoaded) {
            try {
                val res = nativeCleanTrack(rawArtist, rawTitle)
                if (res.size >= 2) {
                    return Pair(res[0], res[1])
                }
            } catch (e: Throwable) {
                Log.w(TAG, "nativeCleanTrack call failed, fallback used", e)
            }
        }
        val cleanA = rawArtist.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
        val cleanT = rawTitle.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
        return Pair(cleanA, cleanT)
    }

    // Транслитерация RU -> EN
    fun transliterate(input: String): String {
        if (isNativeLoaded) {
            try {
                return nativeTransliterate(input)
            } catch (e: Throwable) {
                Log.w(TAG, "nativeTransliterate call failed, fallback used", e)
            }
        }
        return input
    }
}
