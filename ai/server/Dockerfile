# 빌드 위치는 저장소 루트다: docker build -f ai/server/Dockerfile .
# ai 담당 코드(pipelines/, ai/)를 같은 이미지에 넣어 PIPELINE_ENTRYPOINT로 불러올 수 있게 한다.
# 무시 목록은 ai/server/Dockerfile.dockerignore(루트 .dockerignore 대신 이 Dockerfile 전용).
#
# TODO(#86 후속): pipelines 의존성(docling, torch 등)과 모델 파일 처리 방식은 실제 자료 준비 파이프라인이
# 생길 때 ai 담당과 정한다. 지금은 설치하지 않으므로 PIPELINE_MODE=fake(기본)로만 동작한다.
FROM python:3.12-slim
WORKDIR /app
COPY ai/server/requirements.txt ai/server/requirements.txt
RUN pip install --no-cache-dir -r ai/server/requirements.txt
COPY pipelines/ pipelines/
COPY ai/ ai/
# 저장소 루트를 모듈 경로에 둔다: "pipelines.…", "ai.…"로 불러온다
ENV PYTHONPATH=/app
WORKDIR /app/ai/server
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
