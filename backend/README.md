# Telescope Backend

Spring Boot(Gradle, Java 21) 백엔드. 기본 프로파일이 `local`이라 DB 없이는 빌드/기동이 되지 않습니다.

## 사전 준비

- JDK 21
- Docker / Docker Compose (로컬 MySQL용)

## 1. 환경변수 설정

프로젝트 루트(`telescope/`)의 `.env.example`을 복사해 `.env`를 만들고 MySQL 관련 값을 채웁니다.

```bash
cd ..   # 프로젝트 루트로 이동
cp .env.example .env
```

```
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=telescope
MYSQL_USER=telescope
MYSQL_PASSWORD=원하는값
MYSQL_ROOT_PASSWORD=원하는값
```

이미 로컬에 3306 포트를 쓰는 MySQL이 있다면 `MYSQL_PORT`를 다른 값으로 바꾸세요.

## 2. 로컬 MySQL 띄우기

프로젝트 루트에서:

```bash
docker compose up -d mysql
```

## 3. 백엔드 실행

`docker compose`는 `.env`를 자동으로 읽지만, `./gradlew`로 직접 실행할 때는 Spring Boot가 `.env`를 자동으로 읽지 않습니다. 쉘 환경변수로 먼저 내보낸 뒤 실행하세요.

```bash
cd ..            # 프로젝트 루트 (.env가 있는 위치)
set -a; source .env; set +a
cd backend
./gradlew bootRun
```

IDE(IntelliJ 등)에서 실행한다면 Run Configuration의 Environment variables에 `.env`와 동일한 값을 넣어주세요.

## 4. 확인

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## 참고

- `spring.profiles.default: local`이 설정되어 있어, 프로파일을 따로 지정하지 않으면 `local`이 적용되고 MySQL 연결을 시도합니다.
- `./gradlew build`, `./gradlew test`도 컨텍스트를 띄워보는 테스트가 있어 MySQL이 떠 있어야 통과합니다.
- 원격 환경에서 다른 프로파일(`SPRING_PROFILES_ACTIVE=...`)을 쓰려면 해당 프로파일의 `application-{profile}.yml`을 추가하세요.
