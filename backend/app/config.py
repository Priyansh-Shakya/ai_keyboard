from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    supabase_url: str
    supabase_service_role_key: str

    llm_base_url: str = "https://api.x.ai/v1"
    llm_api_key: str
    llm_model: str = "grok-4"
    llm_max_tokens: int = 1024
    llm_system_prompt: str = (
        "You are an AI assistant inside a phone keyboard. Be concise and practical. "
        "For writing help (messages, emails, replies) output the text itself so the user "
        "can paste it directly, with no preamble."
    )

    chat_rate_limit_per_min: int = 20


settings = Settings()
