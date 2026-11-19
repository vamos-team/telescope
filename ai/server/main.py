import logging

from fastapi import FastAPI

from config import settings

logging.basicConfig(level=settings.log_level)
logger = logging.getLogger(__name__)

app = FastAPI(title="telescope-ai-server")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
