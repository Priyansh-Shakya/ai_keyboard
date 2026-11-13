from typing import Literal

from pydantic import BaseModel, Field


class Credentials(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=128)


class SignupBody(Credentials):
    password: str = Field(min_length=6, max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str


class GoogleLogin(BaseModel):
    id_token: str
    nonce: str | None = None


class NoteIn(BaseModel):
    title: str = Field("", max_length=200)
    content: str = Field(min_length=1, max_length=100_000)


class NoteUpdate(BaseModel):
    title: str | None = Field(None, max_length=200)
    content: str | None = Field(None, min_length=1, max_length=100_000)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1, max_length=40)
