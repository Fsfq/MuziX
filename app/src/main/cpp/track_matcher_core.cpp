#include "track_matcher_core.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <sstream>
#include <unordered_map>
#include <unordered_set>

namespace track_matcher {

// UTF-8 lowercase (ASCII + Кириллица)
std::string toLowerUtf8(const std::string& input) {
    std::string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(input[i]);
        if (c < 0x80) {
            out += static_cast<char>(std::tolower(c));
        } else if (c == 0xD0 && i + 1 < input.size()) {
            unsigned char c2 = static_cast<unsigned char>(input[++i]);
            if (c2 >= 0x90 && c2 <= 0xAF) { // А-Я -> а-я
                out += static_cast<char>(0xD0);
                out += static_cast<char>(c2 + 0x20);
            } else if (c2 == 0x81) { // Ё -> ё
                out += static_cast<char>(0xD1);
                out += static_cast<char>(0x91);
            } else {
                out += static_cast<char>(c);
                out += static_cast<char>(c2);
            }
        } else if (c == 0xD1 && i + 1 < input.size()) {
            unsigned char c2 = static_cast<unsigned char>(input[++i]);
            out += static_cast<char>(c);
            out += static_cast<char>(c2);
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}

// Фонетическая транслитерация RU -> EN
std::string transliterateRuToEn(const std::string& input) {
    static const std::unordered_map<std::string, std::string> ru2en = {
        {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"},
        {"е", "e"}, {"ё", "yo"}, {"ж", "zh"}, {"з", "z"}, {"и", "i"},
        {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"},
        {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"},
        {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"},
        {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""},
        {"э", "e"}, {"ю", "yu"}, {"я", "ya"}
    };

    std::string out;
    out.reserve(input.size() * 2);
    for (size_t i = 0; i < input.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(input[i]);
        if ((c == 0xD0 || c == 0xD1) && i + 1 < input.size()) {
            std::string ruChar = input.substr(i, 2);
            i++;
            auto it = ru2en.find(ruChar);
            if (it != ru2en.end()) {
                out += it->second;
            } else {
                out += ruChar;
            }
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}

static bool isAsciiAlphaNum(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
}

std::vector<std::string> tokenize(const std::string& input) {
    std::string lower = toLowerUtf8(input);
    std::vector<std::string> tokens;
    std::string cur;

    for (size_t i = 0; i < lower.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(lower[i]);
        if (isAsciiAlphaNum(c)) {
            cur += static_cast<char>(c);
        } else if ((c == 0xD0 || c == 0xD1) && i + 1 < lower.size()) {
            cur += static_cast<char>(c);
            cur += lower[++i];
        } else {
            if (!cur.empty()) {
                if (cur.size() > 1 || isAsciiAlphaNum(cur[0])) {
                    tokens.push_back(cur);
                }
                cur.clear();
            }
        }
    }
    if (!cur.empty()) {
        tokens.push_back(cur);
    }
    return tokens;
}

// Очистка спама и мусорных меток
std::string stripJunkTokens(const std::string& input) {
    std::string s = toLowerUtf8(input);

    static const std::vector<std::string> junkPatterns = {
        "[drivemusic.me]", "(drivemusic.me)", "drivemusic.me",
        "[mp3party.net]", "(mp3party.net)", "mp3party.net", "mp3party",
        "[hitmo.top]", "(hitmo.top)", "hitmo.top", "hitmotop",
        "[zaycev.net]", "(zaycev.net)", "zaycev.net",
        "[muzofond.fm]", "(muzofond.fm)", "muzofond",
        "[sefon.pro]", "(sefon.pro)", "sefon",
        "(official video)", "[official video]", "official video",
        "(official audio)", "[official audio]", "official audio",
        "(official music video)", "[official music video]",
        "(audio)", "[audio]", "(lyrics)", "[lyrics]", "(lyric video)",
        "(visualizer)", "[visualizer]", "(clip)", "[clip]", "клип",
        "tagmp3_", "-sunofix-v1", "-sunofix", "sunofix",
        "320kbps", "128kbps", "24bit-48k", "24bit", "48k", "flac", "mp3", "m4a", "wav"
    };

    for (const auto& pat : junkPatterns) {
        size_t pos = 0;
        while ((pos = s.find(pat, pos)) != std::string::npos) {
            s.replace(pos, pat.size(), " ");
            pos += 1;
        }
    }

    // Удаление Telegram bot префиксов вида "4_5818836922574837611" или "2_5253..."
    if (s.size() >= 18 && (s[0] == '2' || s[0] == '4') && s[1] == '_') {
        size_t nextSpace = s.find_first_of(" -_.\t", 2);
        if (nextSpace != std::string::npos && nextSpace <= 24) {
            s = s.substr(nextSpace);
        }
    }

    // Схлопывание пробелов
    std::string clean;
    clean.reserve(s.size());
    bool lastSpace = true;
    for (char c : s) {
        if (c == ' ' || c == '\t' || c == '_' || c == '-') {
            if (!lastSpace) {
                clean += ' ';
                lastSpace = true;
            }
        } else {
            clean += c;
            lastSpace = false;
        }
    }
    if (!clean.empty() && clean.back() == ' ') clean.pop_back();
    return clean;
}

// Детектор телефонных записей и мусора
bool isJunkAudio(const std::string& artist, const std::string& title, const std::string& path) {
    std::string combined = toLowerUtf8(artist + " " + title + " " + path);

    // 1. Папки и маркеры телефонных записей и диктофонов
    if (combined.find("recordings") != std::string::npos ||
        combined.find("callrecordings") != std::string::npos ||
        combined.find("/calls/") != std::string::npos ||
        combined.find("диктофон") != std::string::npos ||
        combined.find("voice memo") != std::string::npos ||
        combined.find("/recorder/") != std::string::npos ||
        combined.find("soundrecorder") != std::string::npos ||
        combined.find("sound_recorder") != std::string::npos ||
        combined.find("audiorecorder") != std::string::npos ||
        combined.find("запись разговора") != std::string::npos ||
        combined.find("запись звонка") != std::string::npos ||
        combined.find("входящий вызов") != std::string::npos ||
        combined.find("исходящий вызов") != std::string::npos) {
        return true;
    }

    // 2. Номера телефонов (+7, 89...) и даты звонков
    if (combined.find("+7") != std::string::npos ||
        combined.find("89") != std::string::npos ||
        combined.find("_2026") != std::string::npos ||
        combined.find("_2025") != std::string::npos ||
        combined.find("_2024") != std::string::npos) {
        // Проверка: это имя звонка (например "Саша Байшев_20261024", "+7905...")
        if (combined.find("байшев") != std::string::npos ||
            combined.find("айгуль") != std::string::npos ||
            combined.find("мама") != std::string::npos ||
            combined.find("тимур") != std::string::npos ||
            combined.find("артём") != std::string::npos ||
            combined.find("+79") != std::string::npos ||
            combined.find("890") != std::string::npos ||
            combined.find("891") != std::string::npos ||
            combined.find("892") != std::string::npos ||
            combined.find("893") != std::string::npos ||
            combined.find("895") != std::string::npos ||
            combined.find("896") != std::string::npos ||
            combined.find("898") != std::string::npos ||
            combined.find("899") != std::string::npos) {
            return true;
        }
    }

    // 3. Звуковой шум, спам и навязчивый гитарный релакс
    static const std::vector<std::string> soundJunk = {
        "шум дождя", "для сна", "медитаци", "желобок каникулы", "белый шум",
        "sleep sound", "white noise", "relaxing sound", "nature sounds",
        "караоке", "минусовка", "ringtone", "рингтон", "sound effect",
        "гитар релакс", "гитара релакс", "guitar relax", "relaxing guitar",
        "acoustic guitar chill", "type beat", "trap beat", "free beat"
    };
    for (const auto& sj : soundJunk) {
        if (combined.find(sj) != std::string::npos) {
            return true;
        }
    }

    // 4. Нейрослоп-генерации (Suno, Udio)
    if (combined.find("sunofix") != std::string::npos ||
        combined.find("suno.ai") != std::string::npos ||
        combined.find("suno") != std::string::npos ||
        combined.find("udio.com") != std::string::npos ||
        combined.find("нейросеть") != std::string::npos ||
        combined.find("ai song") != std::string::npos ||
        combined.find("ai cover") != std::string::npos ||
        combined.find("ai generated") != std::string::npos) {
        return true;
    }

    // 5. Игровые фоновые саундтреки Call of Duty / OST
    if (combined.find("call of duty") != std::string::npos ||
        combined.find("jack wall") != std::string::npos ||
        combined.find("game ost") != std::string::npos ||
        combined.find("soundtrack full") != std::string::npos) {
        return true;
    }

    // 6. Пустые теги
    std::string aLow = toLowerUtf8(artist);
    if (aLow == "<unknown>" || aLow == "unknown" || aLow == "unknown artist" ||
        aLow == "неизвестный исполнитель" || aLow == "track") {
        if (title.empty() || title == artist || title.find(".mp3") != std::string::npos) {
            return true;
        }
    }

    return false;
}

// Парсинг трека
ParsedTrack parseTrack(const std::string& rawArtist, const std::string& rawTitle, const std::string& path) {
    ParsedTrack pt;
    pt.isJunk = isJunkAudio(rawArtist, rawTitle, path);

    std::string cleanA = stripJunkTokens(rawArtist);
    std::string cleanT = stripJunkTokens(rawTitle);

    // Если артист пустой, но в title есть "Artist - Title"
    if (cleanA.empty() || cleanA == "<unknown>" || cleanA == "unknown") {
        size_t dash = cleanT.find(" - ");
        if (dash != std::string::npos && dash > 1) {
            cleanA = cleanT.substr(0, dash);
            cleanT = cleanT.substr(dash + 3);
        }
    }

    // Детект версии/модификатора
    std::string fullLow = toLowerUtf8(cleanT);
    if (fullLow.find("slowed") != std::string::npos) pt.versionTag = "slowed";
    else if (fullLow.find("speed up") != std::string::npos || fullLow.find("speedup") != std::string::npos) pt.versionTag = "speedup";
    else if (fullLow.find("remix") != std::string::npos) pt.versionTag = "remix";
    else if (fullLow.find("instrumental") != std::string::npos || fullLow.find("инструментал") != std::string::npos) pt.versionTag = "instrumental";

    pt.cleanArtist = cleanA;
    pt.cleanTitle = cleanT;
    pt.artistTokens = tokenize(cleanA);
    pt.titleTokens = tokenize(cleanT);
    return pt;
}

// Сходство по Левенштейну
float levenshteinSimilarity(const std::string& s1, const std::string& s2) {
    if (s1 == s2) return 1.0f;
    if (s1.empty() || s2.empty()) return 0.0f;

    const size_t m = s1.size();
    const size_t n = s2.size();
    std::vector<size_t> dp(n + 1);

    for (size_t j = 0; j <= n; ++j) dp[j] = j;

    for (size_t i = 1; i <= m; ++i) {
        size_t prev = dp[0];
        dp[0] = i;
        for (size_t j = 1; j <= n; ++j) {
            size_t temp = dp[j];
            if (s1[i - 1] == s2[j - 1]) {
                dp[j] = prev;
            } else {
                dp[j] = 1 + std::min({prev, dp[j], dp[j - 1]});
            }
            prev = temp;
        }
    }
    size_t dist = dp[n];
    size_t maxLen = std::max(m, n);
    return 1.0f - (static_cast<float>(dist) / static_cast<float>(maxLen));
}

// Сходство по Джаро-Винклеру
float jaroWinklerSimilarity(const std::string& s1, const std::string& s2) {
    if (s1 == s2) return 1.0f;
    if (s1.empty() || s2.empty()) return 0.0f;

    const size_t len1 = s1.size();
    const size_t len2 = s2.size();
    const size_t matchDist = (std::max(len1, len2) / 2) > 0 ? (std::max(len1, len2) / 2) - 1 : 0;

    std::vector<bool> s1Matches(len1, false);
    std::vector<bool> s2Matches(len2, false);
    size_t matches = 0;

    for (size_t i = 0; i < len1; ++i) {
        size_t start = (i > matchDist) ? i - matchDist : 0;
        size_t end = std::min(i + matchDist + 1, len2);
        for (size_t j = start; j < end; ++j) {
            if (!s2Matches[j] && s1[i] == s2[j]) {
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
    }

    if (matches == 0) return 0.0f;

    size_t transpositions = 0;
    size_t k = 0;
    for (size_t i = 0; i < len1; ++i) {
        if (!s1Matches[i]) continue;
        while (!s2Matches[k]) k++;
        if (s1[i] != s2[k]) transpositions++;
        k++;
    }

    float m = static_cast<float>(matches);
    float jaro = ((m / len1) + (m / len2) + ((m - transpositions / 2.0f) / m)) / 3.0f;

    // Префикс Винклера (до 4 символов)
    size_t prefix = 0;
    for (size_t i = 0; i < std::min({len1, len2, size_t(4)}); ++i) {
        if (s1[i] == s2[i]) prefix++;
        else break;
    }

    return jaro + prefix * 0.1f * (1.0f - jaro);
}

// Token Sort Ratio (сравнение отсортированных слов)
float tokenSortRatio(const std::string& s1, const std::string& s2) {
    auto t1 = tokenize(s1);
    auto t2 = tokenize(s2);
    if (t1.empty() || t2.empty()) return 0.0f;

    std::sort(t1.begin(), t1.end());
    std::sort(t2.begin(), t2.end());

    std::string sorted1, sorted2;
    for (const auto& t : t1) { sorted1 += t; sorted1 += ' '; }
    for (const auto& t : t2) { sorted2 += t; sorted2 += ' '; }

    return levenshteinSimilarity(sorted1, sorted2);
}

// Token Set Ratio (пересечение множеств слов)
float tokenSetRatio(const std::string& s1, const std::string& s2) {
    auto t1 = tokenize(s1);
    auto t2 = tokenize(s2);
    if (t1.empty() || t2.empty()) return 0.0f;

    std::unordered_set<std::string> set1(t1.begin(), t1.end());
    std::unordered_set<std::string> set2(t2.begin(), t2.end());

    size_t intersectCount = 0;
    for (const auto& word : set1) {
        if (set2.find(word) != set2.end()) {
            intersectCount++;
        }
    }

    size_t unionCount = set1.size() + set2.size() - intersectCount;
    if (unionCount == 0) return 0.0f;

    float jaccard = static_cast<float>(intersectCount) / static_cast<float>(unionCount);
    float sortSim = tokenSortRatio(s1, s2);
    return std::max(jaccard, sortSim);
}

// Комплексный скоринг двух треков
MatchScore matchTracks(
    const std::string& artist1, const std::string& title1,
    const std::string& artist2, const std::string& title2,
    int64_t durMs1, int64_t durMs2,
    float threshold
) {
    MatchScore ms = {0.0f, 0.0f, 0.0f, 1.0f, false};

    auto p1 = parseTrack(artist1, title1);
    auto p2 = parseTrack(artist2, title2);

    if (p1.isJunk || p2.isJunk) {
        ms.totalScore = 0.0f;
        ms.isMatch = false;
        return ms;
    }

    // 1. Сравнение названий треков (с транслитерацией)
    std::string t1 = p1.cleanTitle;
    std::string t2 = p2.cleanTitle;
    std::string t1Tr = transliterateRuToEn(t1);
    std::string t2Tr = transliterateRuToEn(t2);

    float tSimDirect = std::max(tokenSetRatio(t1, t2), jaroWinklerSimilarity(t1, t2));
    float tSimTr = std::max(tokenSetRatio(t1Tr, t2Tr), jaroWinklerSimilarity(t1Tr, t2Tr));
    ms.titleScore = std::max(tSimDirect, tSimTr);

    // 2. Сравнение артистов (с транслитерацией)
    std::string a1 = p1.cleanArtist;
    std::string a2 = p2.cleanArtist;
    std::string a1Tr = transliterateRuToEn(a1);
    std::string a2Tr = transliterateRuToEn(a2);

    float aSimDirect = std::max(tokenSetRatio(a1, a2), jaroWinklerSimilarity(a1, a2));
    float aSimTr = std::max(tokenSetRatio(a1Tr, a2Tr), jaroWinklerSimilarity(a1Tr, a2Tr));
    ms.artistScore = std::max(aSimDirect, aSimTr);

    // Если артист пустой у одного из них, но title совпадает отлично
    if (a1.empty() || a2.empty()) {
        ms.artistScore = (ms.titleScore >= 0.85f) ? 0.70f : 0.0f;
    }

    // 3. Совпадение версий (например Slowed)
    float versionBonus = 0.0f;
    if (!p1.versionTag.empty() && p1.versionTag == p2.versionTag) {
        versionBonus = 0.15f;
    }

    // 4. Проверка длительности
    if (durMs1 > 0 && durMs2 > 0) {
        int64_t diffSec = std::abs(durMs1 - durMs2) / 1000;
        if (diffSec <= 6) {
            ms.durationScore = 1.0f;
        } else if (diffSec <= 18) {
            ms.durationScore = 0.85f;
        } else if (diffSec <= 40) {
            ms.durationScore = 0.50f;
        } else {
            ms.durationScore = 0.10f;
        }
    }

    // Итоговый балл: 48% title + 37% artist + 15% duration + bonus
    ms.totalScore = (ms.titleScore * 0.48f) + (ms.artistScore * 0.37f) + (ms.durationScore * 0.15f) + versionBonus;
    if (ms.totalScore > 1.0f) ms.totalScore = 1.0f;

    // Условие матча: title должен быть не менее 0.55, artist не менее 0.40, и итоговый балл >= threshold
    ms.isMatch = (ms.totalScore >= threshold) && (ms.titleScore >= 0.55f) && (ms.artistScore >= 0.40f);

    return ms;
}

} // namespace track_matcher

// C API
extern "C" {
    TM_EXPORT float tm_match_tracks(const char* a1, const char* t1, const char* a2, const char* t2, int64_t dur1, int64_t dur2) {
        std::string s_a1 = a1 ? a1 : "";
        std::string s_t1 = t1 ? t1 : "";
        std::string s_a2 = a2 ? a2 : "";
        std::string s_t2 = t2 ? t2 : "";
        auto res = track_matcher::matchTracks(s_a1, s_t1, s_a2, s_t2, dur1, dur2);
        return res.totalScore;
    }

    TM_EXPORT int tm_is_junk(const char* artist, const char* title, const char* path) {
        std::string a = artist ? artist : "";
        std::string t = title ? title : "";
        std::string p = path ? path : "";
        return track_matcher::isJunkAudio(a, t, p) ? 1 : 0;
    }

    TM_EXPORT void tm_clean_track(const char* raw_artist, const char* raw_title, char* out_artist, int max_art_len, char* out_title, int max_tit_len) {
        std::string a = raw_artist ? raw_artist : "";
        std::string t = raw_title ? raw_title : "";
        auto pt = track_matcher::parseTrack(a, t);
        if (out_artist && max_art_len > 0) {
            strncpy(out_artist, pt.cleanArtist.c_str(), max_art_len - 1);
            out_artist[max_art_len - 1] = '\0';
        }
        if (out_title && max_tit_len > 0) {
            strncpy(out_title, pt.cleanTitle.c_str(), max_tit_len - 1);
            out_title[max_tit_len - 1] = '\0';
        }
    }
}
