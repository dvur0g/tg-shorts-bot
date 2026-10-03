# tg-shorts-bot

A Telegram bot for group chats: when someone posts a **YouTube Shorts**, **Instagram Reel/post** or **TikTok** link,
the bot downloads it and replies with the video right in the chat, so nobody has to leave Telegram to watch it.

- YouTube Shorts (`youtube.com/shorts/…`, `youtu.be/…`), TikTok (including `vm.`/`vt.tiktok.com` short links),
  Instagram reels → the video, as a reply to the message with the link.
- Instagram posts (`instagram.com/p/…`) → the photo(s)/video(s) as one album **plus the post's text**.
- The same link posted again is answered instantly from cache.
- Videos longer than 3 minutes, private/deleted videos etc. get a short explanation instead.

Written in plain Java 25, downloads with [yt-dlp](https://github.com/yt-dlp/yt-dlp) + ffmpeg, all packed into one
Docker image.

## Quick start

You only need [Docker](https://docs.docker.com/get-docker/) with Compose.

1. **Create the bot** in Telegram with [@BotFather](https://t.me/BotFather): `/newbot`, copy the token.
2. **Disable privacy mode**, otherwise the bot can't see normal messages in groups:
   @BotFather → `/setprivacy` → choose your bot → **Disable**.
   (If the bot is already in the group, remove it and add it again afterwards.)
3. **Configure and start:**
   ```sh
   git clone https://github.com/dvur0g/tg-shorts-bot.git
   cd tg-shorts-bot
   cp .env.example .env        # put your token into BOT_TOKEN
   docker compose up -d
   ```
4. **Add the bot to your group** and send any message there. Find the group's id in the logs:
   ```sh
   docker compose logs -f
   ```
   The bot logs every chat it sees. Put the id into `ALLOWED_CHAT_IDS` in `.env` so strangers can't use your bot,
   then apply it with `docker compose up -d`.

That's it: post a link in the group.

## Configuration

All settings live in `.env` (see `.env.example` for comments). After changing it run `docker compose up -d`.

| Variable | Default | Meaning |
|---|---|---|
| `BOT_TOKEN` | required | Token from @BotFather |
| `ALLOWED_CHAT_IDS` | empty = any chat | Comma-separated chat ids the bot serves |
| `MAX_DURATION_SEC` | `180` | Skip videos longer than this |
| `MAX_FILE_MB` | `49` | Max video size (Telegram bots can upload up to 50 MB) |
| `DOWNLOAD_TIMEOUT_SEC` | `120` | Give up on a download after this |
| `WORKER_THREADS` | `2` | Parallel downloads |
| `YTDLP_COOKIES_FILE` | empty | Cookies for sites that want a login, see below |
| `YTDLP_AUTO_UPDATE` | `false` | Update yt-dlp every time the bot starts |
| `REPLY_WITH_ERRORS` | `true` | Reply with a short reason when something can't be fetched |
| `LOG_LEVEL` | `INFO` | `DEBUG` also logs message texts and yt-dlp output |
| `VPN_ENABLED`, `VPN_SS_URL` | `false` | Outline VPN support (work in progress) |

### Instagram login (optional)

Most public reels and posts work without logging in. If the bot answers "Instagram wants a login", give it cookies
of an Instagram account:

1. Log in to Instagram in your browser and export the cookies in Netscape format
   (e.g. with the "Get cookies.txt LOCALLY" extension), or use
   `yt-dlp --cookies-from-browser firefox --cookies cookies.txt --skip-download https://www.instagram.com/`.
2. Save the file as `secrets/cookies.txt` in this folder.
3. Set `YTDLP_COOKIES_FILE=/app/secrets/cookies.txt` in `.env` and run `docker compose up -d`.

Using a secondary account is a good idea: cookies give full access to the account.

## Operating

```sh
docker compose logs -f                 # follow the logs
docker compose restart                 # restart
docker compose down                    # stop and remove the container
```

**Keep yt-dlp fresh.** Sites change often and old yt-dlp versions stop working. Rebuild the image to get the
newest release:
```sh
docker compose build --pull --no-cache && docker compose up -d
```
or set `YTDLP_AUTO_UPDATE=true` to update on every start.

**Debugging a link.** Download it with the same settings the bot uses, without involving Telegram:
```sh
docker compose exec -w /tmp bot java -cp /app/tg-shorts-bot.jar dev.shortsbot.DownloadCli <url>
```

## Development

Requires Java 25. Gradle comes with the wrapper.

```sh
./gradlew build      # compile, run the tests, build build/libs/tg-shorts-bot.jar
```

Running the bot outside Docker needs `yt-dlp` and `ffmpeg` on the `PATH`:
```sh
set -a; source .env; set +a; ./gradlew run
```

`CLAUDE.md` describes the architecture, design decisions and the roadmap.
