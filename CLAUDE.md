# tg-shorts-bot

A Telegram bot that sits in a friends' group chat, notices YouTube Shorts / Instagram Reels & posts / TikTok links,
downloads the media and posts it back into the chat as a reply to the original message.

- **Language:** plain Java 25 (no Spring, no DI framework). Small, well-known libraries only.
- **Run:** `docker compose up -d`, one command, nothing else to install on the host. Container name: `tg-shorts-bot`.
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
| 6 | Dockerfile + docker-compose + README | ✅ done, bot runs via compose |
| 7 | Outline / Shadowsocks VPN toggle | ⏸ postponed by the user, plan below |
| 8 | Hardening & polish | ✅ done (CI postponed, optional features left open) |

Each phase is one commit (fixes found while testing a phase go into that phase's commit or a follow-up).
Tests must stay green. Push to GitHub only when the user asks.

---

## Working on this repo

- Build + all tests: `./gradlew build` → `build/libs/tg-shorts-bot.jar` (shadow/fat jar). ~100 unit tests, no network.
- The host has Java 25 but **no yt-dlp/ffmpeg** and no Gradle install (the wrapper downloads Gradle).
  Real downloads and bot runs happen in Docker:
  - Run / redeploy the bot: `docker compose up -d --build` (container `tg-shorts-bot`, service `bot`).
    Only one instance may poll a token at a time, so stop any other copy first.
  - Logs: `docker compose logs -f` (or `docker logs tg-shorts-bot`).
  - Debug downloads: `docker compose exec -w /tmp bot java -cp /app/tg-shorts-bot.jar dev.shortsbot.DownloadCli <url>...`
    (prints items, sizes, dimensions and post text; files land in the container's `/tmp/downloads`).
- `.env` holds the real `BOT_TOKEN` and `ALLOWED_CHAT_IDS` (test group `-5230348538`, a basic group; if Telegram
  converts it to a supergroup the id changes to `-100…` and the bot logs "Ignoring messages from chat …").
  `.env`, `cookies.txt`, `secrets/` are git-ignored and must never be committed.
- Bot: `@shortsinchatbot` (replaced the earlier test bot `@jaccob_bot`). Privacy mode is disabled in @BotFather.

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
├── health/HeartbeatBackOff.java    wraps the polling back-off; writes /tmp/tg-shorts-bot.heartbeat on each successful poll
└── telegram/
    ├── ShortsBot.java              update consumer: allow-list, ignore bots/edits, /help, rate limit, dispatch to workers
    ├── LinkProcessor.java          in-flight dedupe → cache → download (1 retry) → send → cache; caption rules; errors
    ├── RateLimiter.java            sliding window of links per chat per minute
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
| `MAX_QUEUED_LINKS` | `20` | Bounded job queue; overflow is dropped with a log |
| `RATE_LIMIT_PER_MINUTE` | `10` | Per chat; `0` disables |
| `DOWNLOAD_DIR` | `/tmp/shortsbot` | Temp dir, emptied on startup |
| `YTDLP_PATH` | `yt-dlp` | Binary |
| `YTDLP_COOKIES_FILE` | empty | Netscape cookies; a per-job copy is passed (yt-dlp writes cookies back on exit) |
| `YTDLP_AUTO_UPDATE` | `false` | `yt-dlp -U` on startup and every 24 h |
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
  -S "vcodec:h264,res:720,ext:mp4:m4a"                (H.264 plays inline everywhere; TikTok defaults to H.265;
                                                       on TOO_LARGE the whole download is retried once with res:480)
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
- Each link → rate limit check (per chat) → job on a pool of `WORKER_THREADS` with a queue of `MAX_QUEUED_LINKS`;
  chat action `upload_video` (`upload_photo` for posts) every 4 s.
- The same `canonicalId` already in flight → the second job waits for the first, then answers from the cache.
- Cache hit (`SentPost` by `canonicalId`) → resend by file_id instantly; if Telegram rejects the file_id → evict and download.
- 1 item → `sendVideo` / `sendPhoto`; 2–10 → `sendMediaGroup`. Replies silently (`disable_notification`), with
  `allow_sending_without_reply`, in the same forum topic.
- Text: only for Instagram `/p/` posts (reels/shorts/TikToks are sent without caption). ≤ 1024 chars → caption;
  longer → media without caption + the text as a separate reply (truncated at 4096 without splitting emoji).
- Retries: one retry for `TIMEOUT`/`UNKNOWN` (3 s delay); Telegram 429 → wait `retry_after` (max 60 s, 3 attempts).
- Upload timeouts are **not** reported to the chat: Telegram may still deliver the media (this happened with the
  OkHttp 10 s default timeout, hence `TelegramHttp` with 120 s).
- `/start`, `/help` (also `/help@shortsinchatbot`) explain the bot; edited messages and messages from bots are ignored.
- Shutdown: stop polling, let jobs finish for 30 s, then interrupt (kills yt-dlp + ffmpeg).

---

## 6. Docker + one-command start (done)

**Goal:** `docker compose up -d` is the only thing needed on a machine with Docker.

- `Dockerfile`, multi-stage:
  - **build** `eclipse-temurin:25-jdk`: `./gradlew shadowJar -x test` with a BuildKit cache mount on `/root/.gradle`.
  - **tools** stage downloads static `ffmpeg`/`ffprobe` from yt-dlp's FFmpeg-Builds (`ffmpeg-master-latest-<arch>-gpl`)
    and the standalone yt-dlp binary for `TARGETARCH` (needs curl + xz only there).
  - **runtime** `eclipse-temurin:25-jre` (Ubuntu, has CA certificates) with no extra apt packages: ffmpeg/ffprobe in
    `/usr/local/bin`, yt-dlp in `/opt/yt-dlp` (on `PATH`) owned by the non-root user `bot` (uid 10001) so
    `YTDLP_AUTO_UPDATE` can replace it. `HEALTHCHECK` = heartbeat file younger than 3 min (interval 60 s, start 90 s).
    `ENTRYPOINT java -XX:MaxRAMPercentage=75 -jar /app/tg-shorts-bot.jar`.
  - Size: ~700 MB unpacked, 286 MB compressed (apt ffmpeg was 422 MB of layers; static is 267 MB). Docker Desktop's
    `docker images` shows ~1 GB because it counts compressed + unpacked. johnvansickle.com static ffmpeg would be
    ~100 MB but is older (7.0) and hosted on a personal site, so it wasn't used.
- `docker-compose.yml`: service `bot`, `container_name: tg-shorts-bot`, `restart: unless-stopped`, `env_file: .env`,
  `init: true` (tini reaps ffmpeg orphans of killed downloads), `stop_grace_period: 45s` (the bot waits 30 s for
  jobs), `./secrets:/app/secrets:ro` for an optional cookies.txt (a directory, so a missing file can't break startup),
  tmpfs `/tmp/shortsbot` (512 MB, mode 1777), json-file logs capped at 3×10 MB.
- `.dockerignore` keeps `.env`, `secrets/`, build output and `.git` out of the build context.
- `README.md`: user-facing setup (BotFather + privacy mode, `.env`, chat id from logs), config table, Instagram
  cookies, operating, updating yt-dlp, `DownloadCli`, development.
- Verified: runs as `bot` with tini as PID 1, temp dir and yt-dlp writable, real downloads work, `docker compose restart`
  shuts down gracefully.

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

## 8. Hardening & polish (done)

- **Health:** `HeartbeatBackOff` is passed to `TelegramBotsLongPollingApplication` as its back-off supplier. The session
  calls `reset()` after every successful `getUpdates` (idle polls return every 50 s), so that's where the heartbeat file
  `/tmp/tg-shorts-bot.heartbeat` is written (at most every 10 s). Docker marks the container unhealthy after ~3 min
  without a successful poll (network down, revoked token, another instance polling the same token → 409).
  Plain Docker doesn't restart unhealthy containers; it's for `docker ps`/monitoring.
- **yt-dlp freshness:** with `YTDLP_AUTO_UPDATE=true`, `yt-dlp -U` runs at startup and every 24 h on a `maintenance` thread
  (running downloads keep the old binary; the file is replaced atomically).
- **Smaller image:** static ffmpeg (see phase 6).
- **Abuse limits:** `RATE_LIMIT_PER_MINUTE` per chat (sliding window, denied links don't count), `ThreadPoolExecutor` with an
  `ArrayBlockingQueue(MAX_QUEUED_LINKS)` (overflow logged as "too many links waiting"), in-flight dedupe by `canonicalId`.
- **Too-large fallback:** `YtDlpDownloader` retries a `TOO_LARGE` download once at 480p.
- **CI: postponed by the user.** Planned: `.github/workflows/ci.yml` running `./gradlew build` and a cached
  `docker build` (no push) on pushes to main and PRs (actions/checkout@v7, setup-java@v6, gradle/actions/setup-gradle@v6,
  docker/setup-buildx-action@v4, docker/build-push-action@v7). Pushing workflow files needs a token with the
  `workflow` scope (`gh auth refresh -s workflow`), which the user's gh login doesn't have yet.
- **Optional features, not done** (only if wanted): TikTok photo posts (`/photo/`), delete the original message after
  posting (needs admin + config flag), caption with the poster's name, persistent cache on a volume, configurable reply
  language, auto-restart on unhealthy (e.g. exit the JVM after N minutes without a poll so `restart: unless-stopped` kicks in).
