"""Thin async wrapper over Supabase Auth (GoTrue) and PostgREST."""
import logging

import httpx
from fastapi import HTTPException

from .config import settings

log = logging.getLogger("uvicorn.error")
client = httpx.AsyncClient(timeout=20.0)
BASE = settings.supabase_url.rstrip("/")
KEY = settings.supabase_service_role_key


def _msg(resp: httpx.Response, default: str) -> str:
    try:
        d = resp.json()
        return d.get("msg") or d.get("error_description") or d.get("message") or default
    except Exception:
        return default


async def auth_call(path: str, body: dict, params: dict | None = None) -> dict:
    try:
        r = await client.post(
            f"{BASE}/auth/v1/{path}", json=body, params=params,
            headers={"apikey": KEY, "Content-Type": "application/json"},
        )
    except httpx.HTTPError:
        raise HTTPException(502, "Auth service unreachable")
    if r.status_code >= 400:
        raise HTTPException(r.status_code if r.status_code < 500 else 502,
                            _msg(r, "Authentication failed"))
    return r.json()


async def get_user(token: str) -> dict | None:
    try:
        r = await client.get(f"{BASE}/auth/v1/user",
                             headers={"apikey": KEY, "Authorization": f"Bearer {token}"})
    except httpx.HTTPError:
        raise HTTPException(502, "Auth service unreachable")
    return r.json() if r.status_code == 200 else None


async def rest(method: str, table: str, *, params: dict | None = None,
               json: dict | None = None, prefer: str | None = None) -> list:
    headers = {"apikey": KEY, "Authorization": f"Bearer {KEY}", "Content-Type": "application/json"}
    if prefer:
        headers["Prefer"] = prefer
    try:
        r = await client.request(method, f"{BASE}/rest/v1/{table}", params=params, json=json, headers=headers)
    except httpx.HTTPError:
        raise HTTPException(502, "Database unreachable")
    if r.status_code >= 400:
        log.error("PostgREST %s %s -> %s %s", method, table, r.status_code, r.text[:300])
        raise HTTPException(502, "Database error")
    return r.json() if r.content else []


async def ensure_user_profile(user_id: str, email: str, name: str | None = None) -> dict | None:
    """Ensure user profile row exists in 'users' table (creates if missing, updates email/name if changed)."""
    payload: dict = {"user_id": user_id, "email": email}
    if name is not None:
        payload["name"] = name
    try:
        rows = await rest(
            "POST",
            "users",
            json=payload,
            prefer="resolution=merge-duplicates,return=representation",
        )
        return rows[0] if rows else None
    except Exception as e:
        log.error("Failed to ensure user profile for %s: %s", user_id, e)
        return None


async def get_user_profile(user_id: str) -> dict | None:
    """Fetch user profile row from 'users' table."""
    try:
        rows = await rest("GET", "users", params={"user_id": f"eq.{user_id}"})
        return rows[0] if rows else None
    except Exception as e:
        log.error("Failed to fetch user profile for %s: %s", user_id, e)
        return None


async def decrement_request_limit(user_id: str) -> dict | None:
    """Decrement request_limits for non-premium user by 1 and return updated profile."""
    try:
        profile = await get_user_profile(user_id)
        if not profile:
            return None
        if profile.get("premium", False):
            return profile
        curr = profile.get("request_limits", 10)
        if curr > 0:
            new_val = curr - 1
            rows = await rest(
                "PATCH",
                "users",
                params={"user_id": f"eq.{user_id}"},
                json={"request_limits": new_val},
                prefer="return=representation",
            )
            if rows and isinstance(rows, list):
                return rows[0]
            profile["request_limits"] = new_val
            return profile
        return profile
    except Exception as e:
        log.error("Failed to decrement request limit for %s: %s", user_id, e)
    return None
