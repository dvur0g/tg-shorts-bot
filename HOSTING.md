# Hosting on a home PC

How to turn a regular home PC into a small, quiet, headless server that runs this bot, the TeamSpeak music bot,
and anything else in Docker, and that you can reach securely from anywhere.

---

## TL;DR

| Question | Answer |
|---|---|
| Which OS? | **Debian 13 ("trixie")**, minimal install, no desktop, only "SSH server" ticked. |
| Do I need a white (public, static) IP? | **No, not for this setup.** The bots only make *outgoing* connections, and you reach the server through **Tailscale**. You need a white IP only if *other people* must connect *in* (e.g. you host the TeamSpeak *server* itself, or a public website). |
| How do I reach it from anywhere? | **Tailscale** (a private network between your devices). The server opens **zero** ports to the internet. |
| How do I run the services? | **Docker + docker compose**, one folder per service under `/srv`. |
| Personal VPN? | Easiest: make the server a **Tailscale exit node** (no ports to open). Classic alternative: **WireGuard (wg-easy)**, which needs a white IP or at least port forwarding. Read the [warning about blocked sites](#warning-a-home-vpn-does-not-unblock-sites-blocked-in-your-country) first. |
| Security basics | SSH with keys only, firewall that blocks all incoming traffic, automatic security updates, never publish Docker ports to the internet by accident. |

The whole thing idles at ~300–500 MB RAM (OS + Docker + both bots) and almost 0% CPU.

---

## 1. The big picture

```
          Internet
             │
     ┌───────┴────────┐
     │  Home router   │  ← nothing forwarded, no open ports
     └───────┬────────┘
             │ LAN
     ┌───────┴──────────────────────────────────────────┐
     │  Home PC: Debian 13, no GUI                       │
     │                                                   │
     │   sshd ── only reachable over LAN + Tailscale     │
     │   tailscale ── private network to your devices    │
     │   docker                                          │
     │     ├── tg-shorts-bot     (outgoing only)         │
     │     ├── teamspeak-musicbot (outgoing only)        │
     │     └── … future services                         │
     └───────────────────────────────────────────────────┘

  Your laptop / phone, anywhere ──Tailscale──► home PC
```

**Why no white IP is needed:** think of it like HTTP clients vs. servers in code. Both bots are *clients*: the
Telegram bot long-polls `api.telegram.org`, the music bot connects to a TeamSpeak server. Clients can work from behind
any router. You only need a public address when something has to *accept* connections from the internet.

**Why Tailscale for remote access:** normally, to SSH into your home PC from outside you'd need a white IP, port
forwarding on the router, and you'd have SSH exposed to every bot on the internet. Tailscale instead installs a small
agent on each of your devices (server, laptop, phone). They connect to each other through an encrypted WireGuard
tunnel, even through home routers and mobile networks, without opening ports. Each device gets a stable private IP
like `100.x.y.z` and a name like `homeserver`. From anywhere you just run `ssh you@homeserver`. Free for personal use
(up to 100 devices).

---

## 2. Hardware prep

- **Any PC from the last ~10 years is plenty.** 4 GB RAM is enough, 8 GB is comfortable. An SSD is strongly preferred.
- **Wired Ethernet** to the router, not Wi‑Fi (more reliable, and Debian may not have Wi‑Fi drivers out of the box).
- **BIOS/UEFI settings:**
  - "Restore on AC power loss" / "After power failure" → **Power On**. After a power outage the server boots by itself.
  - Disable features you don't need (onboard audio, RGB, etc.) if you want; optional.
- **Power:** a typical desktop idles at 20–60 W. If you have a choice, an older laptop or a mini PC (Intel N100 etc.)
  idles at 5–10 W and also has a built-in "UPS" (its battery).
- **Monitor + keyboard** are needed only once, for the install. After that the PC can live in a closet.

---

## 3. Which OS and why

**Debian 13 (stable).** Reasons:

- Smallest, most "boring" mainstream server OS: a minimal install uses ~150 MB RAM and runs only what you install.
- Extremely stable, security updates for years, huge amount of documentation online.
- Docker and Tailscale officially support it.

Alternatives, and why not first choice:

| OS | Verdict |
|---|---|
| Ubuntu Server LTS | Fine too, almost the same thing. A bit heavier (snap, cloud-init). Pick it if you already know Ubuntu. |
| Proxmox | Great if you want to run full virtual machines. Overkill for "a few containers". |
| TrueNAS / Unraid / CasaOS | Web GUIs for NAS/home-lab use. More stuff running, more to learn, and you said no GUI. |
| Alpine | Very light, but uses musl and a different toolset; more surprises. |
| Windows + Docker Desktop | Heavy, reboots for updates, not meant to be a server. |

---

## 4. Install Debian (one-time, with monitor + keyboard)

1. Download the **"netinst" ISO** for amd64 from <https://www.debian.org/download>.
2. Write it to a USB stick (on macOS: [balenaEtcher](https://etcher.balena.io/), or `dd`).
3. Boot the PC from the USB stick and choose **"Install"** (the text installer, not "Graphical install"; same result).
4. Answers that matter:
   - **Hostname:** e.g. `homeserver`.
   - **Root password:** leave it **empty**. Then your normal user gets `sudo` rights automatically.
   - **User:** e.g. `dan`, with a strong password (you'll still need it for `sudo`).
   - **Partitioning:** "Guided - use entire disk" is fine. (Choose "with encrypted LVM" only if you're OK typing the
     disk password on every boot. That breaks "boots by itself after a power cut", so it's usually skipped for a server.)
   - **Software selection** (the important screen): **untick** "Debian desktop environment" and "GNOME";
     **tick only** "SSH server" and "standard system utilities".
5. Reboot, remove the USB stick, log in on the console once and find the PC's local IP:
   ```bash
   ip -4 addr
   ```
6. In your **router's admin page**, add a **DHCP reservation** ("static lease") for this PC so its LAN IP never
   changes (e.g. `192.168.1.50`).

From now on, everything is done over SSH from your Mac. You can unplug the monitor.

---

## 5. First steps over SSH (from your Mac)

### 5.1 SSH keys instead of passwords

On your **Mac**:

```bash
# skip if you already have ~/.ssh/id_ed25519
ssh-keygen -t ed25519

# copy your public key to the server (asks for the server password one last time)
ssh-copy-id dan@192.168.1.50

# this must now log in WITHOUT asking for a password
ssh dan@192.168.1.50
```

On the **server**, turn off password logins and root logins:

```bash
sudo tee /etc/ssh/sshd_config.d/10-hardening.conf >/dev/null <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sudo systemctl restart ssh
```

Keep your current SSH session open and test a **new** `ssh dan@192.168.1.50` from another terminal before closing it,
so a typo can't lock you out.

Optional convenience on the Mac, in `~/.ssh/config`:

```
Host homeserver
    HostName homeserver      # the Tailscale name (step 6); use 192.168.1.50 until then
    User dan
```

Then it's just `ssh homeserver`.

### 5.2 Updates, automatic security patches

```bash
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y unattended-upgrades curl git htop
sudo dpkg-reconfigure -plow unattended-upgrades   # answer "Yes"
```

Security updates now install themselves daily. Do a manual `sudo apt update && sudo apt full-upgrade` now and then,
and `sudo reboot` when a new kernel came in.

---

## 6. Tailscale: access from anywhere

On the **server**:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

It prints a login link: open it, log in (Google/GitHub/etc. account). Then:

1. Install Tailscale on your **Mac** and **phone** (App Store / <https://tailscale.com/download>), log in with the
   **same account**.
2. In the admin console <https://login.tailscale.com/admin/machines>, open the server's "…" menu and click
   **"Disable key expiry"**, otherwise you'll have to re-login on the server every 180 days.
3. Test from anywhere (e.g. phone hotspot): `ssh dan@homeserver`.

Tailscale traffic is end-to-end encrypted; Tailscale's servers only help devices find each other.

---

## 7. Firewall

Rule of thumb: **block everything incoming, except SSH from your home LAN and anything over Tailscale.**

```bash
sudo apt install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow in on tailscale0                              # everything from your own devices
sudo ufw allow from 192.168.0.0/16 to any port 22 proto tcp  # SSH from the home LAN (fallback if Tailscale breaks)
sudo ufw enable
sudo ufw status verbose
```

(If your LAN is `10.x.x.x`, use `10.0.0.0/8` instead of `192.168.0.0/16`.)

### ⚠️ The Docker + firewall gotcha

**Docker bypasses ufw.** If a compose file says `ports: ["8080:8080"]`, that port is open on *every* network interface,
whatever ufw says. With no port forwarding on the router it's "only" open to your LAN, but still: be explicit.

- Services that only make outgoing connections (both bots): **no `ports:` at all.** This repo's compose file already
  has none.
- A web UI you want to reach yourself: bind it to localhost or the Tailscale IP, never bare:
  ```yaml
  ports:
    - "127.0.0.1:8080:8080"     # reach via `ssh -L 8080:localhost:8080 homeserver`
    - "100.101.102.103:8080:8080"  # or the server's Tailscale IP (`tailscale ip -4`)
  ```

---

## 8. Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER      # run docker without sudo; log out and back in after this
```

(Being in the `docker` group is effectively root on that machine. Fine for your own user, don't give it to others.)

Cap log sizes for **all** containers, so logs can never fill the disk:

```bash
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
sudo systemctl restart docker
```

Docker starts at boot by default, and containers with `restart: unless-stopped` come back automatically after a reboot
or power cut.

---

## 9. Folder layout for multiple services

One folder per service, each with its own `docker-compose.yml` and `.env`. Independent: you can update/restart one
without touching the others.

```
/srv/
├── tg-shorts-bot/          git clone of this repo
│   ├── docker-compose.yml
│   ├── .env                BOT_TOKEN etc. (copy by hand, never in git)
│   └── secrets/cookies.txt optional
├── ts-musicbot/            git clone of the TeamSpeak music bot
│   ├── docker-compose.yml
│   └── .env
└── vpn/                    only if you choose wg-easy (section 10)
    └── docker-compose.yml
```

```bash
sudo mkdir -p /srv && sudo chown $USER:$USER /srv
cd /srv
git clone https://github.com/<you>/tg-shorts-bot.git
git clone https://github.com/<you>/ts-musicbot.git
```

Copy the secrets from your Mac (they're git-ignored, so they don't come with the clone):

```bash
# on the Mac
scp .env homeserver:/srv/tg-shorts-bot/.env
ssh homeserver 'chmod 600 /srv/tg-shorts-bot/.env'
```

Start each service:

```bash
cd /srv/tg-shorts-bot && docker compose up -d --build
cd /srv/ts-musicbot   && docker compose up -d --build
```

**Important for this bot:** only one copy may poll a Telegram token at a time. Stop the copy on your Mac
(`docker compose down`) before starting it on the server, otherwise both get `409 Conflict` errors.

### Daily operations cheat sheet

| Task | Command |
|---|---|
| What's running, healthy? | `docker ps` |
| Live logs of one service | `cd /srv/tg-shorts-bot && docker compose logs -f` |
| Update a service to latest code | `cd /srv/<service> && git pull && docker compose up -d --build` |
| Restart / stop | `docker compose restart` / `docker compose down` |
| RAM/CPU per container | `docker stats` |
| Overall machine | `htop`, `df -h` |
| Clean old images (after many rebuilds) | `docker system prune` (also `-a` to remove unused images) |

### Keep the JVM small (optional)

The bot's image uses `-XX:MaxRAMPercentage=75`, i.e. "up to 75% of the RAM the container sees". Without a limit the
container sees the whole PC. To keep it tidy next to other services, add a limit in `docker-compose.yml`:

```yaml
services:
  bot:
    mem_limit: 768m
```

---

## 10. Personal VPN

### Warning: a home VPN does NOT unblock sites blocked in your country

A VPN makes your traffic look like it comes from the VPN server. If the server is in your flat, your traffic comes out
of **your home internet connection, in your country**, with the same blocks as without a VPN. A home VPN is great for:

- safe browsing on public/hotel/café Wi‑Fi,
- reaching your home network and services from anywhere,
- looking like you're at home while travelling abroad (home streaming catalogs, banking apps that dislike foreign IPs).

It is **not** a way around your own country's blocks. For that you need a server **abroad**: a small rented VPS
($3–5/month) with Outline or WireGuard, or an existing Outline key (which is what phase 7 of the bot is planned for).

### Option A (recommended): Tailscale exit node, zero ports, nothing new to run

The server already runs Tailscale; let it forward your internet traffic too:

```bash
# on the server
echo 'net.ipv4.ip_forward = 1'          | sudo tee    /etc/sysctl.d/99-tailscale.conf
echo 'net.ipv6.conf.all.forwarding = 1' | sudo tee -a /etc/sysctl.d/99-tailscale.conf
sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
sudo tailscale set --advertise-exit-node
```

Then in the admin console → server → "Edit route settings" → tick **"Use as exit node"**. On the phone/laptop, in the
Tailscale app pick **Exit node → homeserver**. All your traffic now goes through home. Turn it off with one tap.

Pros: no white IP, no port forwarding, no extra container. Cons: needs the Tailscale app on each device, depends on
Tailscale's (free) coordination service.

### Option B: your own WireGuard server in a container (wg-easy)

Classic self-hosted VPN with a small web UI for creating client configs/QR codes. **Needs incoming traffic**, so:

1. A public IP that reaches your router (see section 11), and
2. a **port forward** on the router: UDP `51820` → `192.168.1.50:51820`.

```yaml
# /srv/vpn/docker-compose.yml  (check the wg-easy README for the current version and options)
services:
  wg-easy:
    image: ghcr.io/wg-easy/wg-easy:15
    container_name: wg-easy
    restart: unless-stopped
    environment:
      - INSECURE=false
    volumes:
      - ./data:/etc/wireguard
      - /lib/modules:/lib/modules:ro
    ports:
      - "51820:51820/udp"              # the VPN itself, forwarded on the router
      - "100.101.102.103:51821:51821"  # web UI ONLY on the Tailscale IP, never public
    cap_add: [NET_ADMIN, SYS_MODULE]
    sysctls:
      - net.ipv4.ip_forward=1
      - net.ipv4.conf.all.src_valid_mark=1
```

Also allow it through ufw: `sudo ufw allow 51820/udp`. WireGuard is very hard to attack: a wrong packet simply gets no
reply, so the open UDP port looks closed to scanners.

Note: some countries' censorship systems detect and block the WireGuard protocol itself. Then a home WireGuard won't
even connect from abroad/mobile, and Option A (which can fall back to Tailscale relays) is more robust.

---

## 11. White IP: when you actually need one

You need inbound connections only for things like: hosting the **TeamSpeak server itself** (friends connect to UDP
`9987`), a WireGuard server (Option B), a public website, a game server.

**Check what you have now:**

1. Open your router's admin page and find its **WAN / Internet IP**.
2. Compare it with <https://ifconfig.me> (from any device at home).

| Result | Meaning | What to do |
|---|---|---|
| Same IP, and it never changes | Static white IP | Port forwarding works. Done. |
| Same IP, but it changes from time to time | Dynamic white IP | Port forwarding works; use **dynamic DNS** (e.g. DuckDNS, free) so a name like `myhome.duckdns.org` always points to the current IP. |
| Different, or router WAN IP starts with `100.64–100.127.` or `10.` | **CGNAT**: you share an IP with other customers | Port forwarding is impossible. Ask the ISP for a white IP (usually a cheap monthly add-on). |

If you get one: forward only the exact ports you need (e.g. UDP `9987` for TeamSpeak, UDP `51820` for WireGuard),
**never** SSH (22), Docker web UIs or the router's admin page. Admin stuff stays on Tailscale.

**Alternatives to a white IP for public services:**

- **Cloudflare Tunnel** (free) for websites/HTTP: an outgoing tunnel, no white IP, no open ports. Doesn't handle UDP
  (so not TeamSpeak/WireGuard).
- **A cheap VPS as the public front door** forwarding to home over Tailscale. Or simply run that public service on the
  VPS itself.

---

## 12. Backups

The code is in git. What's *not* in git and must be backed up somewhere (password manager, encrypted USB stick):

- each service's `.env` (bot tokens, keys),
- `secrets/` (Instagram cookies),
- `/srv/vpn/data` if you use wg-easy (the VPN keys),
- your Mac's `~/.ssh/id_ed25519` (or you lock yourself out of SSH, then you'd need a monitor + keyboard again).

Rebuilding the whole server from scratch = sections 4–9 again + copy those files back. About an hour.

---

## 13. Optional nice-to-haves

- **Uptime Kuma** (container): web dashboard that pings your services and messages you on Telegram if something is
  down. Bind its UI to the Tailscale IP.
- **Dockge or Portainer** (container): web UI to see/restart containers if you ever want one; same rule, Tailscale IP only.
- **Auto-reboot after kernel updates**: in `/etc/apt/apt.conf.d/50unattended-upgrades` set
  `Unattended-Upgrade::Automatic-Reboot "true";` and `Unattended-Upgrade::Automatic-Reboot-Time "05:00";`.
- **A small UPS** if power cuts are common (or use an old laptop, see section 2).

---

## 14. Checklist

- [ ] BIOS: power on after power loss; PC wired to the router
- [ ] Debian 13 netinst, no desktop, "SSH server" only
- [ ] DHCP reservation on the router
- [ ] SSH keys, password + root login disabled
- [ ] `unattended-upgrades` on
- [ ] Tailscale on server, Mac, phone; key expiry disabled for the server
- [ ] ufw: deny incoming, allow `tailscale0` + LAN SSH
- [ ] Docker + log size limits
- [ ] `/srv/tg-shorts-bot` running (copy on the Mac stopped), `/srv/ts-musicbot` running
- [ ] VPN: Tailscale exit node (or wg-easy + white IP + UDP forward)
- [ ] Secrets backed up off the machine
- [ ] Pull the plug once and check everything comes back by itself
