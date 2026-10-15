FROM ghcr.io/astral-sh/uv:0.10.4 AS uv
FROM python:3.14-slim
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
COPY services/ai-service/pyproject.toml services/ai-service/uv.lock ./
RUN uv sync --frozen --no-dev
COPY services/ai-service/app app
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
USER 10001
EXPOSE 8000
CMD [".venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
