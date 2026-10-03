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

# ---- tools: static ffmpeg/ffprobe and yt-dlp, fetched here so the runtime image needs no apt packages ----
FROM eclipse-temurin:25-jre AS tools
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl xz-utils && rm -rf /var/lib/apt/lists/*
WORKDIR /out
# yt-dlp's own FFmpeg builds: static, current, and patched for yt-dlp (about half the size of apt's ffmpeg).
RUN case "$TARGETARCH" in arm64) arch=linuxarm64 ;; *) arch=linux64 ;; esac \
 && curl -fsSL "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-${arch}-gpl.tar.xz" \
    | tar -xJ --strip-components=2 --wildcards '*/bin/ffmpeg' '*/bin/ffprobe' \
 && ./ffmpeg -version | head -1
RUN case "$TARGETARCH" in arm64) f=yt-dlp_linux_aarch64 ;; *) f=yt-dlp_linux ;; esac \
 && curl -fsSL -o yt-dlp "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$f" \
 && chmod 755 yt-dlp \
 && ./yt-dlp --version

# ---- runtime: Java + ffmpeg + yt-dlp ----
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --uid 10001 bot
COPY --from=tools /out/ffmpeg /out/ffprobe /usr/local/bin/
# yt-dlp lives in a directory owned by the bot user so YTDLP_AUTO_UPDATE (yt-dlp -U) can replace it.
COPY --from=tools --chown=bot:bot /out/yt-dlp /opt/yt-dlp/yt-dlp
RUN chown bot:bot /opt/yt-dlp
ENV PATH="/opt/yt-dlp:${PATH}"

WORKDIR /app
COPY --from=build /src/build/libs/tg-shorts-bot.jar /app/tg-shorts-bot.jar
USER bot

# The bot touches this file after every successful poll of Telegram (idle polls return every 50 s).
HEALTHCHECK --interval=60s --timeout=5s --start-period=90s --retries=3 \
    CMD find /tmp/tg-shorts-bot.heartbeat -mmin -3 | grep -q . || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/tg-shorts-bot.jar"]
