# syntax=docker/dockerfile:1

# ---- build: compile the fat jar ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# Tests run locally / in CI; the image build only packages. The cache mount keeps Gradle and dependencies between builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --quiet shadowJar -x test

# ---- runtime: Java + ffmpeg + yt-dlp ----
FROM eclipse-temurin:25-jre
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# yt-dlp as the official standalone binary, in a directory owned by the bot user so YTDLP_AUTO_UPDATE (yt-dlp -U) can replace it.
RUN useradd --system --create-home --uid 10001 bot \
 && mkdir /opt/yt-dlp \
 && case "$TARGETARCH" in arm64) f=yt-dlp_linux_aarch64 ;; *) f=yt-dlp_linux ;; esac \
 && curl -fsSL -o /opt/yt-dlp/yt-dlp "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$f" \
 && chmod 755 /opt/yt-dlp/yt-dlp \
 && chown -R bot:bot /opt/yt-dlp \
 && /opt/yt-dlp/yt-dlp --version
ENV PATH="/opt/yt-dlp:${PATH}"

WORKDIR /app
COPY --from=build /src/build/libs/tg-shorts-bot.jar /app/tg-shorts-bot.jar
USER bot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/tg-shorts-bot.jar"]
