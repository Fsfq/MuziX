#pragma once

#include <string>
#include <vector>
#include <cstdint>

#ifdef _WIN32
  #define TM_EXPORT __declspec(dllexport)
#else
  #define TM_EXPORT __attribute__((visibility("default")))
#endif

namespace track_matcher {

struct ParsedTrack {
    std::string cleanArtist;
    std::string cleanTitle;
    std::string versionTag; // "slowed", "speedup", "remix", "instrumental", etc.
    std::vector<std::string> artistTokens;
    std::vector<std::string> titleTokens;
    bool isJunk;
};

struct MatchScore {
    float totalScore;      // 0.0 .. 1.0
    float artistScore;     // 0.0 .. 1.0
    float titleScore;      // 0.0 .. 1.0
    float durationScore;   // 0.0 .. 1.0
    bool isMatch;          // true if totalScore >= threshold (default 0.70)
};

// UTF-8 и транслитерация
std::string toLowerUtf8(const std::string& input);
std::string transliterateRuToEn(const std::string& input);
std::string stripJunkTokens(const std::string& input);
std::vector<std::string> tokenize(const std::string& input);

// Алгоритмы нечеткого сравнения
float levenshteinSimilarity(const std::string& s1, const std::string& s2);
float jaroWinklerSimilarity(const std::string& s1, const std::string& s2);
float tokenSortRatio(const std::string& s1, const std::string& s2);
float tokenSetRatio(const std::string& s1, const std::string& s2);

// Детектор мусора (звонки, диктофоны, нейрослоп, белый шум)
bool isJunkAudio(const std::string& artist, const std::string& title, const std::string& path = "");

// Парсинг трека из сырых данных или имени файла
ParsedTrack parseTrack(const std::string& rawArtist, const std::string& rawTitle, const std::string& path = "");

// Комплексный скоринг двух треков
MatchScore matchTracks(
    const std::string& artist1, const std::string& title1,
    const std::string& artist2, const std::string& title2,
    int64_t durMs1 = 0, int64_t durMs2 = 0,
    float threshold = 0.70f
);

} // namespace track_matcher

// C-экспорты для вызова из Python (ctypes), C# или PC Desktop
extern "C" {
    TM_EXPORT float tm_match_tracks(const char* a1, const char* t1, const char* a2, const char* t2, int64_t dur1, int64_t dur2);
    TM_EXPORT int tm_is_junk(const char* artist, const char* title, const char* path);
    TM_EXPORT void tm_clean_track(const char* raw_artist, const char* raw_title, char* out_artist, int max_art_len, char* out_title, int max_tit_len);
}
