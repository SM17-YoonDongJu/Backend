FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# GC 로그·힙덤프 저장 위치
RUN mkdir -p /app/logs

COPY --from=builder /app/build/libs/*.jar app.jar

# JVM 옵션: 컨테이너 메모리 한계 인식(MaxRAMPercentage) + G1 GC + GC 로그(회전) + OOM 힙덤프
# 배포 시 docker-compose 등에서 JAVA_OPTS 로 오버라이드 가능
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 \
-XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof \
-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"

# 빌드한 커밋을 /actuator/info(app.commit)로 내보낸다. CI가 build-arg로 넘기고, 배포 검증이 이 값으로
# 새 이미지가 실제로 떴는지 확인한다. 매 빌드마다 바뀌는 값이라 캐시를 깨지 않게 맨 뒤에 둔다.
ARG GIT_COMMIT=unknown
ENV GIT_COMMIT=${GIT_COMMIT}

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
