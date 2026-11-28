# 에이전트 서비스(ai-server). 빌드 위치는 저장소 루트: docker build -f ai/server/Dockerfile .
# 분석·예측 에이전트(ai/)와 작업 껍데기(ai/server)만 넣는다. 무거운 파싱 의존성(torch·docling)은
# 데이터 파이프라인(ingest-worker)에만 들어간다. 지금은 PIPELINE_MODE=fake로만 동작한다.
# 무시 목록: ai/server/Dockerfile.dockerignore
FROM python:3.12-slim
WORKDIR /app
COPY ai/server/requirements.txt ai/server/requirements.txt
RUN pip install --no-cache-dir -r ai/server/requirements.txt
COPY ai/ ai/
ENV PYTHONPATH=/app
WORKDIR /app/ai/server
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
