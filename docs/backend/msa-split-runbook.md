# backend MSA 분리 — 배포·되돌리기 런북

backend를 auth·canvas·analysis 세 서비스로 나누는 전환(#193)의 배포 절차와 되돌리기 절차다.
밖에서 보는 API(경로·요청·응답·에러 코드·WebSocket 주소)와 JWT는 바뀌지 않는다.

## 구성 요약

| 서비스 | 스키마 | 내려받는 경로(Ingress) |
|---|---|---|
| auth | `auth` (users, login_attempt) | `/api/v1/auth`, `/api/v1/users` |
| canvas | `canvas` (canvas 등 7개) | `/api/v1/canvases`, `/api/v1/ws` |
| analysis(backend) | `app` (나머지) | `/api` (그 외) |

- 세 서비스는 같은 PostgreSQL을 쓰고, 마이그레이션은 analysis(backend)만 올린다.
- 서비스를 넘는 쓰기는 없다. 넘는 호출은 읽기 2곳뿐이다: canvas→auth(사람 이름), analysis→canvas(캔버스 권한). 둘 다 내부 키로만 부른다.
- 서비스를 넘는 외래키 16개는 해제했다. 참조 무결성은 각 서비스가 맡는다.

## 배포 순서

1. **백업** (아래 "백업"). 받은 뒤 로컬에 복원해 쓸 수 있는지 확인한다.
2. **기준 점검**: 전환 전 주요 API 응답을 기록한다(로그인·가입·내 정보·사용자 검색·캔버스 생성/조회/공유/이벤트·WebSocket·업로드·작업 조회).
3. `develop` → `main` 머지. CD가 이미지(바뀐 서비스만)를 올리고 `kubectl apply -k`로 배포한다.
   - 마이그레이션 V17이 users·캔버스 테이블을 auth·canvas 스키마로 옮기고 FK를 해제한다.
   - auth는 `auth`, canvas는 `canvas`, analysis는 `app` 스키마를 검증(validate)하며 뜬다.
   - Ingress가 `/api/v1/auth`·`/users`는 auth로, `/canvases`·`/ws`는 canvas로 보낸다.
4. **옛 카나리 Ingress 정리**(한 번만): #196에서 만든 `api-canary`가 남아 있으면 지운다. CD는 더 이상 이 이름을 다루지 않는다.
   ```bash
   kubectl -n p3-1 delete ingress api-canary --ignore-not-found
   ```
5. **전환 후 기준 점검**: 2번 목록을 다시 돌려 응답이 같은지 비교한다.
6. **격리 확인**: analysis(backend)를 재배포해도 canvas WebSocket 접속이 끊기지 않는지 본다.

## 백업

RDS는 PostgreSQL 18이다. 로컬 pg_dump 17로는 안 되고 같은 버전 컨테이너를 쓴다.
자격 증명은 `rds-credentials` 시크릿에서 환경변수로만 넘기고, 값은 로그에 남기지 않는다.

```bash
kubectl -n p3-1 port-forward deploy/pg-proxy 25432:5432 &
docker run --rm -e PGHOST=host.docker.internal -e PGPORT=25432 \
  -e PGUSER=... -e PGPASSWORD=... -e PGDATABASE=... -e PGSSLMODE=... \
  -v "$HOME/telescope-backups":/backup postgres:18 \
  pg_dump -Fc --no-owner --no-privileges -f /backup/telescope-rds-<시각>-before-193-split.dump
```

복원 확인: `vector` 확장이 켜져 있으나 쓰는 컬럼은 없다. 일반 postgres:18에 복원할 때는
`pg_restore -l`에서 `EXTENSION vector` 두 줄을 빼고 `-L`로 복원한다.

> 백업은 **마이그레이션을 올리는 배포 직전**에 받는다. 미뤄둔 마이그레이션이 그 배포에서 함께
> 적용될 수 있어, 오래된 백업은 운영과 다를 수 있다.

## 되돌리기 (단계별, 위에서부터)

문제의 크기에 맞춰 필요한 단계까지만 되돌린다.

1. **경로 원복**: Ingress를 되돌려 모든 `/api`를 analysis(backend)로 보낸다. (가장 빠르고, 코드·DB는 그대로)
   - `infra/k8s/ingress.yaml`의 `api`에서 `/api/v1/auth`·`/users`·`/canvases`·`/ws`를 다시 `backend`로 바꿔 배포한다.
   - 단, analysis가 그 기능을 다시 맡으려면 2번(이전 이미지)이 함께 필요하다.
2. **이전 이미지**: 직전 정상 이미지 태그로 롤백한다(`kubectl rollout undo` 또는 이전 태그로 재배포).
3. **역방향 마이그레이션**: 스키마를 app으로 되돌리고 FK를 복원한다(아래 SQL). 데이터 복사가 아니라 소속 변경이라 빠르다.
4. **백업 복원**: 위 단계로도 안 되면 백업에서 복원한다.

### 역방향 SQL (스키마 되돌리기 + FK 복원)

`search_path`를 app으로 두고 실행한다. 한 트랜잭션으로 돈다.

```sql
SET search_path TO app;
BEGIN;
ALTER TABLE auth.users         SET SCHEMA app;
ALTER TABLE auth.login_attempt SET SCHEMA app;
ALTER TABLE canvas.canvas         SET SCHEMA app;
ALTER TABLE canvas.canvas_share   SET SCHEMA app;
ALTER TABLE canvas.canvas_card    SET SCHEMA app;
ALTER TABLE canvas.canvas_link    SET SCHEMA app;
ALTER TABLE canvas.canvas_mapping SET SCHEMA app;
ALTER TABLE canvas.canvas_event   SET SCHEMA app;
ALTER TABLE canvas.canvas_request SET SCHEMA app;

ALTER TABLE analysis_run   ADD CONSTRAINT fk_analysis_run_requester    FOREIGN KEY (requested_by)   REFERENCES users (id);
ALTER TABLE chat_session   ADD CONSTRAINT fk_chat_session_user         FOREIGN KEY (user_id)        REFERENCES users (id);
ALTER TABLE data_sync_run  ADD CONSTRAINT fk_data_sync_run_requested_by FOREIGN KEY (requested_by)   REFERENCES users (id);
ALTER TABLE document_version ADD CONSTRAINT fk_document_version_uploader FOREIGN KEY (uploaded_by)   REFERENCES users (id);
ALTER TABLE document_version ADD CONSTRAINT fk_document_version_canvas   FOREIGN KEY (canvas_id)     REFERENCES canvas (id);
ALTER TABLE prediction_run ADD CONSTRAINT fk_prediction_run_requester   FOREIGN KEY (requested_by)   REFERENCES users (id);
ALTER TABLE canvas         ADD CONSTRAINT fk_canvas_owner               FOREIGN KEY (owner_id)       REFERENCES users (id);
ALTER TABLE canvas         ADD CONSTRAINT fk_canvas_last_edited_by      FOREIGN KEY (last_edited_by) REFERENCES users (id);
ALTER TABLE canvas_share   ADD CONSTRAINT fk_canvas_share_user          FOREIGN KEY (user_id)        REFERENCES users (id);
ALTER TABLE canvas_card    ADD CONSTRAINT fk_canvas_card_creator        FOREIGN KEY (created_by)     REFERENCES users (id);
ALTER TABLE canvas_card    ADD CONSTRAINT fk_canvas_card_memo_by        FOREIGN KEY (memo_by)        REFERENCES users (id);
ALTER TABLE canvas_link    ADD CONSTRAINT fk_canvas_link_creator        FOREIGN KEY (created_by)     REFERENCES users (id);
ALTER TABLE canvas_link    ADD CONSTRAINT fk_canvas_link_memo_by        FOREIGN KEY (memo_by)        REFERENCES users (id);
ALTER TABLE canvas_mapping ADD CONSTRAINT fk_canvas_mapping_user        FOREIGN KEY (decided_by)     REFERENCES users (id);
ALTER TABLE canvas_event   ADD CONSTRAINT fk_canvas_event_author        FOREIGN KEY (author_id)      REFERENCES users (id);
ALTER TABLE canvas_request ADD CONSTRAINT fk_canvas_request_author      FOREIGN KEY (author_id)      REFERENCES users (id);

DROP SCHEMA IF EXISTS auth;
DROP SCHEMA IF EXISTS canvas;
COMMIT;
```

> 역방향 후에는 각 서비스의 기본 스키마 설정(auth·canvas)과 Ingress 경로도 1·2단계로 함께 되돌려야
> 서비스가 다시 `app` 스키마를 본다. 되돌린 뒤 Flyway 기록에서 V17 행을 지워야 재적용된다.
