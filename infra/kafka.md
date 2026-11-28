# Kafka (이벤트 스트리밍) — 접속·토픽 규약·로컬 개발

## 구성 요약

- 배포: KRaft 단일 브로커(ZooKeeper 없음). 팀 규모·볼륨 쿼터에 맞춘 가벼운 구성.
- 접근: **클러스터 내부 전용** `kafka:9092`. 외부 노출(인그레스) 없음.
- 토픽: 자동 생성 끔. 초기 토픽은 선언적으로 만든다.
- 복제: 단일 브로커라 replication factor 1.
- 메시지: JSON으로 시작. 스키마 레지스트리는 메시지 계약이 복잡해질 때 후속.
- 볼륨: PVC 1개(gp3, 5Gi).

## 접속

| 환경 | 주소 | 비고 |
|---|---|---|
| 클러스터 내부(운영) | `kafka:9092` | 헤드리스 Service. `KAFKA_BOOTSTRAP_SERVERS`로 주입 |
| 로컬 — 컨테이너 안에서(docker-compose) | `kafka:9092` | 같은 compose 네트워크 |
| 로컬 — 호스트에서(IDE·bootRun) | `localhost:29092` | compose가 29092를 호스트로 매핑 |

백엔드는 `KAFKA_BOOTSTRAP_SERVERS` 환경변수만 읽는다. 주소를 코드에 박지 않는다.

## 토픽 명명 규약

```
telescope.<도메인>.<이벤트>
```

- `<도메인>`: 서비스/업무 영역. 예: `canvas`, `job`, `ingest`.
- `<이벤트>`: 과거형 사실(무엇이 일어났는가). 예: `edited`, `requested`, `completed`.
- 예: `telescope.canvas.edited`, `telescope.job.requested`, `telescope.ingest.completed`.

**자동 생성은 꺼져 있다.** 새 토픽이 필요하면 선언적으로 추가한다(매니페스트 또는 생성 Job). 토픽 추가는 인프라에 요청하고, 파티션·보존 기간은 그때 함께 정한다.

## 메시지 예(참고)

아래는 스키마 형태 예시일 뿐이다.

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-11-28T09:00:00Z",
  "canvasId": "…",
  "payload": { }
}
```

## 로컬 개발

```bash
docker compose up -d kafka
```

호스트에서 `localhost:29092`로 붙는다. 토픽 생성·produce/consume 예:

```bash
# 토픽 생성
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create \
  --topic telescope.canvas.edited --partitions 1 --replication-factor 1

# produce
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic telescope.canvas.edited

# consume
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic telescope.canvas.edited --from-beginning
```

## 되돌리기

- 매니페스트: `infra/k8s/kustomization.yaml`에서 `kafka.yaml`을 빼면 배포에서 제외된다.
- 데이터: StatefulSet을 지워도 PVC(`data-kafka-0`)는 남는다. 완전히 지우려면 PVC도 따로 삭제한다.
- 로컬: `docker compose down kafka`(볼륨까지 지우려면 `-v`).
