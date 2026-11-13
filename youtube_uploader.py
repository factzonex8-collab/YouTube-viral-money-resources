import os
import json
from pathlib import Path
from typing import Callable, Optional

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

YOUTUBE_SCOPES = [
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtube.readonly",
]


class YouTubeClientManager:
    """
    Manages one-time OAuth 2.0 authorization, persistent token refresh,
    channel metadata lookup, and resumable scheduled video uploads
    via YouTube Data API v3.
    """

    def __init__(self, client_secrets_path: Path, token_path: Path):
        self.client_secrets_path = client_secrets_path
        self.token_path = token_path
        self._pending_flows: dict[str, Flow] = {}

    def has_client_secrets(self) -> bool:
        return self.client_secrets_path.exists()

    def get_credentials(self) -> Optional[Credentials]:
        if not self.token_path.exists():
            return None

        try:
            creds = Credentials.from_authorized_user_file(
                str(self.token_path), YOUTUBE_SCOPES
            )
        except Exception:
            return None

        if creds and creds.expired and creds.refresh_token:
            try:
                creds.refresh(Request())
                self._save_credentials(creds)
            except Exception:
                return None

        if creds and creds.valid:
            return creds
        return None

    def _save_credentials(self, creds: Credentials) -> None:
        self.token_path.parent.mkdir(parents=True, exist_ok=True)
        self.token_path.write_text(creds.to_json(), encoding="utf-8")

    def disconnect(self) -> None:
        if self.token_path.exists():
            self.token_path.unlink()

    def create_auth_url(self, redirect_uri: str) -> str:
        if not self.has_client_secrets():
            raise FileNotFoundError(
                f"Missing OAuth client secrets file at {self.client_secrets_path}. "
                "Download your Web Application OAuth 2.0 JSON from Google Cloud Console "
                "and save it as client_secrets.json."
            )

        if redirect_uri.startswith("http://localhost") or redirect_uri.startswith("http://127.0.0.1"):
            os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"

        flow = Flow.from_client_secrets_file(
            str(self.client_secrets_path),
            scopes=YOUTUBE_SCOPES,
            redirect_uri=redirect_uri,
        )
        auth_url, state = flow.authorization_url(
            access_type="offline",
            include_granted_scopes="true",
            prompt="consent",
        )
        self._pending_flows[state] = flow
        return auth_url

    def exchange_code(self, state: str, authorization_response: str) -> Credentials:
        flow = self._pending_flows.pop(state, None)
        if flow is None:
            raise ValueError("Invalid or expired OAuth state. Please try connecting again.")

        if authorization_response.startswith("http://localhost") or authorization_response.startswith("http://127.0.0.1"):
            os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"

        flow.fetch_token(authorization_response=authorization_response)
        creds = flow.credentials
        self._save_credentials(creds)
        return creds

    def get_connected_channel(self) -> Optional[dict]:
        creds = self.get_credentials()
        if not creds:
            return None

        try:
            youtube = build("youtube", "v3", credentials=creds, cache_discovery=False)
            resp = youtube.channels().list(
                part="snippet,statistics",
                mine=True,
            ).execute()
            items = resp.get("items", [])
            if not items:
                return None
            channel = items[0]
            snippet = channel.get("snippet", {})
            stats = channel.get("statistics", {})
            thumbs = snippet.get("thumbnails", {})
            avatar = (
                thumbs.get("default", {}).get("url")
                or thumbs.get("medium", {}).get("url")
                or ""
            )
            return {
                "channel_id": channel.get("id", ""),
                "title": snippet.get("title", "Connected Channel"),
                "handle": snippet.get("customUrl", ""),
                "avatar_url": avatar,
                "subscriber_count": stats.get("subscriberCount", "0"),
                "video_count": stats.get("videoCount", "0"),
            }
        except HttpError as exc:
            return {"error": f"YouTube API error: {exc}"}

    def upload_scheduled_short(
        self,
        video_path: Path,
        title: str,
        description: str,
        tags: list[str],
        category_id: str = "22",
        publish_at_iso: Optional[str] = None,
        privacy_status: str = "private",
        on_progress: Optional[Callable[[int], None]] = None,
    ) -> dict:
        """
        Uploads an MP4 to YouTube using resumable upload.
        Note: YouTube Data API v3 requires privacyStatus='private' when publishAt is set.
        """
        creds = self.get_credentials()
        if not creds:
            raise RuntimeError(
                "YouTube channel is not connected. Complete the one-time OAuth 2.0 setup first."
            )

        if not video_path.exists():
            raise FileNotFoundError(f"Video file not found: {video_path}")

        youtube = build("youtube", "v3", credentials=creds, cache_discovery=False)

        # Ensure #Shorts tag is present for Shorts feed discovery
        safe_title = title.strip()[:100]
        if "#shorts" not in safe_title.lower() and len(safe_title) <= 92:
            safe_title = f"{safe_title} #Shorts"

        clean_tags = [t.strip()[:30] for t in tags if t and t.strip()][:20]

        status_body: dict = {
            "selfDeclaredMadeForKids": False,
        }
        if publish_at_iso:
            # YouTube Data API v3 enforces private status for scheduled publishAt
            status_body["privacyStatus"] = "private"
            status_body["publishAt"] = publish_at_iso
        else:
            status_body["privacyStatus"] = (
                privacy_status if privacy_status in {"private", "unlisted", "public"} else "private"
            )

        body = {
            "snippet": {
                "title": safe_title,
                "description": description.strip()[:5000],
                "tags": clean_tags,
                "categoryId": category_id or "22",
            },
            "status": status_body,
        }

        media = MediaFileUpload(
            str(video_path),
            mimetype="video/mp4",
            chunksize=4 * 1024 * 1024,
            resumable=True,
        )

        request = youtube.videos().insert(
            part="snippet,status",
            body=body,
            media_body=media,
        )

        response = None
        while response is None:
            status, response = request.next_chunk()
            if status and on_progress:
                on_progress(int(status.progress() * 100))

        if on_progress:
            on_progress(100)

        video_id = response.get("id", "")
        return {
            "video_id": video_id,
            "watch_url": f"https://www.youtube.com/watch?v={video_id}",
            "shorts_url": f"https://www.youtube.com/shorts/{video_id}",
            "studio_url": f"https://studio.youtube.com/video/{video_id}/edit",
            "publish_at": publish_at_iso,
            "privacy_status": status_body["privacyStatus"],
        }
