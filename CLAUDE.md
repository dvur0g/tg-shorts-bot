# tg-shorts-bot

A Telegram bot that sits in a friends' group chat, notices YouTube Shorts / Instagram Reels & posts / TikTok links,
downloads the media and posts it back into the chat as a reply to the original message.

- **Language:** plain Java 25 (no Spring, no DI framework). Small, well-known libraries only.
- **Run (target):** `docker compose up -d`, one command, nothing else to install on the host (phase 6).
- **VPN (phase 7):** optional, off by default, toggled by `VPN_ENABLED=true` + an Outline `ss://` key.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Gradle skeleton | ✅ done |
| 2 | Config + Telegram long polling | ✅ done, verified in the test group |
| 3 | Link detection | ✅ done, verified with real links |
| 4 | Downloading with yt-dlp | ✅ done, verified with real links |
| 5 | Sending to the chat, cache, errors, concurrency | ✅ done, verified in the test group |
| 5b | Instagram `/p/` posts: photos/albums + post text | ✅ done, verified in the test group |
| 6 | Dockerfile + docker-compose + README | ⏳ next |
| 7 | Outline / Shadowsocks VPN toggle | planned |
| 8 | Hardening & polish | planned |

Each phase is one commit (fixes found while testing a phase go into that phase's commit or a follow-up).
Tests must stay green. Push to GitHub only when the user asks.

---

## Working on this repo

- Build + all tests: `./gradlew build` → `build/libs/tg-shorts-bot.jar` (shadow/fat jar). ~90 unit tests, no network.
- The host has Java 25 but **no yt-dlp/ffmpeg** and no Gradle install (the wrapper downloads Gradle).
  Until phase 6 exists, real downloads/bot runs use a throwaway image built from:
  ```dockerfile
  FROM eclipse-temurin:25-jre
  ARG TARGETARCH
  RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl && rm -rf /var/lib/apt/lists/* \
   && if [ "$TARGETARCH" = "arm64" ]; then f=yt-dlp_linux_aarch64; else f=yt-dlp_linux; fi \
   && curl -fsSL -o /usr/local/bin/yt-dlp "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$f" && chmod +x /usr/local/bin/yt-dlp
  ```
  - Debug downloads: `docker run --rm -v "$PWD":/w -w /w <image> java -cp build/libs/tg-shorts-bot.jar dev.shortsbot.DownloadCli <url>...`
    (saves to `downloads/`, git-ignored; prints items, sizes, dimensions and post text).
  - Run the bot: `docker run -d --name shortsbot-dev-run --env-file .env -v <jar>:/app/tg-shorts-bot.jar:ro <image> java -jar /app/tg-shorts-bot.jar`.
    Don't rebuild a jar that a running JVM uses; copy it elsewhere or restart the bot.
- `.env` holds the real `BOT_TOKEN` and `ALLOWED_CHAT_IDS` (test group `-5230348538`, a basic group; if Telegram
  converts it to a supergroup the id changes to `-100…` and the bot logs "Ignoring messages from chat …").
  `.env`, `cookies.txt`, `secrets/` are git-ignored and must never be committed.
- Bot: `@jaccob_bot`. Privacy mode is already disabled in @BotFather.

---

## Key decisions (and why)

| Concern | Decision | Reason |
|---|---|---|
| Telegram API | `org.telegram:telegrambots-longpolling` + `telegrambots-client` **10.3.0** | Mature, plain Java, OkHttp-based → easy to route through a SOCKS proxy. Long polling = no public URL needed. |
| Downloading | `yt-dlp` CLI via `ProcessBuilder` | The only reliable, maintained extractor for all three sites. |
| Muxing / probing | `ffmpeg` / `ffprobe` | yt-dlp merges video+audio with it; ffprobe fills in missing Instagram dimensions. |
| Build | Gradle 9.8 (Kotlin DSL), Shadow plugin, wrapper committed, versions in `gradle/libs.versions.toml` | No Gradle installed locally; the wrapper plus a Docker build means the host only needs Docker. |
| Config | Environment variables (`.env`) | Same locally and in Docker. |
| Logging | SLF4J + Logback to console, level from `LOG_LEVEL` | `docker compose logs` is the log viewer. Message text is logged only at DEBUG. |
| VPN | `sslocal` (shadowsocks-rust) in the image, started by Java when enabled; SOCKS5 on `127.0.0.1:1080` | Outline keys are plain Shadowsocks. One container, one toggle. |

Known limits: Bot API uploads are max **50 MB per video**, **10 MB per photo**, albums of **2–10** items,
captions **1024** chars, messages **4096** chars. Instagram may require a login for some content → optional cookies file.
yt-dlp breaks when sites change → it must be kept up to date (phase 6/8).

---

## Architecture (as built)

```
src/main/java/dev/shortsbot/
├── Main.java                       wiring, startup checks (yt-dlp version, getMe, privacy-mode warning), graceful shutdown
├── DownloadCli.java                debug tool: download links with the bot's settings into downloads/
├── config/BotConfig.java           record built from env, validation, secrets hidden in toString()
├── config/ConfigException.java
├── link/Platform.java              YOUTUBE, INSTAGRAM, TIKTOK
├── link/DetectedLink.java          (platform, normalized url, canonicalId) + isInstagramPost()
├── link/LinkExtractor.java         entities + regex scan → normalized, deduped links (max 5 per message)
├── download/VideoDownloader.java   interface: download(link) → DownloadResult
├── download/YtDlpDownloader.java   runs yt-dlp per job in its own temp dir, collects items, classifies errors
├── download/DownloadResult.java    (items, caption, title, workDir); close() deletes workDir
├── download/MediaItem.java         (type, file, size, width, height, duration)
├── download/MediaType.java         VIDEO, PHOTO
├── download/DownloadException.java reason: TOO_LONG, TOO_LARGE, NOT_A_VIDEO, LOGIN_REQUIRED, UNAVAILABLE, TIMEOUT, UNKNOWN
├── cache/LruCache.java             generic thread-safe LRU (500 entries of SentPost)
└── telegram/
    ├── ShortsBot.java              update consumer: allow-list, ignore bots/edits, /help, dispatch links to worker pool
    ├── LinkProcessor.java          cache → download (1 retry) → send → cache; caption rules; error replies
    ├── ChatGateway.java            interface over Telegram calls (faked in tests)
    ├── TelegramChatGateway.java    sendVideo / sendPhoto / sendMediaGroup / reply / chat action; honors 429 retry_after
    ├── TelegramHttp.java           OkHttp client for API calls (30 s connect, 120 s read/write)
    ├── ReplyTarget.java            chat id, forum topic id, message id to reply to
    ├── OutgoingMedia.java          media to send: local file or Telegram file_id
    ├── SentPost.java               cached file_ids + caption for a link
    └── FailureMessages.java        short user-facing error texts
```

### Configuration (`.env.example` documents all of it)

| Variable | Default | Meaning |
|---|---|---|
| `BOT_TOKEN` | required | From BotFather |
| `ALLOWED_CHAT_IDS` | empty = all | Comma-separated chat ids |
| `MAX_DURATION_SEC` | `180` | Skip longer videos |
| `MAX_FILE_MB` | `49` | Per-video cap (1–50) |
| `DOWNLOAD_TIMEOUT_SEC` | `120` | Kill yt-dlp (and its ffmpeg children) after this |
| `WORKER_THREADS` | `2` | Parallel downloads |
| `DOWNLOAD_DIR` | `/tmp/shortsbot` | Temp dir, emptied on startup |
| `YTDLP_PATH` | `yt-dlp` | Binary |
| `YTDLP_COOKIES_FILE` | empty | Netscape cookies; a per-job copy is passed (yt-dlp writes cookies back on exit) |
| `YTDLP_AUTO_UPDATE` | `false` | `yt-dlp -U` on startup |
| `REPLY_WITH_ERRORS` | `true` | Short reply when something can't be fetched |
| `VPN_ENABLED` / `VPN_SS_URL` | `false` / empty | Phase 7; enabling without a URL is a config error; currently only warns "not supported yet" |
| `LOG_LEVEL` | `INFO` | Logback root level |

### Link detection (phase 3)
- Candidates come from `url` / `text_link` entities in text **and** captions, plus a regex scan of the raw text
  (the scan only matches after whitespace/opening bracket, so links embedded in other URLs are ignored).
- Supported: `youtube.com/shorts/{id}`, `youtu.be/{id}`; Instagram `/reel/`, `/reels/`, `/p/`, `/{user}/reel/`;
  TikTok `/@user/video/{id}`, `vm.`/`vt.tiktok.com/{code}`, `tiktok.com/t/{code}`, `m.tiktok.com/v/{id}.html`.
- Ignored: `youtube.com/watch`, channels/profiles, stories, TikTok `/photo/`, look-alike domains.
- Tracking params dropped; `canonicalId` (`youtube:ID`, `instagram:ID`, `tiktok:ID`, `tiktok-short:CODE`) is the cache key.

### Downloading (phases 4, 5b)
Command (args list, never a shell string):
```
yt-dlp --no-progress --no-warnings --no-colors --restrict-filenames --socket-timeout 20
  --match-filter "duration <=? MAX_DURATION_SEC"      (<=? lets unknown durations through, e.g. Instagram)
  --max-filesize MAX_FILE_MB M
  -S "vcodec:h264,res:720,ext:mp4:m4a"                (H.264 plays inline everywhere; TikTok defaults to H.265)
  --merge-output-format mp4 --remux-video mp4
  --write-info-json --no-write-playlist-metafiles
  -o "<workdir>/item-%(playlist_index|0)s.%(ext)s"
  Instagram /p/ posts: --playlist-items 1:10 --ignore-no-formats-error --write-thumbnail --convert-thumbnails jpg
  everything else:     --no-playlist --playlist-items 1
  [--cookies <per-job copy>]  [--proxy socks5://127.0.0.1:1080 (phase 7)]
  -- <url>
```
Gotchas learned from real runs:
- yt-dlp exits **0 with no file** when `--match-filter` / `--max-filesize` rejects a video (stdout: `does not pass filter`
  / `larger than max-filesize`), possibly leaving a partial `item-0.fNNN.mp4`.
- For Instagram photo posts it exits **1** ("No video formats found") although the images were saved.
- So success is decided **by the files**, not the exit code: `item-N.mp4` → video; else `item-N.jpg` with empty
  `formats` in `item-N.info.json` → photo (the full-size image is the item's "thumbnail"); else skipped.
  Nothing sendable → classify by stderr (exit ≠ 0) or stdout (exit 0).
- Instagram gives no width/height/duration without login → `ffprobe` fallback.
- Oversize items (photo > 10 MB, video > `MAX_FILE_MB`) are dropped; the rest of a post is still sent.
- gallery-dl was rejected (needs Instagram login); the Instagram embed page carries no data without running JS.

### Sending (phases 5, 5b)
- Each link → job on a fixed pool of `WORKER_THREADS`; chat action `upload_video` (`upload_photo` for posts) every 4 s.
- Cache hit (`SentPost` by `canonicalId`) → resend by file_id instantly; if Telegram rejects the file_id → evict and download.
- 1 item → `sendVideo` / `sendPhoto`; 2–10 → `sendMediaGroup`. Replies silently (`disable_notification`), with
  `allow_sending_without_reply`, in the same forum topic.
- Text: only for Instagram `/p/` posts (reels/shorts/TikToks are sent without caption). ≤ 1024 chars → caption;
  longer → media without caption + the text as a separate reply (truncated at 4096 without splitting emoji).
- Retries: one retry for `TIMEOUT`/`UNKNOWN` (3 s delay); Telegram 429 → wait `retry_after` (max 60 s, 3 attempts).
- Upload timeouts are **not** reported to the chat: Telegram may still deliver the media (this happened with the
  OkHttp 10 s default timeout, hence `TelegramHttp` with 120 s).
- `/start`, `/help` (also `/help@jaccob_bot`) explain the bot; edited messages and messages from bots are ignored.
- Shutdown: stop polling, let jobs finish for 30 s, then interrupt (kills yt-dlp + ffmpeg).

---

## 6. Docker + one-command start (next)

**Goal:** `docker compose up -d` is the only thing needed on a machine with Docker.

`Dockerfile` (multi-stage):
1. **build stage** `eclipse-temurin:25-jdk`: copy `gradlew`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts`
   first and resolve dependencies (layer cache), then sources, `./gradlew shadowJar --no-daemon -x test`.
   BuildKit cache mount for `/root/.gradle`.
2. **runtime stage** `eclipse-temurin:25-jre`, the same as the dev image above (ffmpeg, ca-certificates, yt-dlp standalone
   binary by `TARGETARCH`), plus: non-root user that owns the yt-dlp binary (so `YTDLP_AUTO_UPDATE` works),
   `WORKDIR /app`, copy jar, `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/tg-shorts-bot.jar"]`.

`docker-compose.yml`:
```yaml
services:
  bot:
    build: .
    image: tg-shorts-bot:latest
    restart: unless-stopped
    env_file: .env
    volumes:
      - ./secrets:/app/secrets:ro     # optional cookies.txt → YTDLP_COOKIES_FILE=/app/secrets/cookies.txt
    tmpfs:
      - /tmp/shortsbot:size=512m
    logging:
      driver: json-file
      options: { max-size: "10m", max-file: "3" }
```
Mount a directory, not a single file, so a missing cookies file doesn't break startup (the bot already just warns).

`README.md`: what the bot does, BotFather steps (privacy mode!), `cp .env.example .env`, `docker compose up -d`,
`docker compose logs -f`, getting the chat id from the logs, exporting Instagram cookies, updating yt-dlp
(`docker compose build --pull --no-cache && docker compose up -d`), `DownloadCli` for debugging.

**Checkpoint:** with only Docker: clone → fill `.env` → `docker compose up -d` → bot works in the group.

---

## 7. VPN via Outline (`ss://`), optional, default off

**Goal:** with `VPN_ENABLED=true`, *all* outbound traffic (Telegram API **and** yt-dlp) goes through the Outline server.
Countries that block Instagram/TikTok/YouTube often block Telegram too.

1. **`proxy/ShadowsocksUrl`** parses: SIP002 `ss://BASE64URL(method:password)@host:port/?outline=1#Name`;
   legacy `ss://BASE64(method:password@host:port)#Name`; dynamic `ssconf://…` (fetch via `https://`, response is an
   `ss://` string or JSON `{server, server_port, password, method}`). Record `(method, password, host, port)`; never log
   the password. Unit tests: padded/unpadded, URL-safe base64, percent-encoding, IPv6 host.
2. **Image:** download the `shadowsocks-rust` release for `TARGETARCH`, put `sslocal` in `/usr/local/bin` (needs `xz-utils`).
3. **`proxy/ShadowsocksSidecar`** (started before Telegram/yt-dlp are touched): run `sslocal` with a `0600` temp JSON
   config (password not visible in `ps`), bind `127.0.0.1:1080`, pipe output to the logger as `[sslocal]`, wait for the
   port, probe `https://api.telegram.org` through the proxy, restart with backoff if it dies, kill it on shutdown.
4. **Routing**, one `ProxySettings` (`Optional<InetSocketAddress>`) built in `Main`:
   - Telegram: `TelegramHttp.newClient()` gets `.proxy(new Proxy(SOCKS, …))`; the long-polling application gets the same
     via its `Supplier<OkHttpClient>` constructor (it has its own client with 100 s read timeout). OkHttp resolves DNS
     through SOCKS.
   - yt-dlp: `--proxy socks5://127.0.0.1:1080` (covers videos, Instagram photos, everything).
   - `VPN_ENABLED=false` → behavior identical to before. Remove the "not supported yet" warning in `Main`.
5. Compose unchanged; only `.env` gets `VPN_ENABLED=true` and `VPN_SS_URL=ss://…`.

**Checkpoint:** logs show sslocal started + probe OK; media still arrives; egress IP inside the container
(`curl --socks5-hostname 127.0.0.1:1080 https://ifconfig.me`) equals the Outline server's IP.

---

## 8. Hardening & polish (after it all works)

- **Health:** Docker `HEALTHCHECK` on a heartbeat file the poller updates.
- **yt-dlp freshness:** `YTDLP_AUTO_UPDATE=true` and/or a README note to rebuild regularly.
- **Abuse limits:** per-chat rate limit (e.g. 10 links/min), bounded job queue (drop with a log when full);
  dedupe the same link while it's still in flight.
- **Too-large fallback:** if a video is > `MAX_FILE_MB`, retry once with `-S "res:480"`.
- **Optional features** (only if wanted): TikTok photo posts (`/photo/`), delete the original message after posting
  (needs admin + config flag), caption with the poster's name, persistent cache on a volume, configurable reply language.
- **CI:** GitHub Actions running `./gradlew build` and `docker build`.
