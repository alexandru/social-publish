///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+
//KOTLIN 2.3.21
//COMPILE_OPTIONS -Xcontext-parameters
//DEPS io.arrow-kt:arrow-fx-coroutines-jvm:2.2.2.1
//DEPS io.arrow-kt:arrow-autoclose-jvm:2.2.2.1
//DEPS org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0

import arrow.AutoCloseScope
import arrow.autoCloseScope
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.ResourceScope
import arrow.fx.coroutines.resource.context.autoCloseable
import arrow.fx.coroutines.resource.context.bind
import arrow.fx.coroutines.resource.context.install
import arrow.fx.coroutines.resource.context.resource
import arrow.fx.coroutines.resource.context.resourceScope
import kotlinx.coroutines.runBlocking

private class TrackedHandle private constructor(
    private val name: String,
    private val releases: MutableList<String>,
) : AutoCloseable {
    private var closed = false

    fun read(): String {
        check(!closed) { "$name is already closed" }
        return "data:$name"
    }

    override fun close() {
        if (!closed) {
            closed = true
            releases += name
        }
    }

    companion object {
        fun open(name: String, releases: MutableList<String>): TrackedHandle =
            TrackedHandle(name, releases)
    }
}

context(_: ResourceScope)
private suspend fun trackedHandle(name: String, releases: MutableList<String>): TrackedHandle =
    install(
        acquire = { TrackedHandle.open(name, releases) },
        release = { handle, _ -> handle.close() },
    )

context(_: ResourceScope)
private suspend fun autoTrackedHandle(name: String, releases: MutableList<String>): TrackedHandle =
    autoCloseable { TrackedHandle.open(name, releases) }

private class ManagedClient private constructor(
    private val primary: TrackedHandle,
    private val cache: TrackedHandle,
) {
    fun fetch(): String =
        "${primary.read()}|${cache.read()}"

    companion object {
        context(_: ResourceScope)
        suspend fun open(releases: MutableList<String>): ManagedClient =
            ManagedClient(
                primary = trackedHandle("primary", releases),
                cache = autoTrackedHandle("cache", releases),
            )

        fun resource(releases: MutableList<String>): Resource<ManagedClient> =
            resource { open(releases) }
    }
}

private class SyncClient private constructor(
    private val handle: TrackedHandle,
) {
    fun fetch(): String =
        handle.read()

    companion object {
        context(scope: AutoCloseScope)
        fun open(releases: MutableList<String>): SyncClient =
            SyncClient(scope.install(TrackedHandle.open("sync", releases)))
    }
}

suspend fun resourceExample(): List<String> {
    val releases = mutableListOf<String>()

    val result = resourceScope {
        val client = ManagedClient.resource(releases).bind()
        client.fetch()
    }

    check(result == "data:primary|data:cache")
    check(releases == listOf("cache", "primary"))
    return releases
}

fun autoCloseExample(): List<String> {
    val releases = mutableListOf<String>()

    val result = autoCloseScope {
        val client = SyncClient.open(releases)
        client.fetch()
    }

    check(result == "data:sync")
    check(releases == listOf("sync"))
    return releases
}

fun main() {
    runBlocking {
        check(resourceExample() == listOf("cache", "primary"))
    }
    check(autoCloseExample() == listOf("sync"))
}
