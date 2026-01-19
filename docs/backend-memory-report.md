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

**Observed process footprint (idle server):**

* `ps`: **RSS ~202 MB**, VSZ ~7.8 GB.
* NMT (`jcmd <pid> VM.native_memory summary`):
  * **Committed total:** ~131 MB (reserved 5.7 GB largely from default heap reservation).
  * **Java heap:** 17 MB committed, **4 GB reserved** (default max heap).
  * **Metaspace:** 54 MB committed (class metadata for tapir/http4s/doobie/swagger UI, etc.).
  * **Code cache:** 12 MB committed.
  * **Symbols/strings:** 13 MB committed.
  * **Threads:** 28 threads, stacks reserve ~27 MB (2.2 MB committed).
  * **GC (Shenandoah) control structures:** 259 MB reserved, 4 MB committed.
  * **NMT overhead itself:** ~5 MB committed.

**What is consuming the extra ~100 MB?**

* Heap is small (17 MB) — the excess comes mostly from **class metadata + code cache (~70 MB)** and **JVM/platform overhead** (thread stacks, NMT, shared class data).
* **Shenandoah** reserves large regions (4 GB heap reservation + ~259 MB GC structures) even though little is committed; this inflates virtual size and pushes the RSS upward compared to a tuned small-heap configuration.
* Default **Hikari/HTTP client threads** (~28 total) contribute ~20–30 MB of stack reservation.

## Recommendations to trim ~80–120 MB

1. **Cap heap explicitly for the service profile**  
   Run the fat JAR with smaller bounds instead of relying on MaxRAM default (which reserved 4 GB): e.g.
   ```
   -Xms32m -Xmx256m -XX:InitialRAMPercentage=5 -XX:MaxRAMPercentage=25
   ```
   This prevents over-reservation and typically drops RSS by 60–80 MB on small workloads.

2. **Prefer G1 for this footprint (or tune Shenandoah)**  
   For small heaps, G1 adds less control-plane overhead than Shenandoah. If Shenandoah is retained, set a smaller region size and cap the heap (`-Xmx`) to avoid the 4 GB reservation.

3. **Trim thread stacks**  
   Add `-Xss512k` (or lower if safe) and reduce pool sizes:
   * Hikari: set `maximumPoolSize` to 2–4 for SQLite (default is 10).
   * HTTP client: reuse a bounded executor if practical.
   Expect ~10–15 MB saved.

4. **Reduce classpath-heavy features in prod**  
   The Swagger bundle and OpenAPI generation add to metaspace. If not needed in production, gate them behind a flag or build profile to avoid loading those classes/resources, saving ~10–15 MB.

5. **Disable NMT outside diagnostics**  
   NMT adds ~5 MB committed and some CPU; keep it off in normal runs.

Applying (1) + (3) alone should bring the idle RSS closer to ~90–120 MB for this service.
