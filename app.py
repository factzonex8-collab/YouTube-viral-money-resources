import os
import re
import uuid
import shutil
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

import streamlit as st
import torch
import whisper
from dotenv import load_dotenv
from google import genai
from google.genai import types
from pydantic import BaseModel, Field

from youtube_uploader import YouTubeClientManager

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent
WORK_DIR = BASE_DIR / "workspace"
WORK_DIR.mkdir(parents=True, exist_ok=True)
CLIENT_SECRETS_PATH = BASE_DIR / "client_secrets.json"
YOUTUBE_TOKEN_PATH = WORK_DIR / "youtube_token.json"

YOUTUBE_REGEX = re.compile(
    r"^(https?://)?(www\.|m\.)?(youtube\.com/(watch\?v=|shorts/|live/|embed/)|youtu\.be/)[\w\-]{6,}.*$"
)


class ViralClipSelection(BaseModel):
    start_time: float = Field(
        description="Start timestamp of the viral clip in seconds (float)."
    )
    end_time: float = Field(
        description="End timestamp of the viral clip in seconds (float)."
    )
    hook_title: str = Field(
        description="Punchy 3-7 word viral title summarizing the hook."
    )
    viral_score: int = Field(
        ge=1,
        le=100,
        description="Estimated viral retention score from 1 to 100.",
    )
    reason: str = Field(
        description="2-3 sentence explanation of why this segment hooks viewers and retains attention."
    )
    highlight_keywords: list[str] = Field(
        default_factory=list,
        description="3-6 high-impact keywords from the clip to visually emphasize in captions.",
    )


class TrendIdea(BaseModel):
    topic: str
    viral_angle: str
    youtube_search_query: str


class TrendDiscoveryResult(BaseModel):
    ideas: list[TrendIdea] = Field(min_length=1, max_length=5)
    selected_index: int = Field(default=0, ge=0)
    trend_summary: str


class YouTubeSEOPlan(BaseModel):
    title: str
    description: str
    tags: list[str]
    category_id: str = "22"
    seo_rationale: str


@st.cache_resource(show_spinner=False)
def load_whisper_cached(model_name: str) -> whisper.Whisper:
    device = "cuda" if torch.cuda.is_available() else "cpu"
    return whisper.load_model(model_name, device=device)


def resolve_gemini_api_key(sidebar_input: str = "") -> str:
    if sidebar_input.strip():
        return sidebar_input.strip()
    env_key = os.getenv("GEMINI_API_KEY", "").strip()
    if env_key:
        return env_key
    try:
        return str(st.secrets.get("GEMINI_API_KEY", "")).strip()
    except Exception:
        return ""


def format_timestamp_mmss(seconds: float) -> str:
    total_ms = max(0, int(round(seconds * 1000)))
    mins, rem_ms = divmod(total_ms, 60000)
    secs, ms = divmod(rem_ms, 1000)
    return f"{mins:02d}:{secs:02d}.{ms // 10:02d}"


def format_ass_time(seconds: float) -> str:
    total_cs = max(0, int(round(seconds * 100)))
    hours, rem = divmod(total_cs, 360000)
    mins, rem = divmod(rem, 6000)
    secs, cs = divmod(rem, 100)
    return f"{hours}:{mins:02d}:{secs:02d}.{cs:02d}"


def escape_ass_text(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace("{", "\\{")
        .replace("}", "\\}")
        .replace("\n", " ")
        .strip()
    )


def escape_ffmpeg_filter_path(path: Path) -> str:
    raw = path.resolve().as_posix()
    return raw.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")


def sanitize_filename(title: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_\-\s]", "", title).strip()
    cleaned = re.sub(r"\s+", "_", cleaned)
    return (cleaned[:48] or "viral_short") + "_9x16.mp4"


def download_youtube_video(url: str, job_dir: Path) -> dict:
    import yt_dlp

    output_template = str(job_dir / "source.%(ext)s")
    ydl_opts = {
        "format": "bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4][height<=1080]/best",
        "outtmpl": output_template,
        "merge_output_format": "mp4",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        downloaded_path = Path(ydl.prepare_filename(info))
        mp4_candidate = downloaded_path.with_suffix(".mp4")
        if mp4_candidate.exists():
            final_video = mp4_candidate
        elif downloaded_path.exists():
            final_video = downloaded_path
        else:
            matches = list(job_dir.glob("source.*"))
            if not matches:
                raise FileNotFoundError("yt-dlp failed to produce a video file.")
            final_video = matches[0]

    return {
        "video_path": final_video,
        "title": info.get("title") or "YouTube Video",
        "duration": float(info.get("duration") or 0.0),
        "uploader": info.get("uploader") or "",
    }


def extract_audio_wav(video_path: Path, wav_path: Path) -> None:
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(video_path),
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        "16000",
        "-ac",
        "1",
        str(wav_path),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"FFmpeg audio extraction failed: {res.stderr[-400:]}")


def probe_video_duration(video_path: Path) -> float:
    cmd = [
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(video_path),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        try:
            return float(res.stdout.strip())
        except ValueError:
            pass
    return 0.0


def transcribe_with_whisper(wav_path: Path, model_name: str) -> dict:
    model = load_whisper_cached(model_name)
    use_fp16 = torch.cuda.is_available()
    return model.transcribe(
        str(wav_path),
        word_timestamps=True,
        fp16=use_fp16,
        verbose=False,
    )


def select_viral_clip_with_gemini(
    api_key: str,
    segments: list[dict],
    video_title: str,
    video_duration: float,
    target_duration: int,
) -> ViralClipSelection:
    if not segments:
        raise RuntimeError("Whisper produced an empty transcript; no speech found.")

    transcript_lines = []
    for seg in segments:
        s = float(seg.get("start", 0.0))
        e = float(seg.get("end", s))
        txt = (seg.get("text") or "").strip()
        if txt:
            transcript_lines.append(f"[{s:.2f}s - {e:.2f}s] {txt}")

    transcript_text = "\n".join(transcript_lines)[:120000]
    min_len = max(15, min(25, int(video_duration * 0.5))) if video_duration > 0 else 15
    max_len = min(60, max(min_len + 5, int(target_duration)))

    prompt = f"""You are a viral short-form video editor (TikTok, YouTube Shorts, Instagram Reels).
Analyze the timestamped transcript below for "{video_title}" (total duration: {video_duration:.2f}s).

Select the SINGLE highest-retention contiguous clip between {min_len} and {max_len} seconds long.
Rules:
1. Strong 3-second hook at the start.
2. Self-contained story or takeaway.
3. Never cut mid-sentence.
4. Return exact timestamps in seconds (`start_time` and `end_time`).

Transcript:
{transcript_text}
"""

    client = genai.Client(api_key=api_key)
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=prompt,
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=ViralClipSelection,
            temperature=0.3,
        ),
    )

    selection = ViralClipSelection.model_validate_json(response.text)
    max_bound = video_duration if video_duration > 0 else float(segments[-1]["end"])
    start = max(0.0, min(selection.start_time, max(0.0, max_bound - 10.0)))
    end = min(max_bound, selection.end_time)

    if end - start < 10.0:
        end = min(max_bound, start + float(target_duration))
    if end - start > 62.0:
        end = start + 60.0

    for seg in segments:
        seg_s = float(seg.get("start", 0.0))
        seg_e = float(seg.get("end", 0.0))
        if abs(seg_s - start) <= 0.6:
            start = seg_s
        if abs(seg_e - end) <= 0.6:
            end = seg_e

    selection.start_time = round(start, 2)
    selection.end_time = round(max(start + 8.0, min(max_bound, end)), 2)
    return selection


def build_ass_subtitles(
    whisper_result: dict,
    clip_start: float,
    clip_end: float,
    style_name: str,
    highlight_keywords: list[str],
    ass_path: Path,
) -> list[dict]:
    keywords_lower = {k.strip().lower() for k in highlight_keywords if k.strip()}
    words: list[dict] = []

    for seg in whisper_result.get("segments", []):
        seg_words = seg.get("words") or []
        if seg_words:
            for w in seg_words:
                ws = float(w.get("start", 0.0))
                we = float(w.get("end", ws + 0.2))
                wt = (w.get("word") or "").strip()
                if wt and we >= clip_start and ws <= clip_end:
                    words.append(
                        {
                            "start": max(0.0, ws - clip_start),
                            "end": max(0.05, min(clip_end, we) - clip_start),
                            "text": wt,
                        }
                    )
        else:
            ss = float(seg.get("start", 0.0))
            se = float(seg.get("end", ss + 1.0))
            st_txt = (seg.get("text") or "").strip()
            if st_txt and se >= clip_start and ss <= clip_end:
                tokens = st_txt.split()
                dur = max(0.2, min(clip_end, se) - max(clip_start, ss))
                step = dur / max(1, len(tokens))
                base = max(0.0, ss - clip_start)
                for idx, tok in enumerate(tokens):
                    words.append(
                        {
                            "start": base + idx * step,
                            "end": base + (idx + 1) * step,
                            "text": tok,
                        }
                    )

    chunks: list[list[dict]] = []
    current: list[dict] = []
    current_chars = 0
    for w in words:
        wlen = len(w["text"])
        if current and (len(current) >= 3 or current_chars + wlen + 1 > 20):
            chunks.append(current)
            current = [w]
            current_chars = wlen
        else:
            current.append(w)
            current_chars += wlen + (1 if current else 0)
    if current:
        chunks.append(current)

    if style_name == "neon":
        style_line = (
            "Style: Default,Arial,74,&H00FFFFFF,&H0010B981,&H0009120E,&HAA000000,"
            "-1,0,0,0,100,100,0,0,1,5,3,2,72,72,380,1"
        )
        accent_color = "&H0081B910&"
        uppercase = True
    elif style_name == "minimal":
        style_line = (
            "Style: Default,Arial,66,&H00F8FAFC,&H00FFFFFF,&HAA0A0B0F,&H00000000,"
            "-1,0,0,0,100,100,0,0,3,12,0,2,72,72,360,1"
        )
        accent_color = "&H004FD3FB&"
        uppercase = False
    else:
        style_line = (
            "Style: Default,Arial Black,80,&H00FFFFFF,&H0000D7FF,&H00000000,&HCC000000,"
            "-1,0,0,0,100,100,0,0,1,7,4,2,64,64,390,1"
        )
        accent_color = "&H0000D7FF&"
        uppercase = True

    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
ScaledBorderAndShadow: yes
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
{style_line}

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

    events: list[str] = []
    preview_cues: list[dict] = []

    for chunk in chunks:
        c_start = chunk[0]["start"]
        c_end = max(c_start + 0.15, chunk[-1]["end"])
        preview_cues.append(
            {
                "start": round(c_start, 2),
                "end": round(c_end, 2),
                "text": " ".join(w["text"] for w in chunk),
            }
        )

        for active_idx, active_word in enumerate(chunk):
            w_start = active_word["start"]
            w_end = (
                chunk[active_idx + 1]["start"]
                if active_idx + 1 < len(chunk)
                else c_end
            )
            w_end = max(w_start + 0.05, w_end)

            styled_tokens = []
            for idx, w in enumerate(chunk):
                raw_txt = w["text"].upper() if uppercase else w["text"]
                escaped = escape_ass_text(raw_txt)
                clean_alpha = re.sub(r"[^a-z0-9]", "", w["text"].lower())
                if idx == active_idx or clean_alpha in keywords_lower:
                    styled_tokens.append(
                        f"{{\\c{accent_color}\\fscx108\\fscy108}}{escaped}{{\\r}}"
                    )
                else:
                    styled_tokens.append(escaped)

            events.append(
                f"Dialogue: 0,{format_ass_time(w_start)},{format_ass_time(w_end)},Default,,0,0,0,,{' '.join(styled_tokens)}"
            )

    ass_path.write_text(header + "\n".join(events) + "\n", encoding="utf-8")
    return preview_cues


def render_vertical_short(
    source_video: Path,
    ass_path: Path,
    output_video: Path,
    start_time: float,
    end_time: float,
) -> None:
    duration = max(1.0, end_time - start_time)
    escaped_ass = escape_ffmpeg_filter_path(ass_path)
    vf = (
        f"scale=1080:1920:force_original_aspect_ratio=increase,"
        f"crop=1080:1920,"
        f"setsar=1,"
        f"ass='{escaped_ass}'"
    )
    cmd = [
        "ffmpeg",
        "-y",
        "-ss",
        f"{start_time:.3f}",
        "-i",
        str(source_video),
        "-t",
        f"{duration:.3f}",
        "-vf",
        vf,
        "-c:v",
        "libx264",
        "-preset",
        "medium",
        "-crf",
        "20",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        "-movflags",
        "+faststart",
        str(output_video),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"FFmpeg render failed: {res.stderr[-600:]}")


def discover_trends_with_gemini(api_key: str, niche: str) -> TrendDiscoveryResult:
    client = genai.Client(api_key=api_key)
    grounded = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=(
            f"Search the web for the top 3 hottest trending topics or stories right now "
            f"in '{niche}' that have engaging YouTube commentary, podcasts, or explainers."
        ),
        config=types.GenerateContentConfig(
            tools=[types.Tool(google_search=types.GoogleSearch())],
            temperature=0.5,
        ),
    )
    structured = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=(
            f"Based on these trends for '{niche}':\n{grounded.text}\n\n"
            "Return 3 structured viral Shorts ideas with YouTube search queries for 2-15 minute videos."
        ),
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=TrendDiscoveryResult,
            temperature=0.4,
        ),
    )
    return TrendDiscoveryResult.model_validate_json(structured.text)


def find_source_video(queries: list[str]) -> tuple[str, str]:
    import yt_dlp

    with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True, "skip_download": True}) as ydl:
        for query in queries:
            info = ydl.extract_info(f"ytsearch6:{query}", download=False)
            for entry in info.get("entries") or []:
                vid_id = entry.get("id")
                duration = entry.get("duration") or 0
                if not vid_id:
                    continue
                if duration and (duration < 75 or duration > 1500):
                    continue
                return f"https://www.youtube.com/watch?v={vid_id}", entry.get("title") or "YouTube Video"
    raise RuntimeError("No matching YouTube video found for discovered trends.")


def generate_seo_plan(api_key: str, niche: str, hook_title: str, cues: list[dict]) -> YouTubeSEOPlan:
    client = genai.Client(api_key=api_key)
    transcript = " ".join(c["text"] for c in cues)
    resp = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=(
            f"Generate a YouTube Shorts SEO package for niche '{niche}'.\n"
            f"Hook: {hook_title}\nTranscript: {transcript}\n"
            "Keep title under 65 chars, include hashtags including #Shorts in description, and 12 tags."
        ),
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=YouTubeSEOPlan,
            temperature=0.4,
        ),
    )
    return YouTubeSEOPlan.model_validate_json(resp.text)


def process_clip_pipeline(
    youtube_url: str,
    api_key: str,
    caption_style: str,
    whisper_model: str,
    target_duration: int,
    status_box,
) -> dict:
    job_id = uuid.uuid4().hex[:10]
    job_dir = WORK_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    wav_path = job_dir / "audio.wav"
    ass_path = job_dir / "captions.ass"
    output_video = job_dir / "short_9x16.mp4"

    status_box.write("1/4 Downloading YouTube video via yt-dlp...")
    meta = download_youtube_video(youtube_url, job_dir)
    source_video = meta["video_path"]
    duration = meta["duration"] or probe_video_duration(source_video)

    status_box.write(f"2/4 Extracting audio & transcribing with Whisper ({whisper_model})...")
    extract_audio_wav(source_video, wav_path)
    whisper_result = transcribe_with_whisper(wav_path, whisper_model)

    status_box.write("3/4 Selecting highest-retention viral moment with Gemini...")
    selection = select_viral_clip_with_gemini(
        api_key=api_key,
        segments=whisper_result.get("segments", []),
        video_title=meta["title"],
        video_duration=duration,
        target_duration=target_duration,
    )

    status_box.write("4/4 Burning auto-captions & cropping to 9:16 with FFmpeg...")
    cues = build_ass_subtitles(
        whisper_result=whisper_result,
        clip_start=selection.start_time,
        clip_end=selection.end_time,
        style_name=caption_style,
        highlight_keywords=selection.highlight_keywords,
        ass_path=ass_path,
    )
    render_vertical_short(
        source_video=source_video,
        ass_path=ass_path,
        output_video=output_video,
        start_time=selection.start_time,
        end_time=selection.end_time,
    )

    for temp in (source_video, wav_path, ass_path):
        if temp.exists():
            try:
                temp.unlink()
            except OSError:
                pass

    return {
        "job_id": job_id,
        "video_title": meta["title"],
        "output_path": str(output_video),
        "download_filename": sanitize_filename(selection.hook_title),
        "hook_title": selection.hook_title,
        "viral_score": selection.viral_score,
        "reason": selection.reason,
        "start_time": selection.start_time,
        "end_time": selection.end_time,
        "start_formatted": format_timestamp_mmss(selection.start_time),
        "end_formatted": format_timestamp_mmss(selection.end_time),
        "duration": round(selection.end_time - selection.start_time, 2),
        "highlight_keywords": selection.highlight_keywords,
        "captions_preview": cues,
    }


def main():
    st.set_page_config(
        page_title="ClipForge AI — 9:16 Viral Shorts Generator",
        page_icon="🎬",
        layout="wide",
    )

    st.markdown(
        """
        <style>
        .stApp {
            background: radial-gradient(900px 500px at 15% -10%, rgba(16, 185, 129, 0.10), transparent 60%), #08090d;
            color: #f3f5f9;
        }
        .metric-card {
            background: #11141d;
            border: 1px solid #252b3b;
            border-radius: 14px;
            padding: 16px;
        }
        </style>
        """,
        unsafe_allow_html=True,
    )

    with st.sidebar:
        st.header("Engine Settings")
        api_key_input = st.text_input(
            "Gemini API Key",
            value="",
            type="password",
            help="Reads GEMINI_API_KEY from .env / Streamlit secrets if left blank.",
        )
        caption_style = st.selectbox(
            "Caption Style",
            options=["hormozi", "neon", "minimal"],
            format_func=lambda s: {
                "hormozi": "Hormozi Pop (Bold Gold)",
                "neon": "Neon Emerald",
                "minimal": "Minimal Boxed",
            }[s],
        )
        whisper_model = st.selectbox("Whisper Model", ["tiny", "base", "small"], index=1)
        target_duration = st.slider("Target Clip Length (sec)", 20, 60, 45, 5)

        st.divider()
        ffmpeg_ok = shutil.which("ffmpeg") is not None
        active_key = resolve_gemini_api_key(api_key_input)
        st.write(f"FFmpeg: {'Ready' if ffmpeg_ok else 'Missing in PATH'}")
        st.write(f"Gemini API Key: {'Configured' if active_key else 'Not set'}")
        st.write(f"Whisper Device: {'CUDA GPU' if torch.cuda.is_available() else 'CPU'}")

    st.title("ClipForge AI — 9:16 Viral Shorts Studio")
    st.caption("Single-process Streamlit app: yt-dlp + OpenAI Whisper + Google Gemini + FFmpeg (no separate backend server needed).")

    tab_clipper, tab_agent = st.tabs(["YouTube URL → 9:16 Short", "Autonomous Niche & SEO"])

    with tab_clipper:
        youtube_url = st.text_input(
            "YouTube Video URL",
            placeholder="https://www.youtube.com/watch?v=...",
        )

        if st.button("Generate 9:16 Viral Short", type="primary", use_container_width=True):
            api_key = resolve_gemini_api_key(api_key_input)
            if not ffmpeg_ok:
                st.error("FFmpeg is not installed or not in system PATH.")
            elif not api_key:
                st.error("Enter your Gemini API Key in the sidebar or set GEMINI_API_KEY in .env.")
            elif not youtube_url.strip() or not YOUTUBE_REGEX.match(youtube_url.strip()):
                st.error("Please enter a valid YouTube video or Shorts URL.")
            else:
                with st.status("Running AI clipping pipeline...", expanded=True) as status:
                    try:
                        result = process_clip_pipeline(
                            youtube_url=youtube_url.strip(),
                            api_key=api_key,
                            caption_style=caption_style,
                            whisper_model=whisper_model,
                            target_duration=target_duration,
                            status_box=status,
                        )
                        st.session_state["last_clip"] = result
                        status.update(label="9:16 Short Ready!", state="complete")
                    except Exception as exc:
                        status.update(label=f"Failed: {exc}", state="error")
                        st.error(str(exc))

        clip = st.session_state.get("last_clip")
        if clip and Path(clip["output_path"]).exists():
            st.divider()
            col_video, col_info = st.columns([1, 1.4], gap="large")
            video_bytes = Path(clip["output_path"]).read_bytes()

            with col_video:
                st.video(video_bytes)
                st.download_button(
                    label="Download Watermark-Free 9:16 MP4",
                    data=video_bytes,
                    file_name=clip["download_filename"],
                    mime="video/mp4",
                    type="primary",
                    use_container_width=True,
                )

            with col_info:
                st.subheader(clip["hook_title"])
                st.caption(f"Source: {clip['video_title']}")
                m1, m2, m3 = st.columns(3)
                m1.metric("Viral Score", f"{clip['viral_score']}/100")
                m2.metric("Segment", f"{clip['start_formatted']} - {clip['end_formatted']}")
                m3.metric("Duration", f"{clip['duration']}s")

                st.info(clip["reason"])
                if clip["highlight_keywords"]:
                    st.write("**Keywords:** " + " ".join(f"`#{k}`" for k in clip["highlight_keywords"]))

                with st.expander("Auto-Generated Captions", expanded=True):
                    for cue in clip["captions_preview"]:
                        st.markdown(f"`{cue['start']:.1f}s` — {cue['text']}")

    with tab_agent:
        st.subheader("Autonomous Trend Discovery, Clipping & YouTube SEO")
        niche = st.text_input("Channel Niche / Topic", value="AI tools, tech breakthroughs, and productivity")
        peak_hour = st.selectbox("Optimal Peak Publish Hour (UTC)", [14, 17, 18, 20, 22], index=2)

        yt_manager = YouTubeClientManager(CLIENT_SECRETS_PATH, YOUTUBE_TOKEN_PATH)
        channel = yt_manager.get_connected_channel()
        if channel and "error" not in channel:
            st.success(f"Connected YouTube Channel: {channel['title']} ({channel['subscriber_count']} subscribers)")
        else:
            st.info("Optional YouTube auto-upload: place client_secrets.json in the project root and run a local OAuth token setup to enable direct scheduled uploads.")

        if st.button("Discover Viral Trend, Clip & Generate SEO", type="primary"):
            api_key = resolve_gemini_api_key(api_key_input)
            if not ffmpeg_ok or not api_key:
                st.error("Ensure FFmpeg is installed and your Gemini API Key is set in the sidebar.")
            else:
                with st.status("Running autonomous workflow...", expanded=True) as status:
                    try:
                        status.write("Searching the web for trending niche topics via Gemini...")
                        trends = discover_trends_with_gemini(api_key, niche)
                        chosen = trends.ideas[min(trends.selected_index, len(trends.ideas) - 1)]
                        st.session_state["last_trends"] = [i.model_dump() for i in trends.ideas]

                        status.write(f"Finding source video for: {chosen.topic}")
                        src_url, src_title = find_source_video([i.youtube_search_query for i in trends.ideas])

                        clip_res = process_clip_pipeline(
                            youtube_url=src_url,
                            api_key=api_key,
                            caption_style=caption_style,
                            whisper_model=whisper_model,
                            target_duration=target_duration,
                            status_box=status,
                        )

                        status.write("Generating optimized YouTube SEO metadata...")
                        seo = generate_seo_plan(
                            api_key=api_key,
                            niche=niche,
                            hook_title=clip_res["hook_title"],
                            cues=clip_res["captions_preview"],
                        )

                        now = datetime.now(timezone.utc)
                        candidate = now.replace(hour=peak_hour, minute=0, second=0, microsecond=0)
                        if candidate <= now + timedelta(hours=2):
                            candidate += timedelta(days=1)
                        publish_iso = candidate.strftime("%Y-%m-%dT%H:%M:%SZ")

                        st.session_state["last_agent_run"] = {
                            "trend": chosen.model_dump(),
                            "source_url": src_url,
                            "source_title": src_title,
                            "clip": clip_res,
                            "seo": seo.model_dump(),
                            "publish_at": publish_iso,
                        }
                        status.update(label="Autonomous Short & SEO Ready!", state="complete")
                    except Exception as exc:
                        status.update(label=f"Failed: {exc}", state="error")
                        st.error(str(exc))

        agent_run = st.session_state.get("last_agent_run")
        if agent_run and Path(agent_run["clip"]["output_path"]).exists():
            st.divider()
            c1, c2 = st.columns([1, 1.4], gap="large")
            vbytes = Path(agent_run["clip"]["output_path"]).read_bytes()
            with c1:
                st.video(vbytes)
                st.download_button(
                    "Download Autonomous 9:16 MP4",
                    data=vbytes,
                    file_name=agent_run["clip"]["download_filename"],
                    mime="video/mp4",
                    use_container_width=True,
                )
            with c2:
                seo = agent_run["seo"]
                st.subheader(seo["title"])
                st.caption(f"Trend: {agent_run['trend']['topic']} • Scheduled Slot: {agent_run['publish_at']}")
                st.text_area("Optimized Description", value=seo["description"], height=130)
                st.write("**Tags:** " + ", ".join(seo["tags"]))

                if channel and "error" not in channel:
                    if st.button("Upload & Schedule to Connected YouTube Channel"):
                        yt_res = yt_manager.upload_scheduled_short(
                            video_path=Path(agent_run["clip"]["output_path"]),
                            title=seo["title"],
                            description=seo["description"],
                            tags=seo["tags"],
                            category_id=seo["category_id"],
                            publish_at_iso=agent_run["publish_at"],
                        )
                        st.success(f"Scheduled on YouTube! Studio URL: {yt_res['studio_url']}")


if __name__ == "__main__":
    main()
