from collections.abc import AsyncIterator

from openai import AsyncOpenAI

from .config import settings

# Any OpenAI-compatible provider (xAI, Groq, OpenRouter, HF router, OpenAI...) - just change .env
_client = AsyncOpenAI(base_url=settings.llm_base_url, api_key=settings.llm_api_key)


async def stream_reply(messages: list[dict]) -> AsyncIterator[str]:
    kwargs = {}
    if settings.llm_max_tokens > 0:
        kwargs["max_tokens"] = settings.llm_max_tokens
    stream = await _client.chat.completions.create(
        model=settings.llm_model,
        messages=[{"role": "system", "content": settings.llm_system_prompt}, *messages],
        stream=True,
        **kwargs,
    )
    async for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            yield chunk.choices[0].delta.content
