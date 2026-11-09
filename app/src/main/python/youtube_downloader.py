# -*- coding: utf-8 -*-
import os
import sys
import json

# директория пакетов
try:
    from android.content import Context
    from com.chaquo.python import Python
    context = Python.getPlatform().getApplication()
    files_dir = context.getFilesDir().getAbsolutePath()
    user_pip_dir = os.path.join(files_dir, "python_packages")
    if not os.path.exists(user_pip_dir):
        os.makedirs(user_pip_dir)
    if user_pip_dir not in sys.path:
        sys.path.insert(0, user_pip_dir)
except Exception as e:
    print(f"Chaquopy runtime updates setup bypassed: {e}")

import urllib.request
import urllib.parse
import re
import json
import yt_dlp

COMMON_YDL_OPTS = {
    'quiet': True,
    'no_warnings': True,
    'nocheckcertificate': True,
    'socket_timeout': 15,
    'extractor_args': {
        'youtube': {
            'player_client': ['android', 'ios'],
        }
    },
    'http_headers': {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept-Language': 'ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7',
    },
}

def _search_youtube_scrape(query, max_results=10):
    """Скрейпинг YouTube."""
    encoded = urllib.parse.quote_plus(query)
    url = f"https://www.youtube.com/results?search_query={encoded}"
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Accept-Language': 'ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
        }
    )
    
    with urllib.request.urlopen(req, timeout=12) as response:
        html = response.read().decode('utf-8', errors='ignore')

    match = re.search(r'var\s+ytInitialData\s*=\s*({.+?});</script>', html)
    if not match:
        match = re.search(r'ytInitialData\s*=\s*({.+?});', html)
    if not match:
        return []

    data = json.loads(match.group(1))
    results = []
    try:
        sections = data['contents']['twoColumnSearchResultsRenderer']['primaryContents']['sectionListRenderer']['contents']
        for section in sections:
            item_section = section.get('itemSectionRenderer', {})
            items = item_section.get('contents', [])
            for item in items:
                video = item.get('videoRenderer')
                if not video:
                    continue
                video_id = video.get('videoId')
                if not video_id:
                    continue

                title = ""
                title_obj = video.get('title', {})
                if 'runs' in title_obj and title_obj['runs']:
                    title = title_obj['runs'][0].get('text', '')
                elif 'simpleText' in title_obj:
                    title = title_obj.get('simpleText', '')

                uploader = "YouTube"
                owner = video.get('ownerText', {}) or video.get('longBylineText', {})
                if 'runs' in owner and owner['runs']:
                    uploader = owner['runs'][0].get('text', '')

                duration_sec = 0.0
                length_text = video.get('lengthText', {}).get('simpleText', '')
                if length_text:
                    parts = length_text.split(':')
                    try:
                        if len(parts) == 2:
                            duration_sec = int(parts[0]) * 60 + int(parts[1])
                        elif len(parts) == 3:
                            duration_sec = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
                    except:
                        pass

                thumb = f"https://img.youtube.com/vi/{video_id}/mqdefault.jpg"
                results.append({
                    'id': video_id,
                    'title': title or 'Unknown Title',
                    'url': f"https://www.youtube.com/watch?v={video_id}",
                    'duration': float(duration_sec),
                    'uploader': uploader,
                    'thumbnail': thumb
                })
                if len(results) >= max_results:
                    return results
    except Exception as e:
        print(f"Error parsing ytInitialData: {e}")

    return results

def _search_invidious_api(query, max_results=10):
    """Поиск Invidious."""
    instances = [
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://vid.priv.au",
        "https://invidious.projectsegfau.lt"
    ]
    encoded = urllib.parse.quote_plus(query)
    for base in instances:
        try:
            url = f"{base}/api/v1/search?q={encoded}&type=video"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=7) as response:
                if response.status == 200:
                    items = json.loads(response.read().decode('utf-8'))
                    results = []
                    for item in items:
                        if item.get('type') != 'video':
                            continue
                        video_id = item.get('videoId')
                        if not video_id:
                            continue
                        results.append({
                            'id': video_id,
                            'title': item.get('title', 'Unknown Title'),
                            'url': f"https://www.youtube.com/watch?v={video_id}",
                            'duration': float(item.get('lengthSeconds', 0.0) or 0.0),
                            'uploader': item.get('author', 'Unknown Artist'),
                            'thumbnail': f"https://img.youtube.com/vi/{video_id}/mqdefault.jpg"
                        })
                        if len(results) >= max_results:
                            break
                    if results:
                        return results
        except Exception as e:
            print(f"Invidious instance {base} fallback error: {e}")
            continue
    return []

def search_audio(query, max_results=10):
    """Поиск треков YouTube."""
    query_str = str(query).strip()
    if not query_str:
        return json.dumps([])

    max_count = int(max_results) if max_results else 10
    max_count = max(1, min(max_count, 30))

    results = []
    last_error = ""

    # поиск yt-dlp
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'nocheckcertificate': True,
        'extract_flat': True,
        'skip_download': True,
        'socket_timeout': 15,
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Accept-Language': 'ru,en;q=0.9',
        },
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            result = ydl.extract_info(f"ytsearch{max_count}:{query_str}", download=False)
            entries = result.get('entries', []) if result else []
            for entry in entries:
                if entry:
                    video_id = entry.get('id', '')
                    if not video_id and entry.get('url'):
                        video_id = entry.get('url', '').replace('https://www.youtube.com/watch?v=', '')
                    if video_id:
                        results.append({
                            'id': video_id,
                            'title': entry.get('title', 'Unknown Title'),
                            'url': f"https://www.youtube.com/watch?v={video_id}",
                            'duration': float(entry.get('duration', 0.0) or 0.0),
                            'uploader': entry.get('uploader', 'Unknown Artist'),
                            'thumbnail': entry.get('thumbnail') or f"https://img.youtube.com/vi/{video_id}/mqdefault.jpg",
                        })
    except Exception as e:
        last_error = str(e)
        print(f"yt-dlp search error: {e}")

    # прямой скрейпинг
    if not results:
        try:
            fallback_res = _search_youtube_scrape(query_str, max_count)
            if fallback_res:
                results = fallback_res
        except Exception as se:
            print(f"Scrape fallback error: {se}")
            if not last_error:
                last_error = str(se)

    # поиск invidious
    if not results:
        try:
            invidious_res = _search_invidious_api(query_str, max_count)
            if invidious_res:
                results = invidious_res
        except Exception as ie:
            print(f"Invidious API fallback error: {ie}")
            if not last_error:
                last_error = str(ie)

    if not results and last_error:
        return json.dumps({
            'error': last_error,
            'results': []
        })

    return json.dumps(results)

def get_audio_info(target, is_search=False):
    """Метаданные трека."""
    ydl_opts = dict(COMMON_YDL_OPTS)
    ydl_opts.update({
        'extract_flat': False,
        'skip_download': True,
    })
    search_query = f"ytsearch1:{target}" if is_search else target
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(search_query, download=False)
            if not info:
                return json.dumps({'status': 'error', 'message': 'Трек не найден'})
            if 'entries' in info and info['entries']:
                video_info = info['entries'][0]
            else:
                video_info = info
                
            video_id = video_info.get('id', '')
            return json.dumps({
                'status': 'success',
                'title': video_info.get('title', 'Unknown Title'),
                'uploader': video_info.get('uploader', 'Unknown Artist'),
                'thumbnail': f"https://img.youtube.com/vi/{video_id}/mqdefault.jpg" if video_id else video_info.get('thumbnail', ''),
                'duration': video_info.get('duration', 0.0) or 0.0
            })
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'message': str(e)
        })

def embed_cover_art(audio_filepath, image_filepath, title=None, artist=None):
    """Вшивание обложки."""
    if not os.path.exists(audio_filepath):
        return False
        
    has_img = image_filepath and os.path.exists(image_filepath)
    if has_img:
        try:
            sidecar_cover = os.path.splitext(audio_filepath)[0] + ".jpg"
            if not os.path.exists(sidecar_cover):
                import shutil
                shutil.copyfile(image_filepath, sidecar_cover)
        except Exception as se:
            print(f"Sidecar cover copy note: {se}")

    ext = os.path.splitext(audio_filepath)[1].lower()
    try:
        if ext == '.mp3':
            from mutagen.mp3 import MP3
            from mutagen.id3 import ID3, APIC, TIT2, TPE1, TALB, error
            audio = MP3(audio_filepath, ID3=ID3)
            try:
                audio.add_tags()
            except error:
                pass
            if title:
                audio.tags.add(TIT2(encoding=3, text=str(title)))
            if artist:
                audio.tags.add(TPE1(encoding=3, text=str(artist)))
            audio.tags.add(TALB(encoding=3, text=u"Muzix Downloads"))
            if has_img:
                with open(image_filepath, 'rb') as f:
                    img_data = f.read()
                audio.tags.add(
                    APIC(
                        encoding=3,
                        mime='image/jpeg',
                        type=3,
                        desc=u'Cover',
                        data=img_data
                    )
                )
            audio.save()
            return True
        elif ext in ('.m4a', '.mp4'):
            from mutagen.mp4 import MP4, MP4Cover
            audio = MP4(audio_filepath)
            if title:
                audio["\xa9nam"] = [str(title)]
            if artist:
                audio["\xa9ART"] = [str(artist)]
            audio["\xa9alb"] = ["Muzix Downloads"]
            if has_img:
                with open(image_filepath, 'rb') as f:
                    img_data = f.read()
                audio["covr"] = [
                    MP4Cover(img_data, imageformat=MP4Cover.FORMAT_JPEG)
                ]
            audio.save()
            return True
    except Exception as e:
        print(f"Error embedding cover art: {e}")
    return False

def download_audio_native(target, is_search=False, output_dir="./downloads", progress_callback=None, downloader_format="mp3", downloader_threads=4, max_duration_mins=0, downloader_quality="192 kbps"):
    """Скачивание аудио."""
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # поиск ссылки
    target_clean = target.strip()
    is_direct_url = target_clean.startswith("http://") or target_clean.startswith("https://")
    
    direct_url = target_clean
    resolved_id = ""
    
    if is_search or not is_direct_url:
        if len(target_clean) == 11 and re.match(r'^[a-zA-Z0-9_-]{11}$', target_clean):
            direct_url = f"https://www.youtube.com/watch?v={target_clean}"
            resolved_id = target_clean
        else:
            resolve_opts = dict(COMMON_YDL_OPTS)
            resolve_opts.update({
                'extract_flat': True,
                'skip_download': True,
            })
            try:
                with yt_dlp.YoutubeDL(resolve_opts) as ydl_res:
                    search_res = ydl_res.extract_info(f"ytsearch1:{target_clean}", download=False)
                    entries = search_res.get('entries', []) if search_res else []
                    if entries and entries[0]:
                        resolved_id = entries[0].get('id', '')
                        direct_url = f"https://www.youtube.com/watch?v={resolved_id}"
            except Exception as se:
                print(f"Error in ytsearch resolve: {se}")

            if not resolved_id:
                try:
                    fallback = _search_youtube_scrape(target_clean, 1)
                    if fallback:
                        resolved_id = fallback[0]['id']
                        direct_url = fallback[0]['url']
                except Exception as fe:
                    print(f"Error in scrape resolve: {fe}")

            if not direct_url or not resolved_id:
                return json.dumps({
                    'status': 'error',
                    'message': f"По запросу '{target_clean}' ничего не найдено на YouTube"
                })

    # выбор формата
    quality_kbps = 320
    try:
        quality_kbps = int(downloader_quality.split()[0])
    except:
        pass

    # формат аудио
    is_video = (downloader_format == 'mp4' or downloader_format == 'video')
    if is_video:
        fmt_opt = 'best[ext=mp4]/bestvideo+bestaudio/best'
    elif downloader_format == 'webm':
        fmt_opt = f'bestaudio[ext=webm][abr<={quality_kbps}]/bestaudio[ext=webm]/bestaudio'
    elif downloader_format == 'm4a':
        fmt_opt = f'bestaudio[ext=m4a][abr<={quality_kbps}]/bestaudio[ext=m4a]/bestaudio'
    else:
        # только аудио
        fmt_opt = f'bestaudio[ext=m4a][abr<={quality_kbps}]/bestaudio[ext=webm][abr<={quality_kbps}]/bestaudio'

    def hook(d):
        if d['status'] == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes') or 0
            speed = d.get('speed') or 0
            eta = d.get('eta') or 0
            if total > 0 and progress_callback:
                try:
                    percent = int((downloaded / total) * 100)
                    progress_callback.onProgress(percent, int(speed), int(eta))
                except Exception as e:
                    print(f"Error calling progress callback: {e}")

    # шаблон имени
    outtmpl = os.path.join(output_dir, '%(title).100B [%(id)s].%(ext)s')

    ydl_opts = dict(COMMON_YDL_OPTS)
    ydl_opts.update({
        'format': fmt_opt,
        'outtmpl': outtmpl,
        'prefer_ffmpeg': False,
        'keepvideo': False,
        'progress_hooks': [hook],
        'concurrent_fragment_downloads': min(max(int(downloader_threads), 1), 8),
        'http_chunk_size': 10485760,
        'buffersize': 1048576,
        'retries': 5,
        'fragment_retries': 5,
    })

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            # загрузка потока
            download_info = ydl.extract_info(direct_url, download=True)
            if not download_info:
                return json.dumps({
                    'status': 'error',
                    'message': 'Не удалось получить аудиопоток с YouTube'
                })

            duration = download_info.get('duration', 0.0) or 0.0
            if max_duration_mins > 0 and duration > (max_duration_mins * 60):
                return json.dumps({
                    'status': 'error',
                    'message': f"Превышен лимит длительности: трек {int(duration // 60)} мин, максимум {max_duration_mins} мин"
                })

            video_id = download_info.get('id', '') or resolved_id
            title = download_info.get('title', 'Unknown Title')
            uploader = download_info.get('uploader', 'Unknown Artist')

            # поиск файла
            filepath = None
            req_downloads = download_info.get('requested_downloads')
            if req_downloads and len(req_downloads) > 0:
                filepath = req_downloads[0].get('filepath')

            if not filepath or not os.path.exists(filepath):
                expected = ydl.prepare_filename(download_info)
                if expected and os.path.exists(expected):
                    filepath = expected

            if not filepath or not os.path.exists(filepath):
                # поиск по id
                if video_id:
                    for f in os.listdir(output_dir):
                        if video_id in f and not f.endswith('.temp') and not f.endswith('.part'):
                            filepath = os.path.join(output_dir, f)
                            break

            if filepath and os.path.exists(filepath):
                # расширение mp3
                if downloader_format == 'mp3' and not filepath.lower().endswith('.mp3'):
                    new_mp3_path = os.path.splitext(filepath)[0] + ".mp3"
                    try:
                        os.rename(filepath, new_mp3_path)
                        filepath = new_mp3_path
                    except Exception as re:
                        print(f"File rename note: {re}")

                thumbnail_url = f"https://img.youtube.com/vi/{video_id}/hqdefault.jpg" if video_id else download_info.get('thumbnail')
                
                temp_thumb_path = os.path.splitext(filepath)[0] + "_temp.jpg"
                if thumbnail_url:
                    try:
                        urllib.request.urlretrieve(thumbnail_url, temp_thumb_path)
                    except Exception as te:
                        print(f"Error downloading thumbnail: {te}")
                embed_cover_art(filepath, temp_thumb_path, title=title, artist=uploader)
                if os.path.exists(temp_thumb_path):
                    try:
                        os.remove(temp_thumb_path)
                    except:
                        pass

                if progress_callback:
                    try:
                        progress_callback.onProgress(100, 0, 0)
                    except:
                        pass

                return json.dumps({
                    'status': 'success',
                    'filepath': filepath,
                    'title': title,
                    'uploader': uploader
                })
            else:
                return json.dumps({
                    'status': 'error',
                    'message': 'Не удалось найти скачанный файл на диске'
                })
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'message': str(e)
        })

def update_ytdlp_runtime():
    try:
        import urllib.request
        import zipfile
        import shutil
        
        from android.content import Context
        from com.chaquo.python import Python
        context = Python.getPlatform().getApplication()
        files_dir = context.getFilesDir().getAbsolutePath()
        user_pip_dir = os.path.join(files_dir, "python_packages")
        
        if not os.path.exists(user_pip_dir):
            os.makedirs(user_pip_dir)
            
        # версия yt-dlp
        url = "https://pypi.org/pypi/yt-dlp/json"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode('utf-8'))
            
        # ссылка wheel
        wheel_url = None
        for release in data['urls']:
            if release['packagetype'] == 'bdist_wheel':
                wheel_url = release['url']
                break
                
        if not wheel_url:
            return json.dumps({
                'status': 'error',
                'message': 'Wheel file not found in PyPI releases'
            })
            
        # скачивание wheel
        wheel_path = os.path.join(files_dir, "yt_dlp_latest.whl")
        urllib.request.urlretrieve(wheel_url, wheel_path)
        
        # распаковка zip
        with zipfile.ZipFile(wheel_path, 'r') as zip_ref:
            # распаковка пакета
            zip_ref.extractall(user_pip_dir)
            
        # удаление архива
        if os.path.exists(wheel_path):
            os.remove(wheel_path)
        
        # путь sys.path
        if user_pip_dir not in sys.path:
            sys.path.insert(0, user_pip_dir)
            
        # перезагрузка модулей
        to_reload = [m for m in sys.modules if m == 'yt_dlp' or m.startswith('yt_dlp.')]
        for m in to_reload:
            del sys.modules[m]
            
        import yt_dlp
        return json.dumps({
            'status': 'success',
            'version': yt_dlp.version.__version__
        })
    except Exception as e:
        return json.dumps({
            'status': 'error',
            'message': str(e)
        })

