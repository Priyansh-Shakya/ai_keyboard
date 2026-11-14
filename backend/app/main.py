import json
import logging
import time
from contextlib import asynccontextmanager
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Response
from fastapi.responses import StreamingResponse

from . import llm, supa
from .auth import AuthUser, current_user, rate_limit
from .schemas import ChatRequest, Credentials, GoogleLogin, NoteIn, NoteUpdate, RefreshRequest, SignupBody

log = logging.getLogger("uvicorn.error")
NOTE_FIELDS = "id,title,content,created_at,updated_at"


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    await supa.client.aclose()


app = FastAPI(title="AI Keyboard API", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"ok": True}


# ------------------------------------------------------------------ auth
def _session(d: dict) -> dict:
    return {
        "access_token": d["access_token"],
        "refresh_token": d["refresh_token"],
        "expires_at": d.get("expires_at") or int(time.time()) + int(d.get("expires_in", 3600)),
        "email": (d.get("user") or {}).get("email", ""),
    }


@app.post("/auth/signup")
async def signup(body: SignupBody):
    signup_payload: dict = {"email": body.email, "password": body.password}
    if body.name:
        signup_payload["options"] = {"data": {"name": body.name}}
    d = await supa.auth_call("signup", signup_payload)

    user_data = d.get("user") or (d if "id" in d else {})
    user_id = user_data.get("id")
    email = user_data.get("email") or body.email
    user_meta = user_data.get("user_metadata") or {}
    name = body.name or user_meta.get("name") or user_meta.get("full_name")

    if user_id:
        await supa.ensure_user_profile(user_id, email, name)

    if "access_token" in d:
        return _session(d)
    return {"confirmation_required": True,
            "message": "Account created. Check your email to confirm it, then log in."}


@app.post("/auth/login")
async def login(body: Credentials):
    d = await supa.auth_call("token", {"email": body.email, "password": body.password},
                             params={"grant_type": "password"})
    user_data = d.get("user") or {}
    user_id = user_data.get("id")
    email = user_data.get("email") or body.email
    user_meta = user_data.get("user_metadata") or {}
    name = user_meta.get("name") or user_meta.get("full_name")
    if user_id:
        await supa.ensure_user_profile(user_id, email, name)
    return _session(d)


@app.post("/auth/refresh")
async def refresh(body: RefreshRequest):
    d = await supa.auth_call("token", {"refresh_token": body.refresh_token},
                             params={"grant_type": "refresh_token"})
    user_data = d.get("user") or {}
    user_id = user_data.get("id")
    email = user_data.get("email")
    if user_id and email:
        await supa.ensure_user_profile(user_id, email)
    return _session(d)


@app.post("/auth/google")
async def google(body: GoogleLogin):
    payload = {"provider": "google", "id_token": body.id_token}
    if body.nonce:
        payload["nonce"] = body.nonce
    d = await supa.auth_call("token", payload, params={"grant_type": "id_token"})
    user_data = d.get("user") or {}
    user_id = user_data.get("id")
    email = user_data.get("email")
    user_meta = user_data.get("user_metadata") or {}
    name = user_meta.get("name") or user_meta.get("full_name")
    if user_id and email:
        await supa.ensure_user_profile(user_id, email, name)
    return _session(d)


@app.get("/me")
async def me(user: AuthUser = Depends(current_user)):
    profile = await supa.get_user_profile(user.id)
    if not profile:
        profile = await supa.ensure_user_profile(user.id, user.email)
    if profile:
        return profile
    return {"user_id": user.id, "email": user.email, "name": None, "premium": False, "request_limits": 10}


# ------------------------------------------------------------------ notes
@app.get("/notes")
async def list_notes(user: AuthUser = Depends(current_user)):
    return await supa.rest("GET", "notes", params={
        "select": NOTE_FIELDS, "user_id": f"eq.{user.id}", "order": "created_at.desc"})


@app.post("/notes", status_code=201)
async def create_note(body: NoteIn, user: AuthUser = Depends(current_user)):
    rows = await supa.rest("POST", "notes", params={"select": NOTE_FIELDS},
                           json={**body.model_dump(), "user_id": user.id},
                           prefer="return=representation")
    return rows[0]


@app.patch("/notes/{note_id}")
async def update_note(note_id: UUID, body: NoteUpdate, user: AuthUser = Depends(current_user)):
    changes = body.model_dump(exclude_none=True)
    if not changes:
        raise HTTPException(400, "Nothing to update")
    rows = await supa.rest("PATCH", "notes", params={
        "id": f"eq.{note_id}", "user_id": f"eq.{user.id}", "select": NOTE_FIELDS},
        json=changes, prefer="return=representation")
    if not rows:
        raise HTTPException(404, "Note not found")
    return rows[0]


@app.delete("/notes/{note_id}", status_code=204)
async def delete_note(note_id: UUID, user: AuthUser = Depends(current_user)):
    rows = await supa.rest("DELETE", "notes", params={
        "id": f"eq.{note_id}", "user_id": f"eq.{user.id}", "select": "id"},
        prefer="return=representation")
    if not rows:
        raise HTTPException(404, "Note not found")
    return Response(status_code=204)


# ------------------------------------------------------------------ chat (SSE)
@app.post("/chat")
async def chat(body: ChatRequest, user: AuthUser = Depends(current_user)):
    rate_limit(user.id)
    msgs = [m.model_dump() for m in body.messages]

    async def events():
        try:
            async for delta in llm.stream_reply(msgs):
                yield f"data: {json.dumps({'delta': delta})}\n\n"
            yield "data: [DONE]\n\n"
        except Exception:
            log.exception("LLM provider error")
            yield f"data: {json.dumps({'error': 'The AI provider returned an error. Try again.'})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
