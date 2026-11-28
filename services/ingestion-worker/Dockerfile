# 빌드 컨텍스트는 저장소 루트: docker build -f services/ingestion-worker/Dockerfile .
FROM python:3.12-slim
WORKDIR /app
COPY libs/job-shell/ libs/job-shell/
RUN pip install --no-cache-dir ./libs/job-shell
COPY services/ingestion-worker/requirements.txt services/ingestion-worker/requirements.txt
RUN pip install --no-cache-dir -r services/ingestion-worker/requirements.txt
COPY services/ingestion-worker/ services/ingestion-worker/
ENV PYTHONPATH=/app/services/ingestion-worker
CMD ["uvicorn", "job_shell.main:app", "--host", "0.0.0.0", "--port", "8000"]
