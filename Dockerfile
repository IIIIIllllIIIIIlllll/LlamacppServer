FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /src

COPY lib ./lib
COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

RUN set -eux; \
    mkdir -p /build/classes /build/lib; \
    cp lib/*.jar /build/lib/; \
    CLASSPATH="$(printf ':%s' lib/*.jar)"; \
    CLASSPATH="${CLASSPATH#:}"; \
    find src/main/java -name "*.java" -print0 | xargs -0 javac --release 21 -encoding UTF-8 -d /build/classes -cp "${CLASSPATH}"; \
    cp -r src/main/resources/* /build/classes/

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=builder /build/classes ./classes
COPY --from=builder /build/lib ./lib

RUN mkdir -p config cache logs models llamacpp downloads

EXPOSE 8080 8070 11434 1234 8075

VOLUME ["/app/config", "/app/cache", "/app/logs", "/app/models", "/app/llamacpp", "/app/downloads"]

ENV JAVA_OPTS="-Xms512m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -classpath './classes:./lib/*' org.mark.llamacpp.server.LlamaServer"]
