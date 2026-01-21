package com.alexn.socialpublish.modules

import arrow.core.Either
import com.alexn.socialpublish.db.DocumentsDatabase
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.PostsDatabase
import com.alexn.socialpublish.models.CompositeError
import com.alexn.socialpublish.models.NewPostRequest
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FormModuleTest {
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private lateinit var rssModule: RssModule

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())

        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE TABLE documents (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    search_key VARCHAR(255) UNIQUE NOT NULL,
                    kind VARCHAR(255) NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """
                    .trimIndent()
            )
            handle.execute(
                """
                CREATE TABLE document_tags (
                   document_uuid VARCHAR(36) NOT NULL,
                   name VARCHAR(255) NOT NULL,
                   kind VARCHAR(255) NOT NULL,
                   PRIMARY KEY (document_uuid, name, kind)
                )
                """
                    .trimIndent()
            )
        }

        val documentsDb = DocumentsDatabase(jdbi)
        postsDb = PostsDatabase(documentsDb)
        filesDb = FilesDatabase(jdbi)
        rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
    }

    @Test
    fun `broadcast should always include RSS`() = runTest {
        val formModule = FormModule(null, null, null, rssModule)
        val request = NewPostRequest(content = "Hello world")

        val result = formModule.broadcastPost(request)

        assertTrue(result.isRight())
        when (result) {
            is Either.Right -> {
                assertTrue(result.value.containsKey("rss"))
            }
            is Either.Left -> assertTrue(false, "Expected success")
        }
    }

    @Test
    fun `broadcast should return composite error on partial failure`() = runTest {
        val formModule = FormModule(null, null, null, rssModule)
        val request = NewPostRequest(content = "Hello world", targets = listOf("mastodon"))

        val result = formModule.broadcastPost(request)

        assertTrue(result.isLeft())
        when (result) {
            is Either.Left -> assertTrue(result.value is CompositeError)
            is Either.Right -> assertTrue(false, "Expected failure")
        }
    }
}
