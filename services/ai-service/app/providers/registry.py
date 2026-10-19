import os
from fastapi import HTTPException
from app.providers.mock import MockProvider


def provider():
    name = os.environ.get("AI_PROVIDER", "mock")
    if name == "mock":
        return MockProvider()
    if name == "bedrock":
        if os.environ.get("REAL_PROVIDER_ENABLED") != "true" or os.environ.get("BEDROCK_ENABLED") != "true":
            raise HTTPException(503, "Bedrock extraction requires explicit enablement")
        from app.providers.bedrock_provider import BedrockProvider
        return BedrockProvider()
    names = {"openai": ("OPENAI", "app.providers.openai_provider", "OpenAIProvider"),
             "anthropic": ("ANTHROPIC", "app.providers.anthropic_provider", "AnthropicProvider")}
    if name not in names:
        raise HTTPException(503, "Provider is not enabled")
    prefix, module, cls = names[name]
    if (os.environ.get("REAL_PROVIDER_ENABLED") != "true" or not os.environ.get(prefix + "_API_KEY")
            or not os.environ.get(prefix + "_MODEL")):
        raise HTTPException(503, "Real provider requires explicit enablement, key and model")
    import importlib
    return getattr(importlib.import_module(module), cls)()
