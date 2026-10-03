# Intranet Bandwidth Testing Server

[中文说明](README.md) | **English**

A **zero-dependency** LAN bandwidth testing web service. Run the server on one machine, then open the page
from any device on the same LAN (phone, tablet, laptop) to measure **download bandwidth, upload bandwidth,
latency, jitter and packet loss**. Useful for checking NAS throughput, 1G/2.5G/10G switches, real Wi-Fi
throughput, and cable quality.

Two interchangeable server implementations share the exact same HTTP API and measurement methodology:

| Runtime | Entry point | Dependencies | Notes |
| --- | --- | --- | --- |
| **Node.js** (default) | [`server/server.js`](server/server.js) | Node 18+, **no `npm install`** | Zero dependency, no build step, instant start |
| Java 17+ (fallback) | [`server/SpeedTestServer.java`](server/SpeedTestServer.java) | JDK standard library only | Cheaper upload path on CPU-bound hosts |

---

## Quick start

```powershell
git clone https://github.com/Allpics/Intranet-bandwidth-testing-server-instance.git
cd Intranet-bandwidth-testing-server-instance
.\start.ps1                      # Node.js, port 8000, runs in background
```

Then open `http://<your-lan-ip>:8000/` **from the device you want to test** (e.g. `http://192.168.1.4:8000/`)
and click **开始测速** (Start).

- Use the **LAN IP**, not `127.0.0.1` — loopback bypasses the network and only measures memory bandwidth.
- If other devices cannot connect, allow the port once from an elevated shell: `.\start.ps1 -Firewall`.

Without the script:

```bash
# Node.js
node server/server.js 8000          # args: [port] [bind address]

# Java
javac -encoding UTF-8 -d classes server/SpeedTestServer.java
java -cp classes SpeedTestServer 8000
```

### `start.ps1` options

| Command | Description |
| --- | --- |
| `.\start.ps1` | Node.js, port 8000, background |
| `.\start.ps1 -Port 8081` | Custom port |
| `.\start.ps1 -Runtime java` | Use the Java implementation |
| `.\start.ps1 -Foreground` | Run in the foreground (`Ctrl+C` to stop) |
| `.\start.ps1 -Firewall` | Add an inbound firewall rule (needs admin) |
| `.\start.ps1 -Stop` | Stop the service |
| `.\start.ps1 -Clean` | Rebuild `classes/` (Java runtime only) |

---

## How it measures

Data movement is handled entirely by the **browser's native network stack** — JavaScript never sits on the
hot path:

| Direction | Request | Byte accounting |
| --- | --- | --- |
| Download | `GET /api/download?sid=..&chunk=16777216` | Server sends exactly N bytes with `Content-Length`; the client verifies the length via `arrayBuffer()` and discards the buffer |
| Upload | `POST /api/upload?sid=..&chunk=8388608` | Fixed-size `Uint8Array` request body; the server reads exactly N bytes and returns `X-Bytes-Read` |

- **Adaptive chunk size**: starts at 8 MiB (up) / 16 MiB (down), then doubles or halves within 2–64 MiB
  based on per-chunk latency (target 30–600 ms per chunk) — works from 1G to 10G without tuning.
- **Throughput** = `total bytes × 8 ÷ client wall-clock seconds ÷ 1e6`, where total bytes are what the client
  actually received/sent, cross-checked against the server's own counters (the diagnostics table shows both;
  a difference above 1 MiB raises a warning).
- **Bounded memory**: every downloaded chunk is discarded immediately, uploads reuse a fixed buffer pool.

A second, fully independent download path (`XMLHttpRequest` + `onprogress`, `responseType='blob'`) is
available in the UI as a fallback and for cross-checking.

---

## HTTP API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/session?ms=10000` | Create a session; `ms` = phase duration (1000–120000) |
| `GET` | `/api/ping` | 204 empty response with `X-Server-Time` |
| `GET` | `/api/download?sid=&chunk=N` | Chunked download: exactly N bytes |
| `GET` | `/api/download?sid=&ms=N` | Legacy mode: stream until the deadline (chunked) |
| `POST` | `/api/upload?sid=&chunk=N` | Chunked upload: read exactly N bytes |
| `POST` | `/api/upload?sid=` | Legacy mode: read until the deadline |
| `GET` | `/api/report?sid=` | Cumulative bytes, time window and chunk count |
| `GET` | `/api/info` | Server capabilities and current `runtime` |
| any | append `&warmup=1` | Warm-up traffic, never counted |

---

## Browser support

Chrome / Edge / Opera, Firefox and Safari (desktop and iOS) all support both directions. The implementation
does **not** rely on `ReadableStream` as a request body, which is
[still Chromium-only](https://caniuse.com/mdn-api_fetch_options_parameter_body_accepts_readablestream).

---

## Documentation

- Full docs (Chinese, primary): [README.md](README.md)
- Implementation notes: [server/README.md](server/README.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)

## License

[Apache License 2.0](LICENSE)

## Security

This service has **no authentication and no rate limiting** — anyone who can reach the port can saturate
your bandwidth. Use it on trusted intranets only, and never expose it to the public internet.
