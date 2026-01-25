package socialpublish.backend.modules

import arrow.fx.coroutines.resourceScope
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.db.Database
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.PostsDatabase

class FormModuleTest {
    private lateinit var postsDb: PostsDatabase
    private lateinit var filesDb: FilesDatabase
    private lateinit var rssModule: RssModule

    @BeforeEach
    fun setup(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db").toString()
        resourceScope {
            val db = Database.connect(dbPath).bind()
            val documentsDb = DocumentsDatabase(db)
            postsDb = PostsDatabase(documentsDb)
            filesDb = FilesDatabase(db)
            rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        }
    }

    @Test
    fun `FormModule can be instantiated`() = runTest {
        val formModule = FormModule(null, null, null, null, rssModule)
        // Basic test to ensure FormModule can be created
    }
}
