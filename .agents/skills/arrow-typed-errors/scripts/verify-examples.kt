///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+
//KOTLIN 2.3.21
//COMPILE_OPTIONS -Xcontext-parameters
//DEPS io.arrow-kt:arrow-core:2.2.2.1

import arrow.core.Either
import arrow.core.Ior
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.left
import arrow.core.recover
import arrow.core.right
import arrow.core.toOption
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.IorRaise
import arrow.core.raise.Raise as ArrowRaise
import arrow.core.raise.recover as recoverRaised
import arrow.core.raise.context.Raise
import arrow.core.raise.context.accumulate
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.context.ensureOrAccumulate
import arrow.core.raise.context.ior
import arrow.core.raise.context.mapOrAccumulate
import arrow.core.raise.context.nullable
import arrow.core.raise.context.option
import arrow.core.raise.context.withError
import arrow.core.raise.context.zipOrAccumulate

sealed interface ParseError {
    data object Blank : ParseError
    data class NotANumber(val raw: String) : ParseError
    data class NotPositive(val value: Long) : ParseError
}

@JvmInline
value class UserId private constructor(val value: Long) {
    companion object {
        context(_: Raise<ParseError>)
        fun parseInContext(raw: String): UserId {
            ensure(raw.isNotBlank()) { ParseError.Blank }
            val value = ensureNotNull(raw.toLongOrNull()) { ParseError.NotANumber(raw) }
            ensure(value > 0) { ParseError.NotPositive(value) }
            return UserId(value)
        }

        fun parse(raw: String): Either<ParseError, UserId> = either {
            parseInContext(raw)
        }
    }
}

data class User(val id: UserId, val name: String, val email: String?)

sealed interface ServiceError {
    data class InvalidId(val error: ParseError) : ServiceError
    data class MissingUser(val id: UserId) : ServiceError
}

fun findUser(id: UserId): Either<ServiceError, User> =
    if (id.value == 1L) User(id, "Ada", "ada@example.test").right()
    else ServiceError.MissingUser(id).left()

context(_: Raise<ServiceError>)
fun loadUser(rawId: String): User {
    val id = UserId.parse(rawId)
        .mapLeft(ServiceError::InvalidId)
        .bind()
    return findUser(id).bind()
}

fun loadUserEither(rawId: String): Either<ServiceError, User> =
    either { loadUser(rawId) }

context(_: Raise<ServiceError>)
fun loadUserDirect(rawId: String): User {
    val id = withError({ error: ParseError -> ServiceError.InvalidId(error) }) {
        UserId.parseInContext(rawId)
    }
    return findUser(id).bind()
}

fun hasUser(rawId: String): Either<ServiceError, Boolean> =
    loadUserEither(rawId).map { true }

fun displayName(rawId: String): String =
    loadUserEither(rawId).fold(
        { "unknown user" },
        { user -> user.name },
    )

fun loadUserChain(rawId: String): Either<ServiceError, User> =
    UserId.parse(rawId)
        .mapLeft(ServiceError::InvalidId)
        .flatMap(::findUser)

fun loadUserOrFallback(rawId: String): Either<ServiceError, User> =
    loadUserEither(rawId).recover {
        val fallbackId = UserId.parse("1").mapLeft(ServiceError::InvalidId).bind()
        findUser(fallbackId).bind()
    }

sealed interface StoredError {
    data class InvalidCount(val text: String) : StoredError
}

fun parseStoredCount(text: String): Either<StoredError, Int> =
    Either.catchOrThrow<NumberFormatException, Int> { text.toInt() }
        .mapLeft { StoredError.InvalidCount(text) }

sealed interface SignupError {
    data object BlankName : SignupError
    data class UnderAge(val age: Int) : SignupError
}

class Signup private constructor(val name: String, val age: Int) {
    companion object {
        fun create(name: String, age: Int): Either<NonEmptyList<SignupError>, Signup> = either {
            zipOrAccumulate(
                { ensure(name.isNotBlank()) { SignupError.BlankName } },
                { ensure(age >= 18) { SignupError.UnderAge(age) } },
            ) { _, _ -> Signup(name, age) }
        }

        @OptIn(ExperimentalRaiseAccumulateApi::class)
        fun createImperatively(name: String, age: Int): Either<NonEmptyList<SignupError>, Signup> = either {
            accumulate {
                ensureOrAccumulate(name.isNotBlank()) { SignupError.BlankName }
                ensureOrAccumulate(age >= 18) { SignupError.UnderAge(age) }
                Signup(name, age)
            }
        }
    }
}

sealed interface AuthorError {
    data object EmptyName : AuthorError
}

class Author private constructor(val name: String) {
    companion object {
        fun create(name: String): Either<AuthorError, Author> = either {
            ensure(name.isNotBlank()) { AuthorError.EmptyName }
            Author(name)
        }
    }
}

sealed interface BookError {
    data class EmptyAuthor(val index: Int) : BookError
}

fun validateAuthors(names: Iterable<String>): Either<NonEmptyList<BookError>, List<Author>> = either {
    names.withIndex().mapOrAccumulate { indexed ->
        Author.create(indexed.value)
            .mapLeft { BookError.EmptyAuthor(indexed.index) }
            .bind()
    }
}

fun selectEmail(user: User?): String? = nullable {
    user.bind().email.bind()
}

fun selectEmailOption(user: Option<User>): Option<String> = option {
    val email = user.bind().email
    email.toOption().bind()
}

fun possibleEmail(rawId: String): String? =
    loadUserEither(rawId).getOrNull()?.email

sealed interface AgeError {
    data object Negative : AgeError
    data object Minor : AgeError
}

@JvmInline
value class Age(val value: Int)

fun adultAge(value: Int): Either<AgeError, Age> = when {
    value < 0 -> AgeError.Negative.left()
    value < 18 -> AgeError.Minor.left()
    else -> Age(value).right()
}

context(notices: IorRaise<List<String>>)
fun normalizeTitle(raw: String): String {
    val title = raw.trim()
    if (title != raw) notices.accumulate(listOf("leading or trailing whitespace removed"))
    return title
}

fun normalizedTitle(raw: String): Ior<List<String>, String> =
    ior(List<String>::plus) { normalizeTitle(raw) }

sealed interface Lce<out E, out A> {
    data object Loading : Lce<Nothing, Nothing>
    data class Content<A>(val value: A) : Lce<Nothing, A>
    data class Failure<E>(val error: E) : Lce<E, Nothing>
}

@JvmInline
value class LceScope<E>(private val errors: ArrowRaise<Lce<E, Nothing>>) {
    fun <A> bind(value: Lce<E, A>): A = when (value) {
        is Lce.Content -> value.value
        is Lce.Failure -> errors.raise(value)
        Lce.Loading -> errors.raise(Lce.Loading)
    }
}

context(scope: LceScope<E>)
fun <E, A> Lce<E, A>.bindLce(): A = scope.bind(this)

inline fun <E, A> lce(block: context(LceScope<E>) () -> A): Lce<E, A> =
    recoverRaised({ Lce.Content(block(LceScope(this))) }) { error: Lce<E, Nothing> -> error }

fun titleScreen(ready: Boolean): Lce<String, String> = lce {
    val title = if (ready) Lce.Content("Ready") else Lce.Loading
    title.bindLce()
}

fun main() {
    check(UserId.parse("x").isLeft())
    check(loadUserEither("1").isRight())
    check(either { loadUserDirect("x") }.isLeft())
    check(hasUser("1") == true.right())
    check(displayName("1") == "Ada")
    check(loadUserChain("1").isRight())
    check(loadUserOrFallback("unknown").isRight())
    check(parseStoredCount("x").isLeft())
    check(Signup.create("", 12).isLeft())
    check(Signup.createImperatively("", 12).isLeft())
    check(validateAuthors(listOf("Ada", "")).isLeft())
    check(selectEmail(loadUserEither("1").getOrNull()) == "ada@example.test")
    check(selectEmailOption(loadUserEither("2").getOrNull().toOption()).isNone())
    check(possibleEmail("2") == null)
    check(adultAge(20).isRight())
    check(normalizedTitle(" title ").isBoth())
    check(titleScreen(false) == Lce.Loading)
}
