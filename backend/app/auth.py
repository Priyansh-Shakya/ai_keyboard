import time
from collections import deque
from dataclasses import dataclass

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import supa
from .config import settings

bearer = HTTPBearer(auto_error=False)
_cache: dict[str, tuple[float, "AuthUser"]] = {}   # token -> (expires, user); avoids a Supabase call per request
_hits: dict[str, deque] = {}
CACHE_TTL = 60


@dataclass
class AuthUser:
    id: str
    email: str


async def current_user(creds: HTTPAuthorizationCredentials | None = Depends(bearer)) -> AuthUser:
    if not creds:
        raise HTTPException(401, "Missing token")
    now = time.monotonic()
    hit = _cache.get(creds.credentials)
    if hit and hit[0] > now:
        return hit[1]
    data = await supa.get_user(creds.credentials)
    if not data or "id" not in data:
        raise HTTPException(401, "Invalid or expired token")
    user = AuthUser(id=data["id"], email=data.get("email") or "")
    if len(_cache) > 2000:
        _cache.clear()
    _cache[creds.credentials] = (now + CACHE_TTL, user)
    return user


def rate_limit(user_id: str) -> None:
    limit = settings.chat_rate_limit_per_min
    if limit <= 0:
        return
    now = time.monotonic()
    dq = _hits.setdefault(user_id, deque())
    while dq and now - dq[0] > 60:
        dq.popleft()
    if len(dq) >= limit:
        raise HTTPException(429, "Too many requests. Slow down a little.")
    dq.append(now)
