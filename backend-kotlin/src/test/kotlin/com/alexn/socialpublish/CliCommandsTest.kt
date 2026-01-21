package com.alexn.socialpublish

import com.github.ajalt.clikt.testing.test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CliCommandsTest {

    @Test
    fun `gen-bcrypt-hash with --password option should work`() {
        val password = "testpassword123"

        val result = SocialPublishCli().test("gen-bcrypt-hash --password $password")

        assertEquals(0, result.statusCode)

        // Should not prompt
        assertTrue(!result.stdout.contains("Enter password"))
        assertTrue(result.stdout.contains("BCrypt hash:"))

        // Extract the hash from output
        val lines = result.stdout.trim().lines()
        val hashLine = lines.firstOrNull { it.startsWith("$") }

        // BCrypt hashes start with $2a$, $2b$, or $2y$ and are 60 characters
        assertTrue(hashLine != null)
        assertTrue(hashLine.startsWith("$2"))
        assertTrue(hashLine.length == 60)
    }

    @Test
    fun `gen-bcrypt-hash with --quiet should output only the hash`() {
        val password = "testpassword123"

        val result = SocialPublishCli().test("gen-bcrypt-hash --quiet --password $password")

        assertEquals(0, result.statusCode)

        // Should not contain extra messages
        assertTrue(!result.stdout.contains("BCrypt hash:"))

        // Output should be just the hash
        val hash = result.stdout.trim()
        assertTrue(hash.startsWith("$2"))
        assertTrue(hash.length == 60)
    }

    @Test
    fun `main command should show help when no subcommand is given`() {
        val result = SocialPublishCli().test("")

        // Should show help
        assertTrue(result.stdout.contains("Usage:") || result.stderr.contains("Usage:"))
        val output = result.stdout + result.stderr
        assertTrue(output.contains("start-server"))
        assertTrue(output.contains("gen-bcrypt-hash"))
    }

    @Test
    fun `start-server should fail without required options`() {
        val result = SocialPublishCli().test("start-server")

        // Should fail due to missing required options
        assertTrue(result.statusCode != 0)
        val output = result.stdout + result.stderr
        assertTrue(
            output.contains("Error") || output.contains("Missing") || output.contains("required")
        )
    }
}
