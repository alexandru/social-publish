## Backend JVM memory snapshot (fat JAR via `scripts/java-exec`)

**Build/Test context:** `backend` assembled with `./sbt assembly` (Scala 3.7.4). Server started with integrations disabled and SQLite/temp paths to mirror production launcher:

```bash
cd backend
./sbt assembly
/home/runner/work/social-publish/social-publish/scripts/java-exec \
  -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions \
  -jar target/scala-3.7.4/social-publish-backend.jar \
  --base-url http://localhost:3000 \
  --server-auth-username user --server-auth-password pass \
  --server-auth-jwt-secret secret \
  --db-path /tmp/social-publish/db.sqlite \
  --uploaded-files-path /tmp/social-publish/uploads \
  --bluesky-enabled false --mastodon-enabled false --twitter-enabled false
```

**Observed process footprint (idle server, current branch):**

* `ps`: **RSS ~202 MB**, VSZ ~3.9 GB (Shenandoah, `-Xms32m -Xmx256m`).
* NMT (`jcmd <pid> VM.native_memory summary`):
  * **Committed total:** ~140 MB (reserved ~1.7 GB mostly metaspace/class-space reservations).
  * **Java heap:** 32 MB committed, **256 MB reserved** (bounded by launcher flags).
  * **Metaspace:** 50–56 MB committed (class metadata for tapir/http4s/doobie, etc.; Swagger removed).
  * **Code cache:** 12 MB committed.
  * **Symbols/strings:** ~12–13 MB committed.
  * **Threads:** ~31 threads, stacks reserve ~30 MB (~2.6 MB committed).
  * **GC (Shenandoah) control structures:** ~19 MB reserved, ~3.9 MB committed.
  * **NMT overhead itself:** ~5 MB committed.

**What is consuming the extra ~100 MB?**

* Heap is small (committed ~32 MB, reserved 256 MB) — the excess comes mostly from **class metadata + code cache (~70 MB)** and **JVM/platform overhead** (thread stacks, NMT, shared class data).
* **Shenandoah** now reserves 256 MB (bounded by launcher flags); RSS is influenced more by metaspace, code cache, and thread stacks than heap usage.
* Default **Hikari/HTTP client threads** (~28 total) contribute ~20–30 MB of stack reservation.

## Recommendations to trim ~80–120 MB

1. **Keep heap cap low and shrink stacks**  
   Launcher already uses `-Xms32m -Xmx256m`. To claw back more RSS, also add e.g. `-Xss512k` (or 256k if safe) to trim ~10–15 MB of stack reservation for ~30 threads.

2. **Keep Shenandoah (for uncommitting) but cap it**  
   Shenandoah is kept to aggressively return unused pages; the key is capping the heap (`-Xmx`). Current heap is 256m reserved / ~13–32m used depending on warmup; further reduction would need lower `-Xmx` and load validation.

3. **Clamp thread pools**  
   Hikari pool already reduced to 4/1. If acceptable, drop to 2/1 for SQLite-only deployments. Consider bounding the HTTP client/compute pools (cats-effect) if workload allows.

4. **Reduce classpath-heavy features in prod**  
   Swagger/OpenAPI removed. Further savings would require trimming remaining libraries or shading out unused tapir modules; impact likely modest compared to heap/stacks.

5. **Disable NMT outside diagnostics**  
   NMT adds ~5 MB committed and some CPU; keep it off in normal runs.

Applying (1) + (3) (stack cut + possibly smaller pools) is the next lever to move RSS below ~180 MB; deeper cuts would require lowering `-Xmx` further or dropping additional libraries.
