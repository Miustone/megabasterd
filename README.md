# MegaBasterd (Modernized Java 25 LTS / WinUI 3 Fork)

Unofficial cross-platform MEGA downloader / uploader / streaming suite — modernized on **Java 25 LTS**, **Windows 11 WinUI 3** styling (FlatLaf 3.6), virtual threads, pooled `HttpClient`, crash-safe chunk resume, and hardware/network auto-tuning.

## What Was Fixed & Modernized

- **Java 25 LTS & Clean Supply Chain**: Upgraded from Java 8 to **Java 25 LTS** with Maven Wrapper (`./mvnw`). Removed obsolete native Xuggler (`xuggle-xuggler-server-all` snapshot) and `jaxb-api` dependencies; video thumbnails optionally use external `ffmpeg` when present.
- **Windows 11 (WinUI 3) Design**: Replaced the legacy Nimbus look-and-feel with **FlatLaf 3.6** Windows 11 native window decorations, rounded cards & controls, WinUI 3 accent colors, Segoe UI Variable typography, and Light/Dark support.
- **Fast, Reliable Downloads & True Restart Resume**:
  - Pooled `java.net.http.HttpClient` + Java 25 **Virtual Threads**.
  - Direct positional `FileChannel` streaming into `.mbpart` + atomic `.mbstate` chunk bitset & 1 MiB sub-chunk MACs (zero double disk writes, zero per-chunk `fsync()` stalls, instant <5 ms integrity verification).
  - Resuming across app restarts skips already-downloaded chunks **before** hitting MEGA's servers, preserves all pending/retrying queues on exit, and migrates legacy `.mctemp` files automatically.
  - Fixed false *"NO DISK SPACE AVAILABLE"* errors on uncreated subdirectories (`#779` / `#677`) and expired-URL `403` loops.
- **Startup PC & Internet Auto-Tuning (`SystemBenchmark`)**:
  - Measures CPU cores, system RAM, AES-CTR throughput, disk write speed, and a tiny quota-safe network probe (<512 KB) to scale concurrency & buffers for High-End PCs while respecting MEGA-safe connection caps and adaptive 429/509 throttling.
- **Factory Reset Button (`ResetManager`)**:
  - Complete 1-click wipe of all MegaBasterd databases, accounts, sessions, logs, caches, and temp folders (with optional deletion of tracked downloaded files) + clean fresh restart.
- **Security & Stability Fixes**:
  - Fixed `SmartProxy` settings save & *"Save working proxies"* textarea sync (`#798`, `#799`).
  - Added rotating 10 MB debug logs (`#800`), crash-safe SQLite `synchronous=NORMAL`, non-intrusive `ClipboardSpy`, bulk `UploadMACGenerator`, `ObjectInputFilter` deserialization guards, MegaCrypter post-decrypt path-traversal guard, and authenticated `KissVideoStreamServer` (`#788`).

## Build & Run

Requires **Java 25 LTS** (no global Maven install required thanks to `./mvnw`):

```bash
./mvnw test package
java -jar target/MegaBasterd-9.0.0-jar-with-dependencies.jar
```

## Disclaimer

You are not authorized to use MegaBasterd in any way that violates [MEGA's terms of use](https://mega.io/terms).
