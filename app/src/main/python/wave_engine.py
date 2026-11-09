# -*- coding: utf-8 -*-
import concurrent.futures
import json
import os
import random
import re
import time
import urllib.parse
import urllib.request
import yt_dlp

USER_AGENT_WEB = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

YANDEX_HEADERS = {
    "User-Agent": "YandexMusic/2402 client:android; Droid",
    "X-Yandex-Music-Client": "YandexMusic/Android",
    "Accept": "application/json",
    "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
}

# пресеты настроений
MOOD_QUERIES_YANDEX = {
    "Бодрое": [
        "phonk drift",
        "workout hits",
        "drum and bass neurofunk",
        "бодрый русский рэп",
        "хиты фонка",
        "energetic trap",
        "gym workout phonk",
        "club bass electronic",
        "russian trap hits",
        "русский трэп",
    ],
    "Спокойное": [
        "lofi hip hop beats",
        "acoustic guitar chill",
        "indie chill vibes",
        "русский инди",
        "спокойный чилл",
        "вечерняя музыка для души",
        "ambient relax calm",
        "атмосферный поп",
        "neo soul chill",
    ],
    "Мрачное": [
        "dark phonk",
        "witch house",
        "dark wave synthwave",
        "русский пост панк",
        "витчхаус",
        "мрачный рок",
        "cold wave russian",
        "депрессивный хип хоп",
        "gothic dark synth",
    ],
    "Весёлое": [
        "party dance hits",
        "disco funk dance",
        "summer party hits",
        "танцевальные хиты русские",
        "веселая вечеринка",
        "club dance electro",
        "зажигательный поп",
        "house dance party",
        "электро свинг",
    ],
}

DEFAULT_SEEDS = {
    "Бодрое": [
        "DVRST", "Shadowraze", "Skrillex", "The Prodigy", "Big Baby Tape",
        "Miyagi & Эндшпиль", "PHARAOH", "Travis Scott", "Fred again..", "Kizaru", "Markul"
    ],
    "Спокойное": [
        "Mac DeMarco", "Cigarettes After Sex", "Joji", "Скриптонит", "Сироткин",
        "Beach House", "Zoloto", "три дня дождя", "Дайте танк (!)", "Saypink!"
    ],
    "Мрачное": [
        "Crystal Castles", "Lebanon Hanover", "Молчат Дома", "Ploho", "IC3PEAK",
        "Mareux", "Буерак", "Boy Harsher", "Черниковская Хата", "GSPD"
    ],
    "Весёлое": [
        "Daft Punk", "Gorillaz", "The Weeknd", "Dua Lipa", "DEAD BLONDE",
        "Cream Soda", "Lida", "Calvin Harris", "GONE.Fludd", "The Hatters"
    ],
}

# маппинг языков
CHARACTER_MAP = {
    "favorites": "Любимое",
    "favorite": "Любимое",
    "любимое": "Любимое",
    "любимый": "Любимое",
    "discover": "Незнакомое",
    "discovery": "Незнакомое",
    "незнакомое": "Незнакомое",
    "незнакомый": "Незнакомое",
    "popular": "Популярное",
    "популярное": "Популярное",
    "популярный": "Популярное",
    "hits": "Популярное",
}

MOOD_MAP = {
    "energetic": "Бодрое",
    "energy": "Бодрое",
    "бодрое": "Бодрое",
    "бодрый": "Бодрое",
    "calm": "Спокойное",
    "relax": "Спокойное",
    "спокойное": "Спокойное",
    "спокойный": "Спокойное",
    "dark": "Мрачное",
    "мрачное": "Мрачное",
    "мрачный": "Мрачное",
    "party": "Весёлое",
    "fun": "Весёлое",
    "весёлое": "Весёлое",
    "веселое": "Весёлое",
    "веселый": "Весёлое",
}

def _search_youtube_scrape(query, max_results=5):
    """Скрейпинг YouTube."""
    if not query:
        return []
    try:
        encoded = urllib.parse.quote_plus(query)
        url = f"https://www.youtube.com/results?search_query={encoded}"
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": USER_AGENT_WEB,
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            }
        )
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            html = resp.read().decode("utf-8", errors="ignore")
        match = re.search(r"var\s+ytInitialData\s*=\s*({.+?});</script>", html)
        if not match:
            match = re.search(r"ytInitialData\s*=\s*({.+?});", html)
        if not match:
            return []
        data = json.loads(match.group(1))
        sections = data.get("contents", {}).get("twoColumnSearchResultsRenderer", {}).get("primaryContents", {}).get("sectionListRenderer", {}).get("contents", [])
        results = []
        for section in sections:
            item_section = section.get("itemSectionRenderer", {})
            items = item_section.get("contents", [])
            for item in items:
                video = item.get("videoRenderer")
                if not video:
                    continue
                video_id = video.get("videoId")
                if not video_id or len(video_id) != 11:
                    continue
                title = ""
                title_obj = video.get("title", {})
                if "runs" in title_obj and title_obj["runs"]:
                    title = title_obj["runs"][0].get("text", "")
                elif "simpleText" in title_obj:
                    title = title_obj.get("simpleText", "")
                results.append({"video_id": video_id, "title": title})
                if len(results) >= max_results:
                    return results
        return results
    except Exception as e:
        print(f"Scrape search exception: {e}")
        return []

# кэш потоков
_STREAM_CACHE = {}

BANNED_TITLE_REGEXES = [
    re.compile(r"\b(?:podcast|подкаст|подкасты)\b", re.I),
    re.compile(r"\b(?:full\s+album|весь\s+альбом|дискография|discography)\b", re.I),
    re.compile(r"\b(?:1|2|3|4|5|10)\s*(?:hour|hours|час|часа|часов)\b", re.I),
    re.compile(r"\b(?:dj\s+set|dj\s+mix|live\s+set|party\s+mix|club\s+mix|workout\s+mix)\b", re.I),
    re.compile(r"\b(?:интервью|interview|audiobook|аудиокнига|обзор)\b", re.I),
    re.compile(r"\b(?:реакция|reaction|стрим|stream|live\s+stream)\b", re.I),
    re.compile(r"\b(?:полный\s+концерт|full\s+concert|live\s+at)\b", re.I),
    re.compile(r"\b(?:soundtrack\s+full|ost\s+full|compilation)\b", re.I),
]


def _is_title_banned(title):
    if not title:
        return False
    t = title.strip()
    return any(rx.search(t) for rx in BANNED_TITLE_REGEXES)


def _parse_time_str_to_sec(text):
    if not text:
        return 0
    clean = text.strip()
    parts = clean.split(":")
    try:
        if len(parts) == 2:
            return int(parts[0]) * 60 + int(parts[1])
        elif len(parts) == 3:
            return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
    except Exception:
        pass
    return 0


def _http_get_json(url, headers=None, timeout=5):
    h = headers or {
        "User-Agent": USER_AGENT_WEB,
        "Accept": "application/json",
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8",
    }
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="ignore"))


def _norm_key(artist, title):
    a = re.sub(r"[^a-zа-яё0-9]+", "", (artist or "").lower())
    t = re.sub(r"\(.*?\)|\[.*?\]", "", (title or "").lower())
    t = re.sub(r"[^a-zа-яё0-9]+", "", t)
    return f"{a}::{t}"


def _clean_artist_name(artist):
    if not artist:
        return ""
    cleaned = re.split(r"\s*(?:,|feat\.?|ft\.?|&|/|;|\sx\s)\s*", artist, flags=re.IGNORECASE)[0].strip()
    bad = {"неизвестный исполнитель", "unknown artist", "youtube", "muzix", "various artists", "моя волна"}
    if cleaned.lower() in bad or len(cleaned) < 2:
        return ""
    return cleaned


def _yandex_track_to_candidate(item, reason=""):
    if not item or not isinstance(item, dict):
        return None
    title = (item.get("title") or "").strip()
    version = (item.get("version") or "").strip()
    if version:
        title = f"{title} ({version})"

    artists_list = item.get("artists") or []
    artist_names = [a.get("name", "").strip() for a in artists_list if a.get("name")]
    artist = ", ".join(artist_names) if artist_names else ""

    albums = item.get("albums") or []
    album = albums[0].get("title", "").strip() if albums else ""

    # размер обложки
    cover_uri = item.get("coverUri") or (albums[0].get("coverUri") if albums else "") or ""
    cover_url = ""
    if cover_uri:
        if not cover_uri.startswith("http"):
            cover_url = f"https://{cover_uri.replace('%%', '400x400')}"
        else:
            cover_url = cover_uri.replace("%%", "400x400")

    duration_ms = int(item.get("durationMs") or 180000)
    if not title or not artist:
        return None
    # фильтр длительности
    if duration_ms < 60000 or duration_ms > 600000:
        return None
    if _is_title_banned(title):
        return None

    yandex_id = str(item.get("id") or "")
    yandex_artist_id = str(artists_list[0].get("id") or "") if artists_list else ""

    return {
        "title": title,
        "artist": artist,
        "album": album,
        "cover_url": cover_url,
        "duration_ms": duration_ms,
        "yandex_id": yandex_id,
        "yandex_artist_id": yandex_artist_id,
        "reason": reason,
    }


def _yandex_search_candidates(query, limit=12, reason=""):
    out = []
    try:
        q = urllib.parse.quote_plus(query)
        url = f"https://api.music.yandex.net/search?text={q}&type=track&page=0&page_size={limit}"
        data = _http_get_json(url, headers=YANDEX_HEADERS, timeout=5)
        results = data.get("result", {}).get("tracks", {}).get("results", [])
        for item in results:
            cand = _yandex_track_to_candidate(item, reason=reason)
            if cand:
                out.append(cand)
    except Exception as e:
        print(f"Yandex search note for '{query}': {e}")
    return out


def _yandex_similar_tracks(track_id, limit=8, reason=""):
    out = []
    if not track_id:
        return out
    try:
        url = f"https://api.music.yandex.net/tracks/{track_id}/similar"
        data = _http_get_json(url, headers=YANDEX_HEADERS, timeout=5)
        sim_tracks = data.get("result", {}).get("similarTracks", [])
        for item in sim_tracks[:limit]:
            cand = _yandex_track_to_candidate(item, reason=reason)
            if cand:
                out.append(cand)
    except Exception as e:
        print(f"Yandex similar tracks note for {track_id}: {e}")
    return out


def _yandex_artist_recommendations(artist_name, character, banned_set, known_artist_low):
    results = []
    if not artist_name or artist_name.lower() in banned_set:
        return results
    try:
        q = urllib.parse.quote_plus(artist_name)
        search_url = f"https://api.music.yandex.net/search?text={q}&type=artist&page=0&page_size=2"
        sdata = _http_get_json(search_url, headers=YANDEX_HEADERS, timeout=5)
        artists = sdata.get("result", {}).get("artists", {}).get("results", [])
        if not artists:
            return results
        artist_id = artists[0].get("id")
        if not artist_id:
            return results

        brief_url = f"https://api.music.yandex.net/artists/{artist_id}/brief-info"
        bdata = _http_get_json(brief_url, headers=YANDEX_HEADERS, timeout=5)
        res = bdata.get("result", {})

        # топ треков
        if character in ("Любимое", "Популярное"):
            pop_tracks = res.get("popularTracks", [])
            for tr in pop_tracks[:6]:
                cand = _yandex_track_to_candidate(tr, reason=artist_name)
                if cand:
                    results.append(cand)

        # похожие артисты
        sim_artists = res.get("similarArtists", [])
        random.shuffle(sim_artists)

        kept_sim = []
        for rel in sim_artists:
            rname = (rel.get("name") or "").strip()
            if not rname or rname.lower() in banned_set:
                continue
            if character == "Незнакомое" and rname.lower() in known_artist_low:
                continue
            kept_sim.append(rel)
            if len(kept_sim) >= 3:
                break

        for rel in kept_sim:
            rid = rel.get("id")
            rname = (rel.get("name") or "").strip()
            if not rid:
                continue
            rtop_url = f"https://api.music.yandex.net/artists/{rid}/brief-info"
            rtop_data = _http_get_json(rtop_url, headers=YANDEX_HEADERS, timeout=5)
            rpop = rtop_data.get("result", {}).get("popularTracks", [])
            for tr in rpop[:4]:
                cand = _yandex_track_to_candidate(tr, reason=f"Похоже на {artist_name}")
                if cand:
                    results.append(cand)
    except Exception as e:
        print(f"Yandex artist graph note for {artist_name}: {e}")
    return results


def _yandex_chart_candidates(limit=25):
    results = []
    try:
        url = "https://api.music.yandex.net/landing3?blocks=chart"
        data = _http_get_json(url, headers=YANDEX_HEADERS, timeout=5)
        blocks = data.get("result", {}).get("blocks", [])
        for block in blocks:
            btype = block.get("type")
            if btype in ("chart", "chart-tracks"):
                entities = block.get("entities", [])
                for ent in entities:
                    data_obj = ent.get("data", {})
                    track_obj = data_obj.get("track") or data_obj
                    cand = _yandex_track_to_candidate(track_obj, reason="Чарт Яндекс")
                    if cand:
                        results.append(cand)
        if len(results) < 8:
            # чарты Яндекс
            results.extend(_yandex_search_candidates("лучшие треки 2024 чарт", limit=limit, reason="Чарт"))
    except Exception as e:
        print(f"Yandex chart note: {e}")
        # запасной поиск
        results.extend(_yandex_search_candidates("популярные русские хиты чарт", limit=limit, reason="Чарт"))
    return results


def _deezer_track_to_candidate(item, reason=""):
    if not item:
        return None
    title = (item.get("title") or "").strip()
    artist_obj = item.get("artist") or {}
    artist = (artist_obj.get("name") or "").strip()
    album_obj = item.get("album") or {}
    album = (album_obj.get("title") or "").strip()
    cover = (
        album_obj.get("cover_xl")
        or album_obj.get("cover_big")
        or album_obj.get("cover_medium")
        or ""
    )
    duration_sec = int(item.get("duration") or 0)
    if not title or not artist:
        return None
    # фильтр длительности
    if duration_sec and (duration_sec < 60 or duration_sec > 600):
        return None
    if _is_title_banned(title):
        return None
    return {
        "title": title,
        "artist": artist,
        "album": album,
        "cover_url": cover,
        "duration_ms": duration_sec * 1000 if duration_sec > 0 else 180000,
        "reason": reason,
    }


def _deezer_artist_recommendations(artist_name, limit=12, reason=""):
    out = []
    if not artist_name:
        return out
    try:
        q = urllib.parse.quote_plus(artist_name)
        search_url = f"https://api.deezer.com/search/artist?q={q}&limit=1"
        sdata = _http_get_json(search_url, timeout=4)
        data_arr = sdata.get("data", [])
        if not data_arr:
            return out
        artist_id = data_arr[0].get("id")
        if not artist_id:
            return out

        # артисты Deezer
        rel_url = f"https://api.deezer.com/artist/{artist_id}/related?limit=5"
        rel_data = _http_get_json(rel_url, timeout=4)
        rel_artists = rel_data.get("data", [])

        # треки Deezer
        artist_ids = [artist_id] + [r.get("id") for r in rel_artists[:3] if r.get("id")]
        for a_id in artist_ids:
            top_url = f"https://api.deezer.com/artist/{a_id}/top?limit=6"
            tdata = _http_get_json(top_url, timeout=4)
            for item in tdata.get("data", []):
                cand = _deezer_track_to_candidate(item, reason=reason or f"Похоже на {artist_name}")
                if cand:
                    out.append(cand)
                    if len(out) >= limit:
                        return out
    except Exception as e:
        print(f"Deezer artist recommendations note for '{artist_name}': {e}")
    return out


def _itunes_search_candidates(query, limit=12, reason=""):
    out = []
    try:
        encoded = urllib.parse.quote_plus(query)
        url = f"https://itunes.apple.com/search?term={encoded}&entity=song&limit={limit}"
        data = _http_get_json(url, timeout=5)
        for item in data.get("results", []):
            title = (item.get("trackName") or "").strip()
            artist = (item.get("artistName") or "").strip()
            album = (item.get("collectionName") or "").strip()
            art = (item.get("artworkUrl100") or "").replace("100x100bb", "1000x1000bb")
            dur_ms = int(item.get("trackTimeMillis") or 180000)
            if title and artist and 60000 <= dur_ms <= 600000 and not _is_title_banned(title):
                out.append({
                    "title": title,
                    "artist": artist,
                    "album": album,
                    "cover_url": art,
                    "duration_ms": dur_ms,
                    "reason": reason,
                })
    except Exception as e:
        print(f"iTunes fallback note: {e}")
    return out


def _fetch_innertube_search(query, limit=15, reason=""):
    """Поиск Innertube."""
    if not query:
        return []
    try:
        url = "https://music.youtube.com/youtubei/v1/search"
        payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20261108.01.00",
                    "hl": "ru",
                    "gl": "RU"
                }
            },
            "query": query,
            "params": "EgWKAQIIAWoQEAMQBBAJEA4QChAFEBEQEg%3D%3D"
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "User-Agent": USER_AGENT_WEB,
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8"
            }
        )
        with urllib.request.urlopen(req, timeout=4.0) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="ignore"))

        def find_renderers(obj):
            if isinstance(obj, dict):
                if "musicResponsiveListItemRenderer" in obj:
                    yield obj["musicResponsiveListItemRenderer"]
                for v in obj.values():
                    yield from find_renderers(v)
            elif isinstance(obj, list):
                for item in obj:
                    yield from find_renderers(item)

        out = []
        for r in find_renderers(data):
            vid = r.get("playlistItemData", {}).get("videoId", "")
            cols = r.get("flexColumns", [])
            if not cols:
                continue
            t_runs = cols[0].get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs", [])
            title = t_runs[0].get("text", "").strip() if t_runs else ""
            a_runs = cols[1].get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs", []) if len(cols) > 1 else []
            artist = a_runs[0].get("text", "").strip() if a_runs else ""
            album = a_runs[2].get("text", "").strip() if len(a_runs) > 2 else ""

            dur_text = ""
            for c in r.get("fixedColumns", []):
                f_runs = c.get("musicResponsiveListItemFixedColumnRenderer", {}).get("text", {}).get("runs", [])
                if f_runs:
                    dur_text = f_runs[0].get("text", "")
                    break
            dur_sec = _parse_time_str_to_sec(dur_text)
            if not dur_sec and len(a_runs) > 4:
                dur_sec = _parse_time_str_to_sec(a_runs[4].get("text", ""))

            # фильтр длительности
            if dur_sec > 600 or (dur_sec > 0 and dur_sec < 55):
                continue
            if _is_title_banned(title):
                continue

            thumbs = r.get("thumbnail", {}).get("musicThumbnailRenderer", {}).get("thumbnail", {}).get("thumbnails", [])
            thumb = thumbs[-1].get("url", "") if thumbs else (f"https://img.youtube.com/vi/{vid}/hqdefault.jpg" if vid else "")
            dur_ms = dur_sec * 1000 if dur_sec > 0 else 180000

            if title and (artist or vid):
                out.append({
                    "title": title,
                    "artist": artist,
                    "album": album,
                    "cover_url": thumb,
                    "duration_ms": dur_ms,
                    "video_id": vid,
                    "reason": reason or f"Поиск {query}",
                })
                if len(out) >= limit:
                    break
        return out
    except Exception as e:
        print(f"Innertube search note for '{query}': {e}")
        return []


def _fetch_innertube_radio(seed_video_id, limit=20):
    """Радио Innertube."""
    if not seed_video_id or len(seed_video_id) != 11:
        return []
    try:
        url = "https://music.youtube.com/youtubei/v1/next"
        payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20261108.01.00",
                    "hl": "ru",
                    "gl": "RU"
                }
            },
            "videoId": seed_video_id,
            "playlistId": f"RDAMVM{seed_video_id}",
            "isAudioOnly": True
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "User-Agent": USER_AGENT_WEB,
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8"
            }
        )
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="ignore"))

        tab_list = (data.get("contents", {})
                    .get("singleColumnMusicWatchNextResultsRenderer", {})
                    .get("tabbedRenderer", {})
                    .get("watchNextTabbedResultsRenderer", {})
                    .get("tabs", [{}]))
        queue_items = (tab_list[0].get("tabRenderer", {})
                       .get("content", {})
                       .get("musicQueueRenderer", {})
                       .get("content", {})
                       .get("playlistPanelRenderer", {})
                       .get("contents", []))

        candidates = []
        for q in queue_items:
            item = q.get("playlistPanelVideoRenderer")
            if not item:
                continue
            vid = item.get("videoId", "")
            title_runs = item.get("title", {}).get("runs", [])
            t_title = title_runs[0].get("text", "").strip() if title_runs else ""
            byline_runs = item.get("longBylineText", {}).get("runs", []) or item.get("shortBylineText", {}).get("runs", [])
            t_artist = byline_runs[0].get("text", "").strip() if byline_runs else ""
            thumbs = item.get("thumbnail", {}).get("thumbnails", [])
            t_thumb = thumbs[-1].get("url", "") if thumbs else (f"https://img.youtube.com/vi/{vid}/hqdefault.jpg" if vid else "")

            if not t_title or not t_artist or not vid:
                continue

            # парсинг длительности
            length_runs = item.get("lengthText", {}).get("runs", [])
            dur_text = length_runs[0].get("text", "") if length_runs else item.get("lengthText", {}).get("simpleText", "")
            dur_sec = _parse_time_str_to_sec(dur_text)

            # фильтр подкастов
            if dur_sec > 600 or (dur_sec > 0 and dur_sec < 60):
                continue
            if _is_title_banned(t_title):
                continue

            dur_ms = dur_sec * 1000 if dur_sec > 0 else 180000

            candidates.append({
                "title": t_title,
                "artist": t_artist,
                "album": "",
                "cover_url": t_thumb,
                "duration_ms": dur_ms,
                "video_id": vid,
                "reason": "Радио трека",
            })
            if len(candidates) >= limit:
                break
        return candidates
    except Exception as e:
        print(f"Innertube radio note for {seed_video_id}: {e}")
        return []


def fetch_wave_candidates(
    seed_artists_json="[]",
    favorite_artists_json="[]",
    banned_json="[]",
    seen_json="[]",
    character="Любимое",
    mood="Бодрое",
    limit=14,
    session_feedback_json="[]",
    last_positive_track_json="{}",
    language="Все"
):
    """Пайплайн волны."""
    try:
        seed_artists = [_clean_artist_name(a) for a in json.loads(seed_artists_json or "[]")]
        seed_artists = [a for a in seed_artists if a]
    except Exception:
        seed_artists = []

    try:
        fav_artists = [_clean_artist_name(a) for a in json.loads(favorite_artists_json or "[]")]
        fav_artists = [a for a in fav_artists if a]
    except Exception:
        fav_artists = []

    try:
        banned_list = [str(x).lower().strip() for x in json.loads(banned_json or "[]") if x]
    except Exception:
        banned_list = []

    try:
        seen_keys = set(json.loads(seen_json or "[]"))
    except Exception:
        seen_keys = set()

    try:
        session_feedback = json.loads(session_feedback_json or "[]")
    except Exception:
        session_feedback = []

    try:
        last_positive = json.loads(last_positive_track_json or "{}")
    except Exception:
        last_positive = {}

    banned_set = set(banned_list)

    # нормализация настроек
    eff_character = CHARACTER_MAP.get(str(character).lower().strip(), "Любимое")
    eff_mood_req = MOOD_MAP.get(str(mood).lower().strip(), "Бодрое")

    # чистка кэша
    if len(seen_keys) > 120:
        seen_keys = set(list(seen_keys)[-50:])

    # бан скипов
    # буст лайков
    reward_artists = []
    for fb in session_feedback:
        art_clean = _clean_artist_name(fb.get("artist", "")).lower()
        if not art_clean:
            continue
        score = float(fb.get("score", 0.0))
        if score <= -2.0:
            banned_set.add(art_clean)
        elif score >= 2.0 and art_clean not in banned_set and art_clean not in reward_artists:
            reward_artists.append(art_clean)

    known_artist_low = {a.lower() for a in (seed_artists + fav_artists + reward_artists)}

    # сортировка сидов
    pool = []
    for r in reward_artists:
        if r not in banned_set:
            pool.append(r)
    for f in fav_artists:
        if f.lower() not in banned_set and f.lower() not in pool:
            pool.append(f)
    for s in seed_artists:
        if s.lower() not in banned_set and s.lower() not in pool:
            pool.append(s)

    # фильтр языка
    lang_norm = str(language).lower().strip()
    is_russian_only = lang_norm in ("русские", "рус", "ru", "russian")
    is_english_only = lang_norm in ("английские", "англ", "en", "english")
    is_instrumental_only = lang_norm in ("без слов", "инструментал", "instrumental", "beats", "inst")

    # учет времени
    hour = time.localtime().tm_hour
    time_mood = "Бодрое" if 6 <= hour < 12 else ("Бодрое" if 12 <= hour < 18 else ("Спокойное" if 18 <= hour < 23 else "Мрачное"))
    effective_mood = eff_mood_req if eff_mood_req in MOOD_QUERIES_YANDEX else time_mood

    picked_seeds = pool[:5]
    if not picked_seeds:
        curated = DEFAULT_SEEDS.get(effective_mood, DEFAULT_SEEDS["Бодрое"])[:]
        picked_seeds = [a for a in curated if a.lower() not in banned_set][:6]

    raw_candidates = []
    mood_terms = MOOD_QUERIES_YANDEX.get(effective_mood, MOOD_QUERIES_YANDEX["Бодрое"])[:]

    # запросы поиска
    if is_instrumental_only:
        mood_terms = [
            "phonk drift instrumental no vocal",
            "lofi hip hop beats instrumental",
            "chillhop instrumental beats",
            "synthwave retrowave instrumental",
            "ambient chill beats no lyrics",
            "guitar instrumental relax",
            "electronic instrumental music",
            "piano relax instrumental"
        ]
        picked_seeds = ["DVRST", "Kordhell", "Hensonn", "Interworld", "C418", "HOME", "Ludovico Einaudi", "Hans Zimmer"]
    elif is_english_only:
        en_terms = [t for t in mood_terms if not re.search(r"[\u0400-\u04FF]", t)]
        if en_terms:
            mood_terms = en_terms
        picked_seeds = [a for a in picked_seeds if not re.search(r"[\u0400-\u04FF]", a)]
        if not picked_seeds:
            picked_seeds = ["The Weeknd", "Daft Punk", "Skrillex", "Travis Scott", "Fred again.."]
    elif is_russian_only:
        ru_terms = [t for t in mood_terms if re.search(r"[\u0400-\u04FF]", t)]
        if ru_terms:
            mood_terms = ru_terms

    random.shuffle(mood_terms)

    # параллельные запросы
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        futures = []

        # треки Яндекс
        last_yandex_id = str(last_positive.get("yandex_id", "")).strip()
        if last_yandex_id:
            futures.append(executor.submit(_yandex_similar_tracks, last_yandex_id, 12, "Похоже на прошлый трек"))

        # микс Innertube
        last_vid = str(last_positive.get("video_id", "")).strip()
        if last_vid:
            futures.append(executor.submit(_fetch_innertube_radio, last_vid, 16))

        # поиск Innertube
        artist_suffix = "песни" if is_russian_only else ("instrumental" if is_instrumental_only else "songs")
        for a in picked_seeds[:3]:
            futures.append(executor.submit(_fetch_innertube_search, f"{a} {artist_suffix}", 8, f"YouTube {a}"))

        if is_instrumental_only:
            for a in picked_seeds[:3]:
                futures.append(executor.submit(_yandex_search_candidates, f"{a} instrumental", 8, "Инструментал"))
        else:
            # артисты Яндекс
            for a in picked_seeds:
                futures.append(executor.submit(_yandex_artist_recommendations, a, eff_character, banned_set, known_artist_low))

            # каталог Deezer
            for a in picked_seeds[:2]:
                futures.append(executor.submit(_deezer_artist_recommendations, a, 8, f"Deezer Radio {a}"))

        # поиск настроения
        if mood_terms:
            futures.append(executor.submit(_fetch_innertube_search, mood_terms[0], 10, effective_mood))
        for mterm in mood_terms[:2]:
            futures.append(executor.submit(_yandex_search_candidates, mterm, 8, effective_mood))

        # чарты трендов
        if not is_instrumental_only and (eff_character == "Популярное" or len(picked_seeds) < 3):
            futures.append(executor.submit(_yandex_chart_candidates, 16))

        # ожидание потоков
        for f in concurrent.futures.as_completed(futures, timeout=5.5):
            try:
                res = f.result()
                if res:
                    raw_candidates.extend(res)
            except Exception:
                pass

    # запасной поиск
    if len(raw_candidates) < 8:
        fallback_queries = []
        if picked_seeds:
            fallback_queries.extend(picked_seeds[:2])
        if mood_terms:
            fallback_queries.extend(mood_terms[:2])
        for fq in fallback_queries:
            yt_res = _fetch_innertube_search(fq, limit=8, reason=effective_mood)
            if yt_res:
                raw_candidates.extend(yt_res)
            raw_candidates.extend(_itunes_search_candidates(fq, limit=8, reason=effective_mood))

    # ранжирование треков
    def _candidate_score(cand):
        s = 0.0
        art = cand.get("artist", "").lower().strip()
        dur = int(cand.get("duration_ms") or 180000)
        # фильтр длительности
        if dur < 60000 or dur > 600000:
            return -999.0
        if _is_title_banned(cand.get("title", "")):
            return -999.0
        # любимый артист
        if any(f.lower() in art for f in fav_artists if len(f) > 2):
            s += 6.0
        # сид библиотеки
        if any(sd.lower() in art for sd in seed_artists if len(sd) > 2):
            s += 3.5
        # бонус сессии
        if art in reward_artists:
            s += 5.5
        # радио трека
        if cand.get("reason") == "Радио трека":
            s += 4.0
        # случайный разброс
        s += random.uniform(-0.6, 0.6)
        return s

    raw_candidates.sort(key=_candidate_score, reverse=True)
    filtered = []
    artist_counts = {}

    for c in raw_candidates:
        dur = int(c.get("duration_ms") or 180000)
        if dur < 60000 or dur > 600000:
            continue
        if _is_title_banned(c.get("title", "")):
            continue
        a_low = c["artist"].lower().strip()
        t_low = c["title"].lower().strip()
        key = _norm_key(c["artist"], c["title"])
        reason_low = (c.get("reason") or "").lower()

        if not key or key in seen_keys:
            continue

        # черный список
        if a_low in banned_set or key in banned_set or f"{a_low} - {t_low}" in banned_set:
            continue
        is_banned_similar = False
        for b in banned_set:
            if len(b) > 2 and b in reason_low:
                is_banned_similar = True
                break
        if is_banned_similar:
            continue

        # фильтр инструментала
        has_cyrillic = bool(re.search(r"[\u0400-\u04FF]", t_low + " " + a_low))
        if is_russian_only and not has_cyrillic and len(raw_candidates) > limit:
            continue
        if is_english_only and has_cyrillic:
            continue
        if is_instrumental_only:
            vocal_pattern = r"(?i)\b(vocal|vocals|feat|feat\.|ft|ft\.|lyrics|lyric|текст|текст\s+песни|клип|voice|singing|song|remix\s+feat|official\s+video|official\s+music\s+video|acapella|cover|duet)\b"
            if re.search(vocal_pattern, t_low):
                continue
            inst_pattern = r"(?i)(instrumental|inst\b|beat\b|beats\b|phonk|synthwave|ambient|lofi|lo-fi|chillhop|soundtrack|ost\b|score\b|piano\b|orchestral|guitar\b|no\s+vocal|no\s+lyrics|без\s+слов|инструментал|минус\b|караоке|bgm\b|theme\b|slowed|acoustic)"
            full_meta = f"{t_low} {a_low} {reason_low}"
            if not re.search(inst_pattern, full_meta):
                continue

        if eff_character == "Незнакомое" and a_low in known_artist_low and len(raw_candidates) > limit:
            continue

        # лимит артиста
        if artist_counts.get(a_low, 0) >= 2:
            continue

        seen_keys.add(key)
        artist_counts[a_low] = artist_counts.get(a_low, 0) + 1
        c["key"] = key
        filtered.append(c)
        if len(filtered) >= int(limit):
            break

    return json.dumps(filtered, ensure_ascii=False)


def _pick_best_audio_entry(entries, expected_dur_sec, artist="", title=""):
    best = None
    best_score = -999999.0
    for e in entries:
        if not e:
            continue
        e_title = (e.get("title") or "").strip()
        e_uploader = (e.get("uploader") or "").strip()
        e_dur = float(e.get("duration") or 0)

        # границы длительности
        if e_dur > 600 or (e_dur > 0 and e_dur < 50):
            continue

        # стоп-слова
        if _is_title_banned(e_title):
            continue

        score = 0.0

        # сверка длительности
        if expected_dur_sec > 0 and e_dur > 0:
            diff = abs(e_dur - expected_dur_sec)
            if diff <= 6:
                score += 80.0
            elif diff <= 15:
                score += 50.0
            elif diff <= 30:
                score += 25.0
            elif diff > 90:
                score -= 60.0

        # официальный канал
        if e_uploader.endswith("- Topic"):
            score += 45.0
        elif "official audio" in e_title.lower():
            score += 35.0
        elif "audio" in e_title.lower():
            score += 20.0
        elif "official music video" in e_title.lower() or "official video" in e_title.lower():
            score += 15.0

        # штраф лайвов
        if "live" in e_title.lower():
            score -= 30.0
        if "mix" in e_title.lower():
            score -= 35.0

        if score > best_score:
            best_score = score
            best = e

    return best


def resolve_wave_stream(artist, title, cover_url="", duration_ms=0, video_id=""):
    """Стрим трека."""
    key = _norm_key(artist, title)
    now = time.time()

    # кэш ссылки
    if key in _STREAM_CACHE:
        cached_obj, exp = _STREAM_CACHE[key]
        if now < exp:
            return json.dumps(cached_obj, ensure_ascii=False)

    expected_dur_sec = float(duration_ms) / 1000.0 if duration_ms and duration_ms > 0 else 0

    opts = {
        "quiet": True,
        "no_warnings": True,
        "nocheckcertificate": True,
        "noplaylist": True,
        "socket_timeout": 7,
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "extractor_args": {
            "youtube": {
                "player_client": ["android", "ios"],
            }
        },
    }

    query = f"{artist} - {title} audio".strip(" -")
    search_target = f"ytsearch5:{query}"
    chosen = None

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            # проверка video_id
            if video_id and len(video_id) == 11:
                try:
                    direct_info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)
                    if direct_info:
                        d_dur = float(direct_info.get("duration") or 0)
                        d_title = direct_info.get("title") or ""
                        # проверка названия
                        if 50 <= d_dur <= 600 and not _is_title_banned(d_title):
                            chosen = direct_info
                except Exception as ex:
                    print(f"Direct video_id check note: {ex}")
                    chosen = None

            # поиск yt-dlp
            if not chosen:
                try:
                    search_info = ydl.extract_info(search_target, download=False)
                    entries = (search_info.get("entries") or []) if (search_info and "entries" in search_info) else ([search_info] if search_info else [])
                    chosen = _pick_best_audio_entry(entries, expected_dur_sec, artist=artist, title=title)
                except Exception as s_err:
                    print(f"ytsearch note: {s_err}")
                    chosen = None

            # скрейпинг YouTube
            if not chosen:
                try:
                    scrape_list = _search_youtube_scrape(query, max_results=4)
                    for sc in scrape_list:
                        svid = sc.get("video_id")
                        if svid and len(svid) == 11:
                            try:
                                s_info = ydl.extract_info(f"https://www.youtube.com/watch?v={svid}", download=False)
                                if s_info:
                                    d_dur = float(s_info.get("duration") or 0)
                                    d_title = s_info.get("title") or ""
                                    if 50 <= d_dur <= 600 and not _is_title_banned(d_title):
                                        chosen = s_info
                                        break
                            except Exception:
                                pass
                except Exception as sc_err:
                    print(f"Scrape fallback note: {sc_err}")

            if not chosen:
                return json.dumps({"status": "error", "message": "Valid song stream not found (filtered out long video/podcast)"})

            stream_url = chosen.get("url") or ""
            resolved_vid = chosen.get("id") or video_id or ""

            # извлечение ссылки
            
            if not stream_url or "googlevideo.com" not in stream_url:
                target_vid = resolved_vid or video_id
                if target_vid:
                    try:
                        single_info = ydl.extract_info(f"https://www.youtube.com/watch?v={target_vid}", download=False)
                        if single_info:
                            cand_url = single_info.get("url") or ""
                            if cand_url:
                                stream_url = cand_url
                                chosen = single_info
                    except Exception as s_ex:
                        print(f"Direct video stream extract note for {target_vid}: {s_ex}")

            if not stream_url:
                return json.dumps({"status": "error", "message": "Failed to extract direct audio stream"})

            headers = chosen.get("http_headers") or {}
            ua = headers.get("User-Agent") or USER_AGENT_WEB
            actual_dur_ms = int(float(chosen.get("duration") or 0) * 1000) or int(duration_ms or 180000)
            final_cover = cover_url or (
                f"https://img.youtube.com/vi/{resolved_vid}/hqdefault.jpg" if resolved_vid else (chosen.get("thumbnail") or "")
            )

            res_dict = {
                "status": "success",
                "stream_url": stream_url,
                "video_id": resolved_vid,
                "source_url": f"https://www.youtube.com/watch?v={resolved_vid}" if resolved_vid else query,
                "title": title or chosen.get("title") or "Unknown",
                "artist": artist or chosen.get("uploader") or "Unknown",
                "cover_url": final_cover,
                "duration_ms": actual_dur_ms,
                "user_agent": ua,
            }

            # сохранение кэша
            _STREAM_CACHE[key] = (res_dict, now + 14400)
            return json.dumps(res_dict, ensure_ascii=False)
    except Exception as e:
        print(f"Resolve stream error for '{query}': {e}")
        return json.dumps({"status": "error", "message": str(e)})
