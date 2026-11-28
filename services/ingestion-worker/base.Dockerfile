# 무거운 파싱·적재 의존성(torch·docling 등)만 담는 base 이미지.
# requirements.txt가 바뀔 때만 재빌드되고, ingest 이미지는 이걸 FROM 해서 코드만 얹는다.
# 빌드 컨텍스트는 저장소 루트.
FROM python:3.12-slim
WORKDIR /app
COPY services/ingestion-worker/requirements.txt services/ingestion-worker/requirements.txt
RUN pip install --no-cache-dir -r services/ingestion-worker/requirements.txt
