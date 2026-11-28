# 빌드 컨텍스트는 저장소 루트: docker build -f services/ingestion-worker/Dockerfile .
FROM python:3.12-slim
WORKDIR /app
# 무거운 파싱·적재 의존성을 먼저(바뀔 일 드묾) → 캐시 보존. job-shell은 자주 바뀌므로 그 뒤에 설치
COPY services/ingestion-worker/requirements.txt services/ingestion-worker/requirements.txt
RUN pip install --no-cache-dir -r services/ingestion-worker/requirements.txt
COPY libs/job-shell/ libs/job-shell/
RUN pip install --no-cache-dir ./libs/job-shell
COPY services/ingestion-worker/ services/ingestion-worker/
ENV PYTHONPATH=/app/services/ingestion-worker
CMD ["uvicorn", "job_shell.main:app", "--host", "0.0.0.0", "--port", "8000"]
