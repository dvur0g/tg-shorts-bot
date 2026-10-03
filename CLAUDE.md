# tg-shorts-bot — Implementation Plan (CLAUDE.md)

A Telegram bot that sits in a group chat, notices YouTube Shorts / Instagram Reels / TikTok links,
downloads the video and posts it back into the chat as a reply to the original message.

- **Language:** plain Java 25 (no Spring, no DI framework). Small, well-known libraries only.
- **Run:** `docker compose up -d` — one command, nothing else to install on the host.
- **VPN (later phase):** optional, off by default, toggled by `VPN_ENABLED=true` + an Outline `ss://` key.

Each phase ends with a **Checkpoint** — a concrete thing that must work before moving on.

---

## 0. Key decisions (and why)

| Concern | Decision | Reason |
|---|---|---|
| Telegram API | `org.telegram:telegrambots-longpolling` + `telegrambots-client` (v9.x) | Mature, plain Java, OkHttp-based → easy to route through a SOCKS proxy later. Long polling = no public URL/HTTPS needed. |
| Downloading | `yt-dlp` CLI called via `ProcessBuilder` | The only reliable, constantly maintained extractor for all three sites. Reimplementing in Java is a losing battle. |
| Muxing / re-encoding | `ffmpeg` (used by yt-dlp) | yt-dlp needs it to merge separate video+audio streams into one mp4. |
| Build | Maven, fat jar via `maven-shade-plugin`; Maven Wrapper (`mvnw`) committed | No Maven installed locally; wrapper + multi-stage Docker build means the host needs only Docker. |
| Config | Environment variables (`.env` file read by compose) | Simple, 12-factor, works the same locally and in Docker. |
| Logging | SLF4J + Logback, console output | `docker compose logs` is the log viewer. |
| VPN | `sslocal` (shadowsocks-rust) bundled in the image, started by the Java app as a child process when enabled; exposes SOCKS5 on `127.0.0.1:1080` | Outline keys are plain Shadowsocks. One container, one toggle, no compose profiles needed. Java just talks to a local SOCKS5 proxy. |

### Telegram-side prerequisites (manual, done by you)
1. Create the bot with **@BotFather** → get `BOT_TOKEN`.
2. **`/setprivacy` → Disable.** Otherwise in groups the bot only sees `/commands` and mentions and will never see links. (If the bot was already in the group, remove and re-add it after changing this.)
3. Optional: `/setjoingroups` to control who can add it.
4. Add the bot to the friends group. To get the group's chat id, the bot will log it on the first message (see phase 2).

### Known limits to design around
- **Bot API upload limit is 50 MB** (`sendVideo`). Shorts/Reels/TikToks are almost always far below this; we select ≤720p mp4 and reject/re-encode anything larger.
- **Instagram** often requires a logged-in session → support an optional `cookies.txt` (Netscape format) mounted into the container.
- **yt-dlp breaks when sites change** → image installs the latest yt-dlp at build time, plus an optional self-update on startup.

---

## 1. Project skeleton

**Goal:** an empty runnable Java app that builds with Maven and in Docker.

Layout:
```
tg-shorts-bot/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/wrapper/
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── .gitignore            (.env, cookies.txt, target/, downloads/)
├── README.md
└── src/
    ├── main/java/dev/shortsbot/
    │   ├── Main.java
    │   ├── config/BotConfig.java
    │   ├── telegram/ShortsBot.java            (update consumer)
    │   ├── telegram/VideoSender.java
    │   ├── link/Platform.java                 (enum YOUTUBE_SHORTS, INSTAGRAM_REEL, TIKTOK)
    │   ├── link/DetectedLink.java             (record: platform, url, canonicalId)
    │   ├── link/LinkExtractor.java
    │   ├── download/VideoDownloader.java      (interface)
    │   ├── download/YtDlpDownloader.java
    │   ├── download/DownloadResult.java       (record: file, width, height, duration, title)
    │   ├── download/DownloadException.java
    │   ├── cache/FileIdCache.java
    │   └── proxy/ (phase 6)
    │       ├── ShadowsocksUrl.java
    │       └── ShadowsocksSidecar.java
    ├── main/resources/logback.xml
    └── test/java/dev/shortsbot/...
```

Steps:
1. `git init`, `.gitignore`.
2. `pom.xml`: Java 25 release, dependencies — `telegrambots-longpolling`, `telegrambots-client`, `slf4j-api`, `logback-classic`, `jackson-databind` (for yt-dlp JSON output); test — `junit-jupiter`, `assertj`. Plugins — `maven-surefire`, `maven-shade` (main class `dev.shortsbot.Main`).
3. Generate the Maven Wrapper (via a throwaway `maven` Docker container: `docker run --rm -v "$PWD":/w -w /w maven:3-eclipse-temurin-25 mvn wrapper:wrapper`).
4. `Main.java` that loads config and logs "starting".

**Checkpoint:** `./mvnw -q package` produces `target/tg-shorts-bot.jar`; `java -jar` prints the start log.

---

## 2. Configuration + minimal Telegram bot

**Goal:** bot connects, receives group messages, logs them.

`BotConfig` (immutable record, built from `System.getenv()` with validation and defaults):

| Variable | Default | Meaning |
|---|---|---|
| `BOT_TOKEN` | — (required) | From BotFather |
| `ALLOWED_CHAT_IDS` | empty = allow all | Comma-separated chat ids; protects against strangers adding the bot |
| `MAX_DURATION_SEC` | `180` | Skip videos longer than this |
| `MAX_FILE_MB` | `49` | Hard cap under the 50 MB Bot API limit |
| `DOWNLOAD_TIMEOUT_SEC` | `120` | Kill yt-dlp after this |
| `WORKER_THREADS` | `2` | Parallel downloads |
| `DOWNLOAD_DIR` | `/tmp/shortsbot` | Temp working dir |
| `YTDLP_PATH` | `yt-dlp` | Binary location |
| `YTDLP_COOKIES_FILE` | empty | Optional cookies.txt (mainly Instagram) |
| `YTDLP_AUTO_UPDATE` | `false` | Run `yt-dlp -U` on startup |
| `REPLY_WITH_ERRORS` | `true` | Post a short "couldn't download" reply on failure |
| `VPN_ENABLED` | `false` | Phase 6 |
| `VPN_SS_URL` | empty | Phase 6, Outline `ss://…` (or `ssconf://…`) key |
| `LOG_LEVEL` | `INFO` | |

Steps:
1. Implement `BotConfig.fromEnv()` — fail fast with a clear message if `BOT_TOKEN` is missing or numbers don't parse.
2. `ShortsBot implements LongPollingSingleThreadUpdateConsumer`; in `consume(Update)` log chat id, chat type, sender, text.
3. `Main`: build `OkHttpTelegramClient` + `TelegramBotsLongPollingApplication`, register bot, add a shutdown hook that closes everything.
4. Ignore updates from chats not in `ALLOWED_CHAT_IDS` (log their id at INFO once so you can copy it into `.env`).

**Checkpoint:** run locally with `BOT_TOKEN=... java -jar ...`, write in the group → message + chat id appear in logs.

---

## 3. Link detection

**Goal:** turn arbitrary message text into a list of supported video links.

`LinkExtractor.extract(Message)`:
1. Collect candidate URLs from **message entities** (`url` and `text_link` types) — more reliable than regex on raw text — plus `caption` entities (links forwarded with media). Fallback to a regex over text if there are no entities.
2. Match each candidate against per-platform patterns (case-insensitive, optional `www.`/`m.`, ignore query string):
   - YouTube Shorts: `youtube.com/shorts/{id}`, `youtu.be/{id}` *(accepted, but duration limit protects us from full-length videos)*
   - Instagram: `instagram.com/reel/{id}`, `/reels/{id}`, `/p/{id}` (posts that are videos — yt-dlp will error on photos; handle gracefully)
   - TikTok: `tiktok.com/@user/video/{id}`, `vm.tiktok.com/{code}`, `vt.tiktok.com/{code}`, `tiktok.com/t/{code}`
3. Return `DetectedLink(platform, normalizedUrl, canonicalId)`; `canonicalId` = `platform:id` used for caching/dedup. De-duplicate within one message; cap at e.g. 5 links per message.

Unit tests (table-driven): every URL shape above, with/without `www`, tracking params (`?igsh=…`, `?si=…`), trailing slashes, multiple links in one message, unrelated links (regular `youtube.com/watch`, other sites) → ignored.

**Checkpoint:** `./mvnw test` green; bot logs "detected TIKTOK link …" for real messages.

---

## 4. Downloading with yt-dlp

**Goal:** `VideoDownloader.download(DetectedLink) → DownloadResult` producing a Telegram-friendly mp4.

`YtDlpDownloader`:
1. Create a unique temp dir per job under `DOWNLOAD_DIR`.
2. Build the command (list of args, never a shell string — no injection risk):
   ```
   yt-dlp
     --no-playlist --no-progress --no-warnings
     --restrict-filenames
     --match-filter "duration <= ${MAX_DURATION_SEC}"
     --max-filesize ${MAX_FILE_MB}M
     -S "vcodec:h264,res:720,ext:mp4:m4a"
     --merge-output-format mp4
     --remux-video mp4
     -o "<tmpdir>/video.%(ext)s"
     --print-json            (metadata on stdout → width/height/duration/title)
     [--cookies <file>]      (if configured)
     [--proxy socks5://127.0.0.1:1080]   (phase 6, if VPN enabled)
     <url>
   ```
   `h264` + mp4 matters: Telegram inline-plays it on every client (TikTok/Instagram sometimes serve HEVC, which shows as a black box on some phones).
3. Run with `ProcessBuilder`, read stdout/stderr on separate threads (avoid pipe-buffer deadlock), `waitFor(timeout)`; on timeout `destroyForcibly()`.
4. Map outcomes to `DownloadException` with a reason enum: `TOO_LONG`, `TOO_LARGE`, `NOT_A_VIDEO`, `LOGIN_REQUIRED`, `UNAVAILABLE`, `TIMEOUT`, `UNKNOWN` (by exit code + stderr patterns). Log full stderr at DEBUG.
5. Parse JSON metadata with Jackson; locate the resulting `video.mp4`; verify size ≤ `MAX_FILE_MB`.
6. Caller is responsible for deleting the temp dir (try/finally) — plus a startup sweep that clears leftovers.

Tests: unit-test command building; an integration test using a **fake `yt-dlp` shell script** (configured via `YTDLP_PATH`) that writes a dummy file + JSON, and one that sleeps (timeout path) / exits 1 with known stderr (error mapping).

**Checkpoint:** a tiny dev entrypoint (or test marked `@Tag("manual")`) downloads one real link of each platform to disk.

---

## 5. Sending to the chat + robustness

**Goal:** the full user-facing flow.

Flow per detected link (submitted to a fixed thread pool of `WORKER_THREADS`, so the polling thread is never blocked):
1. Send chat action `upload_video` (refresh every ~4s while working).
2. **Cache check:** `FileIdCache` (in-memory LRU, e.g. 500 entries, `canonicalId → Telegram file_id`). If hit → `sendVideo` with the `file_id` instantly, no download.
3. Otherwise download → `sendVideo`:
   - `InputFile` from the mp4, `reply_to_message_id` = original message (with `allow_sending_without_reply=true`),
   - `width`, `height`, `duration` from metadata, `supports_streaming=true`,
   - caption: short, e.g. platform icon + link (optional, keep it minimal; make it configurable later if wanted),
   - `disable_notification=true` (friends already saw the link).
4. Store returned `file_id` in the cache. Delete temp dir.
5. On failure: if `REPLY_WITH_ERRORS`, reply with a short human message per reason ("too long (> 3 min)", "Instagram wants a login — cookies needed", …). Never spam: one reply per message, not per retry.
6. Retry policy: one retry for `UNKNOWN`/network-ish errors with a small backoff; no retry for `TOO_LONG`/`TOO_LARGE`/`NOT_A_VIDEO`.
7. Handle Telegram `429 Too Many Requests` by honoring `retry_after`.
8. Graceful shutdown: stop polling, let in-flight jobs finish (bounded wait), kill child processes.

Nice-to-haves for this phase (small, worth doing):
- `/start` and `/help` commands explaining what the bot does.
- Ignore messages sent by bots (including itself).
- Ignore edited messages (avoid re-posting).

**Checkpoint:** in the real group, posting each of the three link types results in a playable video reply; posting the same link again replies instantly (cache); a long YouTube video gets a polite refusal.

---

## 6. Docker + one-command start

**Goal:** `docker compose up -d` is the only thing needed.

`Dockerfile` (multi-stage):
1. **build stage** `maven:3-eclipse-temurin-25` → copy `pom.xml` first, `mvn dependency:go-offline` (layer cache), then sources, `mvn -q package -DskipTests` (tests run in CI / locally).
2. **runtime stage** `eclipse-temurin:25-jre` (Debian-based, so apt works):
   - `apt-get install ffmpeg python3 ca-certificates curl` (+ `xz-utils` for phase 7),
   - install yt-dlp as the official standalone binary from GitHub releases (`yt-dlp_linux` / `yt-dlp_linux_aarch64` chosen by `TARGETARCH`) → `/usr/local/bin/yt-dlp`,
   - non-root user, `WORKDIR /app`, copy jar,
   - `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/tg-shorts-bot.jar"]`.

`docker-compose.yml`:
```yaml
services:
  bot:
    build: .
    image: tg-shorts-bot:latest
    restart: unless-stopped
    env_file: .env
    volumes:
      - ./cookies.txt:/app/cookies.txt:ro    # optional; documented in README
    tmpfs:
      - /tmp/shortsbot:size=512m
    logging:
      driver: json-file
      options: { max-size: "10m", max-file: "3" }
```
(The cookies mount will be made optional — e.g. mount a `./secrets/` dir instead of a single file, so a missing file doesn't break startup.)

`.env.example` with every variable from phase 2, comments, and `VPN_ENABLED=false`.

`README.md`: BotFather steps (incl. privacy mode!), `cp .env.example .env`, `docker compose up -d`, `docker compose logs -f`, how to export Instagram cookies, how to update yt-dlp (`docker compose build --pull --no-cache && docker compose up -d`).

**Checkpoint:** on a clean machine with only Docker: clone → fill `.env` → `docker compose up -d` → bot works in the group.

---

## 7. VPN via Outline (`ss://`) — optional, default off

**Goal:** with `VPN_ENABLED=true`, *all* outbound traffic of the bot (Telegram API **and** yt-dlp) goes through the Outline server. Both matter: countries that block Instagram/TikTok/YouTube often block Telegram too.

### 7.1 Parse the Outline key — `ShadowsocksUrl`
Outline keys come in these shapes; support all:
- SIP002: `ss://BASE64URL(method:password)@host:port/?outline=1#Name`
- Legacy: `ss://BASE64(method:password@host:port)#Name`
- Dynamic key: `ssconf://host/path…` → fetch over HTTPS (`ssconf` → `https`), response is either an `ss://` string or JSON `{server, server_port, password, method}`.

Produce a record `(method, password, host, port)`; never log the password. Unit-test with sample keys (padded/unpadded base64, URL-safe alphabet, percent-encoded parts, IPv6 host).

### 7.2 Bundle `sslocal`
In the Dockerfile download the `shadowsocks-rust` release tarball for the target arch, extract `sslocal` to `/usr/local/bin`. (Small static binary, a few MB.)

### 7.3 Start it from Java — `ShadowsocksSidecar`
On startup, before Telegram/yt-dlp are touched, if `VPN_ENABLED`:
1. Validate `VPN_SS_URL` is present (fail fast otherwise).
2. Start `sslocal -b 127.0.0.1:1080 -s host:port -m method -k password` (args list; or write a temp JSON config with `0600` perms so the password doesn't show in `ps`).
3. Pipe its output into our logger with a `[sslocal]` prefix.
4. Wait until the SOCKS port accepts connections (poll up to ~10s), then do a **connectivity probe** through the proxy (e.g. HTTPS GET `https://api.telegram.org` via a `java.net.Proxy(SOCKS)`), log success/failure clearly.
5. Supervise: if the process dies, log + restart with backoff; destroy it in the shutdown hook.

### 7.4 Route traffic through it
- **Telegram:** build a custom `OkHttpClient` with `.proxy(new Proxy(Proxy.Type.SOCKS, 127.0.0.1:1080))` and pass it to `OkHttpTelegramClient` and the long-polling application. OkHttp resolves hostnames via the SOCKS server, so DNS isn't leaked/blocked either.
- **yt-dlp:** add `--proxy socks5://127.0.0.1:1080` to every command.
- Keep everything behind one `ProxySettings` object (`Optional<InetSocketAddress>`) built in `Main` and handed to both components → when `VPN_ENABLED=false` nothing changes from phases 1–6.

### 7.5 Docker/compose
Nothing new in compose besides two variables in `.env`:
```
VPN_ENABLED=true
VPN_SS_URL=ss://...
```
Still a single `docker compose up -d`.

**Checkpoint:** with `VPN_ENABLED=true`, logs show sslocal started + probe OK; videos still arrive; verify egress IP by temporarily logging the result of `yt-dlp --proxy … --print … ` or `curl --socks5-hostname 127.0.0.1:1080 https://ifconfig.me` inside the container equals the Outline server's IP. With `VPN_ENABLED=false` behavior is identical to before.

---

## 8. Hardening & polish (after it all works)

- **Health:** Docker `HEALTHCHECK` touching a heartbeat file the poller updates every loop.
- **yt-dlp freshness:** `YTDLP_AUTO_UPDATE=true` runs `yt-dlp -U` at startup (binary is writable by the app user), plus a README note to rebuild weekly.
- **Abuse limits:** per-chat rate limit (e.g. 10 links/minute), max queue size — drop with a log when full.
- **Too-large fallback:** if > 49 MB, retry once with `-S "res:480"`; still too big → polite refusal.
- **Optional features** (only if you want them): delete the original message after posting (needs admin rights; config flag), caption with the original poster's name, support for Instagram carousels / TikTok photo posts via `sendMediaGroup`, persistent file_id cache (simple JSON file on a volume).
- **CI:** GitHub Actions running `./mvnw verify` and `docker build`.

---

## Order of execution (summary)

1. Skeleton + Maven wrapper → builds.
2. Config + bot receives group messages.
3. Link extractor + tests.
4. yt-dlp downloader + tests.
5. Send video, cache, errors, concurrency → **usable bot**.
6. Dockerfile + compose + README → **one-command deploy**.
7. Outline/Shadowsocks VPN toggle.
8. Hardening.

Each step is a separate commit; tests stay green at every step.
