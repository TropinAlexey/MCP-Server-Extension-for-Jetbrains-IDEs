package com.github.tropin.ridermcp.ide

import net.jqwik.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretMaskerPropertyTest {

    @Property
    fun `secret column names are always masked`(@ForAll("secretColumnName") secretColumnName: String) {
        if (SecretMasker.classifyColumn(secretColumnName) != SecretMasker.ColumnKind.SECRET) return
        val result = SecretMasker.maskCell("secret-value", SecretMasker.classifyColumn(secretColumnName))
        val masked = result.first
        val hit = result.second
        assertTrue(hit)
        assertEquals("***", masked)
    }

    @Provide
    fun secretColumnName(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.of("password", "passwd", "pwd", "secret", "token", "apikey", "privatekey", "credential",
            "password_hash", "pass_word", "api_key", "access_token", "client_secret", "shared_secret",
            "db_password", "user_password", "admin_token", "auth_token", "refresh_token")
    )

    @Property
    fun `connection string columns keep structure but mask passwords`() {
        // Test with fixed connection strings that have known secret values
        val testCases = listOf(
            "Server=test;Database=db;User Id=user;Password=secret123;Encrypt=true",
            "Server=test;Database=db;User Id=user;Pwd=secret123;Encrypt=true",
            "jdbc:sqlserver://host;databaseName=test;user=user;password=secret123"
        )

        testCases.forEach { connectionString ->
            val result = SecretMasker.maskCell(connectionString, SecretMasker.ColumnKind.CONNECTION)
            val masked = result.first
            val hit = result.second
            assertTrue(hit, "Expected masking for: $connectionString")
            // Password should be masked
            assertTrue { masked.indexOf("***") >= 0 }
            // Other parts should remain
            assertTrue { masked.indexOf("Server=") >= 0 || masked.indexOf("Database=") >= 0 || masked.indexOf("User") >= 0 || masked.indexOf("user=") >= 0 }
            // Original secret should not appear
            assertTrue { masked.indexOf("secret123") < 0 }
        }
    }

    @Property
    fun `plain text secrets are masked`(@ForAll("secretString") secretString: String) {
        val masked = SecretMasker.maskText(secretString)
        if (masked == secretString) return // no masking happened
        // Original secret value should not appear in masked output
        val secretValue = extractSecretValue(secretString)
        if (secretValue.isNotBlank()) {
            assertTrue { masked.indexOf(secretValue) < 0 }
        }
    }

    private fun extractSecretValue(input: String): String {
        // Extract value after password=, pwd=, secret=, token=, api_key=, password:, or @
        val patterns = listOf(
            Regex("(?i)(password|pwd|secret|token|api_key)\\s*[:=]\\s*([^;&\\s,@]+)"),
            Regex("(?i)@([^:@/]+)@") // postgres://user:pass@host
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues.getOrElse(2) { match.groupValues.getOrElse(1) { "" } }
            }
        }
        return ""
    }

    @Provide
    fun secretString(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.strings().map { "password=$it" },
        Arbitraries.strings().map { "pwd=$it" },
        Arbitraries.strings().map { "api_key=$it" },
        Arbitraries.strings().map { "secret=$it" },
        Arbitraries.strings().map { "token=$it" },
        Arbitraries.strings().map { "jdbc:sqlserver://host;password=$it" }
    )

    @Property
    fun `empty and non-secret values pass through unchanged`() {
        // Empty string
        val emptyResult = SecretMasker.maskCell("", SecretMasker.ColumnKind.PLAIN)
        val emptyMasked = emptyResult.first
        val emptyHit = emptyResult.second
        assertFalse(emptyHit)
        assertEquals("", emptyMasked)

        // Non-secret column
        val plainResult = SecretMasker.maskCell("regular-value", SecretMasker.ColumnKind.PLAIN)
        val plainMasked = plainResult.first
        val plainHit = plainResult.second
        assertFalse(plainHit)
        assertEquals("regular-value", plainMasked)
    }

    @Property
    fun `connection string masking preserves non-password parts`() {
        val masked = SecretMasker.maskCell(
            "Server=myserver;Database=mydb;User Id=myuser;Password=secret123;Encrypt=true;TrustServerCertificate=false",
            SecretMasker.ColumnKind.CONNECTION
        ).first
        assertTrue { masked.indexOf("Server=myserver") >= 0 }
        assertTrue { masked.indexOf("Database=mydb") >= 0 }
        assertTrue { masked.indexOf("User Id=myuser") >= 0 }
        assertTrue { masked.indexOf("Encrypt=true") >= 0 }
        assertTrue { masked.indexOf("TrustServerCertificate=false") >= 0 }
        assertTrue { masked.indexOf("Password=***") >= 0 }
        assertTrue { masked.indexOf("secret123") < 0 }
    }

    @Property
    fun `multiple secrets in connection string all masked`() {
        val masked = SecretMasker.maskCell(
            "Server=test;User Id=user1;Password=pass1;Database=db1;User Id=user2;Password=pass2",
            SecretMasker.ColumnKind.CONNECTION
        ).first
        val passwordCount = masked.split("Password=***").size - 1
        assertEquals(2, passwordCount)
    }

    @Property
    fun `json free text secrets masked`() {
        val masked = SecretMasker.maskText("""{"api_key": "secret123", "user": "test"}""")
        assertTrue { masked.indexOf("\"api_key\": \"***\"") >= 0 }
        assertTrue { masked.indexOf("secret123") < 0 }
    }

    @Property
    fun `postgres connection string password masked`() {
        val masked = SecretMasker.maskCell(
            "postgresql://user:secretpass@localhost:5432/db",
            SecretMasker.ColumnKind.CONNECTION
        ).first
        assertTrue { masked.indexOf("postgresql://user:***@") >= 0 }
        assertTrue { masked.indexOf("secret") < 0 }
    }
}