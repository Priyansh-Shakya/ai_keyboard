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
