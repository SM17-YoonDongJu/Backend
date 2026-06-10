# Backend

손해사정사 매칭 플랫폼 Spring Boot REST API 서버.

## Tech Stack

Java 21 · Spring Boot 4.0 · PostgreSQL 16 · Redis 7 · WebSocket(STOMP) · JWT · OAuth2 · AWS S3 · Firebase FCM

## 로컬 실행

```bash
# 1. 환경변수 설정
cp .env.example .env   # DB_PASSWORD, JWT_SECRET 등 필수값 입력

# 2. 인프라 기동 (PostgreSQL, Redis)
docker compose up -d

# 3. 앱 실행
./gradlew bootRun
```

서버: `http://localhost:8080`

## 주요 명령어

```bash
./gradlew build               # 빌드
./gradlew test                # 테스트
./gradlew bootJar -x test     # JAR 빌드 (테스트 제외)
docker compose --profile app up -d  # 전체 배포 (앱 포함)
```

## 구조

```
com.soma.backend
├── domain/      # auth · user · adjuster · report · match · chat · payment · subscription
├── global/      # security · exception · response · config
└── infra/       # redis · s3 · fcm
```
