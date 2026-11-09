# -*- coding: utf-8 -*-
import json
import os
import re
import shutil
import time
import urllib.parse
import urllib.request

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def _sanitize_filename(name):
    cleaned = re.sub(r'[\\/:*?"<>|]+', "_", (name or "").strip())
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return cleaned or f"Track_{int(time.time())}"


def read_track_metadata(filepath):
    """Чтение тегов файла."""
    if not filepath or not os.path.exists(filepath):
        return json.dumps({"status": "error", "message": "Файл не найден"})

    base, ext = os.path.splitext(filepath)
    ext_low = ext.lower()
    filename_no_ext = os.path.basename(base)

    title = ""
    artist = ""
    album = ""
    lyrics = ""

    # файл текста
    lrc_path = base + ".lrc"
    if os.path.exists(lrc_path):
        try:
            with open(lrc_path, "r", encoding="utf-8", errors="ignore") as f:
                lyrics = f.read()
        except Exception:
            pass

    # теги mutagen
    try:
        if ext_low == ".mp3":
            from mutagen.id3 import ID3
            from mutagen.mp3 import MP3

            audio = MP3(filepath, ID3=ID3)
            if audio.tags:
                if "TIT2" in audio.tags:
                    title = str(audio.tags["TIT2"])
                if "TPE1" in audio.tags:
                    artist = str(audio.tags["TPE1"])
                if "TALB" in audio.tags:
                    album = str(audio.tags["TALB"])
                if not lyrics:
                    uslt_frames = audio.tags.getall("USLT")
                    if uslt_frames:
                        lyrics = str(uslt_frames[0].text)
        elif ext_low in (".m4a", ".mp4", ".aac"):
            from mutagen.mp4 import MP4

            audio = MP4(filepath)
            if audio.tags:
                title = (audio.tags.get("\xa9nam") or [""])[0]
                artist = (audio.tags.get("\xa9ART") or [""])[0]
                album = (audio.tags.get("\xa9alb") or [""])[0]
                if not lyrics:
                    lyrics = (audio.tags.get("\xa9lyr") or [""])[0]
        else:
            import mutagen

            audio = mutagen.File(filepath, easy=True)
            if audio and audio.tags:
                title = (audio.tags.get("title") or [""])[0]
                artist = (audio.tags.get("artist") or [""])[0]
                album = (audio.tags.get("album") or [""])[0]
    except Exception as e:
        print(f"read_track_metadata note: {e}")

    return json.dumps(
        {
            "status": "success",
            "title": title,
            "artist": artist,
            "album": album,
            "lyrics": lyrics,
            "filename": filename_no_ext,
            "extension": ext,
        },
        ensure_ascii=False,
    )


def search_online_metadata(title, artist, cache_dir):
    """Поиск обложки и текста."""
    query = f"{artist} {title}".strip()
    if not query:
        return json.dumps({"status": "error", "message": "Введите название или исполнителя"})

    if not os.path.exists(cache_dir):
        os.makedirs(cache_dir, exist_ok=True)

    found_title = title or ""
    found_artist = artist or ""
    found_album = ""
    cover_url = ""
    cover_local_path = ""
    found_lyrics = ""

    # обложка Deezer
    try:
        q = urllib.parse.quote_plus(query)
        req = urllib.request.Request(
            f"https://api.deezer.com/search?q={q}&limit=3",
            headers={"User-Agent": USER_AGENT},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="ignore"))
            items = data.get("data") or []
            if items:
                best = items[0]
                found_title = best.get("title") or found_title
                found_artist = (best.get("artist") or {}).get("name") or found_artist
                album_obj = best.get("album") or {}
                found_album = album_obj.get("title") or ""
                cover_url = (
                    album_obj.get("cover_xl")
                    or album_obj.get("cover_big")
                    or album_obj.get("cover_medium")
                    or ""
                )
    except Exception as e:
        print(f"Deezer metadata lookup error: {e}")

    # обложка iTunes
    if not cover_url:
        try:
            q = urllib.parse.quote_plus(query)
            req = urllib.request.Request(
                f"https://itunes.apple.com/search?term={q}&entity=song&limit=3&country=RU",
                headers={"User-Agent": USER_AGENT},
            )
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="ignore"))
                results = data.get("results") or []
                if results:
                    best = results[0]
                    found_title = best.get("trackName") or found_title
                    found_artist = best.get("artistName") or found_artist
                    found_album = best.get("collectionName") or found_album
                    art100 = best.get("artworkUrl100") or ""
                    if art100:
                        cover_url = art100.replace("100x100bb", "1000x1000bb")
        except Exception as e:
            print(f"iTunes metadata lookup error: {e}")

    if cover_url:
        try:
            preview_path = os.path.join(cache_dir, f"fetched_cover_{int(time.time() * 1000)}.jpg")
            req = urllib.request.Request(cover_url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=10) as resp, open(preview_path, "wb") as out:
                out.write(resp.read())
            if os.path.exists(preview_path) and os.path.getsize(preview_path) > 512:
                cover_local_path = preview_path
        except Exception as e:
            print(f"Cover download error: {e}")

    # текст LRCLIB
    try:
        lq = urllib.parse.quote_plus(f"{found_artist} {found_title}".strip())
        req = urllib.request.Request(
            f"https://lrclib.net/api/search?q={lq}",
            headers={"User-Agent": "MuzixPlayer/0.8 (https://muzix.app)"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            lrc_items = json.loads(resp.read().decode("utf-8", errors="ignore"))
            if isinstance(lrc_items, list) and lrc_items:
                synced = next((x.get("syncedLyrics") for x in lrc_items if x.get("syncedLyrics")), None)
                plain = next((x.get("plainLyrics") for x in lrc_items if x.get("plainLyrics")), None)
                found_lyrics = synced or plain or ""
    except Exception as e:
        print(f"LRCLIB lookup error: {e}")

    return json.dumps(
        {
            "status": "success",
            "title": found_title,
            "artist": found_artist,
            "album": found_album,
            "cover_path": cover_local_path,
            "lyrics": found_lyrics,
        },
        ensure_ascii=False,
    )


def save_track_metadata(
    filepath,
    title="",
    artist="",
    album="",
    lyrics=None,
    new_cover_path="",
    new_filename="",
):
    """Запись тегов в файл."""
    if not filepath or not os.path.exists(filepath):
        return json.dumps({"status": "error", "message": "Аудиофайл не найден на устройстве"})

    parent_dir = os.path.dirname(filepath)
    old_base_no_ext, ext = os.path.splitext(os.path.basename(filepath))
    ext_low = ext.lower()

    has_new_cover = bool(new_cover_path and os.path.exists(new_cover_path))
    img_bytes = None
    mime_type = "image/jpeg"
    if has_new_cover:
        with open(new_cover_path, "rb") as f:
            img_bytes = f.read()
        if img_bytes[:8] == b"\x89PNG\r\n\x1a\n":
            mime_type = "image/png"

    # запись в файл
    tag_error = None
    try:
        if ext_low == ".mp3":
            from mutagen.id3 import APIC, ID3, TALB, TIT2, TPE1, USLT, error
            from mutagen.mp3 import MP3

            audio = MP3(filepath, ID3=ID3)
            try:
                audio.add_tags()
            except error:
                pass

            if title is not None:
                audio.tags.delall("TIT2")
                audio.tags.add(TIT2(encoding=3, text=str(title)))
            if artist is not None:
                audio.tags.delall("TPE1")
                audio.tags.add(TPE1(encoding=3, text=str(artist)))
            if album is not None:
                audio.tags.delall("TALB")
                audio.tags.add(TALB(encoding=3, text=str(album)))
            if lyrics is not None:
                audio.tags.delall("USLT")
                if str(lyrics).strip():
                    audio.tags.add(USLT(encoding=3, lang="rus", desc="", text=str(lyrics)))
            if img_bytes:
                audio.tags.delall("APIC")
                audio.tags.add(
                    APIC(
                        encoding=3,
                        mime=mime_type,
                        type=3,
                        desc="Cover",
                        data=img_bytes,
                    )
                )
            audio.save(v2_version=3)

        elif ext_low in (".m4a", ".mp4"):
            from mutagen.mp4 import MP4, MP4Cover

            audio = MP4(filepath)
            if audio.tags is None:
                audio.add_tags()
            if title is not None:
                audio["\xa9nam"] = [str(title)]
            if artist is not None:
                audio["\xa9ART"] = [str(artist)]
            if album is not None:
                audio["\xa9alb"] = [str(album)]
            if lyrics is not None:
                if str(lyrics).strip():
                    audio["\xa9lyr"] = [str(lyrics)]
                elif "\xa9lyr" in audio:
                    del audio["\xa9lyr"]
            if img_bytes:
                fmt = MP4Cover.FORMAT_PNG if mime_type == "image/png" else MP4Cover.FORMAT_JPEG
                audio["covr"] = [MP4Cover(img_bytes, imageformat=fmt)]
            audio.save()

        elif ext_low == ".flac":
            from mutagen.flac import FLAC, Picture

            audio = FLAC(filepath)
            if title is not None:
                audio["title"] = [str(title)]
            if artist is not None:
                audio["artist"] = [str(artist)]
            if album is not None:
                audio["album"] = [str(album)]
            if lyrics is not None:
                if str(lyrics).strip():
                    audio["lyrics"] = [str(lyrics)]
                elif "lyrics" in audio:
                    del audio["lyrics"]
            if img_bytes:
                audio.clear_pictures()
                pic = Picture()
                pic.type = 3
                pic.mime = mime_type
                pic.desc = "Cover"
                pic.data = img_bytes
                audio.add_picture(pic)
            audio.save()
        else:
            import mutagen

            audio = mutagen.File(filepath, easy=True)
            if audio is not None:
                if audio.tags is None:
                    audio.add_tags()
                if title is not None:
                    audio["title"] = [str(title)]
                if artist is not None:
                    audio["artist"] = [str(artist)]
                if album is not None:
                    audio["album"] = [str(album)]
                audio.save()
    except Exception as e:
        tag_error = str(e)
        print(f"Container tag write warning ({ext_low}): {e}")

    # переименование файла
    final_filepath = filepath
    desired_base = _sanitize_filename(new_filename) if new_filename and str(new_filename).strip() else old_base_no_ext
    old_sidecar_jpg = os.path.join(parent_dir, old_base_no_ext + ".jpg")
    old_sidecar_lrc = os.path.join(parent_dir, old_base_no_ext + ".lrc")

    if desired_base != old_base_no_ext:
        candidate_path = os.path.join(parent_dir, desired_base + ext)
        if os.path.exists(candidate_path) and os.path.abspath(candidate_path) != os.path.abspath(filepath):
            desired_base = f"{desired_base}_{int(time.time()) % 1000}"
            candidate_path = os.path.join(parent_dir, desired_base + ext)
        try:
            os.rename(filepath, candidate_path)
            final_filepath = candidate_path
            if os.path.exists(old_sidecar_jpg):
                new_jpg = os.path.join(parent_dir, desired_base + ".jpg")
                shutil.move(old_sidecar_jpg, new_jpg)
            if os.path.exists(old_sidecar_lrc):
                new_lrc = os.path.join(parent_dir, desired_base + ".lrc")
                shutil.move(old_sidecar_lrc, new_lrc)
        except Exception as re_err:
            print(f"File rename warning: {re_err}")

    final_base_no_ext = os.path.splitext(os.path.basename(final_filepath))[0]
    final_sidecar_jpg = os.path.join(parent_dir, final_base_no_ext + ".jpg")
    final_sidecar_lrc = os.path.join(parent_dir, final_base_no_ext + ".lrc")

    # файлы рядом
    if has_new_cover:
        try:
            shutil.copyfile(new_cover_path, final_sidecar_jpg)
        except Exception as ce:
            print(f"Sidecar jpg write warning: {ce}")

    if lyrics is not None:
        try:
            if str(lyrics).strip():
                with open(final_sidecar_lrc, "w", encoding="utf-8") as lf:
                    lf.write(str(lyrics))
            elif os.path.exists(final_sidecar_lrc):
                os.remove(final_sidecar_lrc)
        except Exception as le:
            print(f"Sidecar lrc write warning: {le}")

    # сброс кэша Coil
    try:
        now = time.time()
        os.utime(final_filepath, (now, now))
    except Exception:
        pass

    return json.dumps(
        {
            "status": "success",
            "filepath": final_filepath,
            "sidecar_cover": final_sidecar_jpg if os.path.exists(final_sidecar_jpg) else "",
            "warning": tag_error or "",
        },
        ensure_ascii=False,
    )
