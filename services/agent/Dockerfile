# 빌드 컨텍스트는 저장소 루트: docker build -f services/agent/Dockerfile .
FROM python:3.12-slim
WORKDIR /app
COPY libs/job-shell/ libs/job-shell/
RUN pip install --no-cache-dir ./libs/job-shell
CMD ["uvicorn", "job_shell.main:app", "--host", "0.0.0.0", "--port", "8000"]
