# 무거운 deps는 base 이미지(base.Dockerfile)에 있고, 여기선 job-shell과 코드만 얹는다.
# INGEST_BASE는 CI/CD가 ECR의 base 태그로 넘긴다.
# 로컬: docker build -f services/ingestion-worker/base.Dockerfile -t telescope-ingestion-base:local . 를 먼저 한 뒤 빌드.
# 빌드 컨텍스트는 저장소 루트.
ARG INGEST_BASE=telescope-ingestion-base:local
FROM ${INGEST_BASE}
WORKDIR /app
COPY libs/job-shell/ libs/job-shell/
RUN pip install --no-cache-dir ./libs/job-shell
COPY services/ingestion-worker/ services/ingestion-worker/
ENV PYTHONPATH=/app/services/ingestion-worker
CMD ["uvicorn", "job_shell.main:app", "--host", "0.0.0.0", "--port", "8000"]
