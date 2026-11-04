package com.github.tropin.ridermcp.ide

import net.jqwik.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlReadOnlyGuardPropertyTest {

    @Property
    fun `plain selects are always allowed`(
        @ForAll("tableName") tableName: String,
        @ForAll("columnName") columnName: String
    ) {
        if (tableName.isBlank() || columnName.isBlank()) return
        val sql = "SELECT $columnName FROM $tableName WHERE id = 1"
        val result = SqlReadOnlyGuard.check(sql, true)
        assertTrue(result.allowed)
    }

    @Provide
    fun tableName(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.of("users", "orders", "products", "customers", "employees", "departments",
            "categories", "suppliers", "inventory", "transactions", "accounts", "profiles",
            "settings", "logs", "events", "notifications", "payments", "subscriptions",
            "user_roles", "order_items", "product_tags", "customer_addresses")
    )

    @Provide
    fun columnName(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.of("id", "name", "email", "username", "password", "created_at", "updated_at",
            "status", "type", "value", "count", "amount", "price", "quantity", "description",
            "first_name", "last_name", "phone", "address", "city", "country", "is_active",
            "role", "permissions", "metadata", "tags", "version", "priority")
    )

    @Property
    fun `DML on real tables rejected`(@ForAll("tableName") tableName: String) {
        val dmlStatements = listOf(
            "UPDATE $tableName SET name = 'x'",
            "DELETE FROM $tableName WHERE id = 1",
            "INSERT INTO $tableName VALUES (1)",
            "INSERT INTO $tableName SELECT 1",
            "MERGE INTO $tableName USING s ON 1=1 WHEN MATCHED THEN DELETE",
            "TRUNCATE TABLE $tableName",
            "DROP TABLE $tableName",
            "ALTER TABLE $tableName ADD c INT",
            "CREATE TABLE $tableName (id INT)",
            "CREATE INDEX ix ON $tableName(id)"
        )

        dmlStatements.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `temp tables are allowed`(@ForAll("tempTableName") tempTableName: String) {
        val tempSqls = listOf(
            "SELECT name INTO #$tempTableName FROM sys.databases; INSERT INTO #$tempTableName VALUES ('x'); UPDATE #$tempTableName SET name = 'y'; DELETE FROM #$tempTableName; DROP TABLE #$tempTableName",
            "DECLARE @$tempTableName TABLE (id INT); INSERT @$tempTableName SELECT 1; UPDATE @$tempTableName SET id = 2; DELETE @$tempTableName",
            "CREATE TABLE #$tempTableName (db SYSNAME); CREATE INDEX ix ON #$tempTableName(db); TRUNCATE TABLE #$tempTableName; ALTER TABLE #$tempTableName ADD n INT; DROP TABLE IF EXISTS #$tempTableName",
            "INSERT INTO tempdb..#$tempTableName SELECT 1",
            "DECLARE c CURSOR LOCAL FAST_FORWARD FOR SELECT name FROM sys.databases; OPEN c; FETCH NEXT FROM c INTO @db; CLOSE c; DEALLOCATE c"
        )

        tempSqls.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed: $sql")
        }
    }

    @Provide
    fun tempTableName(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.of("temp_users", "tmp_orders", "t_products", "temp_data", "staging", "work_table")
    )

    @Property
    fun `PostgreSQL temp tables allowed`() {
        val sql = "CREATE TEMP TABLE temp_test AS SELECT 1"
        val result = SqlReadOnlyGuard.check(sql, false)
        assertTrue(result.allowed)
    }

    @Property
    fun `PostgreSQL DML on temp tables rejected (guard cannot track them)`() {
        val dmlOnTemp = listOf(
            "CREATE TEMP TABLE temp_test (id INT); INSERT INTO temp_test VALUES (1)",
            "CREATE TEMP TABLE tmp AS SELECT 1; UPDATE tmp SET id = 2; DELETE FROM tmp"
        )
        dmlOnTemp.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, false)
            assertFalse(result.allowed, "Expected rejected (guard limitation): $sql")
        }
    }

    @Property
    fun `PostgreSQL DML on real tables rejected`() {
        val dmlOnReal = listOf(
            "UPDATE real_table SET x = 1",
            "DELETE FROM real_table",
            "INSERT INTO real_table VALUES (1)"
        )
        dmlOnReal.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, false)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `transaction control statements rejected`() {
        val txStatements = listOf(
            "COMMIT",
            "SET IMPLICIT_TRANSACTIONS OFF",
            "SET TRANSACTION READ WRITE",
            "START TRANSACTION READ WRITE",
            "SET DEFAULT_TRANSACTION_READ_ONLY = OFF"
        )

        txStatements.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `allowed transaction statements`() {
        val allowedTx = listOf(
            "SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED; SELECT 1",
            "BEGIN TRAN; SELECT 1; ROLLBACK"
        )

        allowedTx.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed: $sql")
        }
    }

    @Property
    fun `exec read-only system procedures allowed`() {
        val readOnlyProcs = listOf(
            "EXEC sp_who2",
            "EXEC sys.sp_helptext 'dbo.p'",
            "EXEC master..sp_spaceused",
            "sp_who"
        )

        readOnlyProcs.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed: $sql")
        }
    }

    @Property
    fun `exec user procedures rejected`() {
        val userProcs = listOf(
            "EXEC dbo.usp_cleanup",
            "EXEC sp_rename 'a', 'b'",
            "EXEC sp_configure 'x', 1"
        )

        userProcs.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `dynamic SQL literal allowed`() {
        val dynamicAllowed = listOf(
            "EXEC sp_executesql N'SELECT * FROM sys.tables WHERE name = @n', N'@n sysname', @n = N'x'",
            "EXEC('SELECT 1')",
            "EXEC sp_MSforeachdb 'SELECT db_name()'"
        )

        dynamicAllowed.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed: $sql")
        }
    }

    @Property
    fun `dynamic SQL with DML rejected`() {
        val dynamicDml = listOf(
            "EXEC sp_executesql N'UPDATE t SET x = 1'",
            "EXEC('DROP TABLE t')",
            "EXEC sp_MSforeachdb 'USE ?; DELETE FROM t'",
            "DECLARE @sql NVARCHAR(MAX) = N'UPDATE t SET x = 1'; EXEC sp_executesql @sql"
        )

        dynamicDml.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `PostgreSQL DO blocks with DML rejected`() {
        val pgDml = listOf(
            "DO \$\$ BEGIN DELETE FROM t; END \$\$",
            "SELECT * FROM t FOR UPDATE",
            "VACUUM t",
            "COPY t FROM '/tmp/x'"
        )

        pgDml.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, false)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }

    @Property
    fun `PostgreSQL read-only DO allowed`() {
        val readOnly = listOf(
            "SELECT \$\$UPDATE t\$\$",
            "SELECT load, cluster FROM servers ORDER BY load"
        )

        readOnly.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, false)
            assertTrue(result.allowed, "Expected allowed: $sql")
        }
    }

    @Property
    fun `MSSQL T-SQL batches with GO handled`() {
        val tsql = """
            DECLARE @x INT = 0;
            WHILE @x < 10
            BEGIN
                SELECT @x;
                SET @x = @x + 1;
            END
            GO
            SELECT 1;
        """.trimIndent()

        val result = SqlReadOnlyGuard.check(tsql, true)
        assertTrue(result.allowed)
    }

    @Property
    fun `MSSQL DML in batch rejected`() {
        val tsqlDml = """
            DECLARE @x INT = 0;
            UPDATE t SET x = @x;
            GO
        """.trimIndent()

        val result = SqlReadOnlyGuard.check(tsqlDml, true)
        assertFalse(result.allowed)
    }

    @Property
    fun `comments do not affect classification`() {
        val commentedDml = listOf(
            "-- UPDATE t SET x = 1\nSELECT 1",
            "/* UPDATE t SET x = 1 */ SELECT 1",
            "SELECT 1 /* DROP TABLE t */"
        )

        commentedDml.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed (commented): $sql")
        }
    }

    @Property
    fun `quoted identifiers allowed`() {
        val quotedSql = listOf(
            """SELECT [delete], [insert] FROM t""",
            """SELECT "delete", "insert" FROM t""",
            """SELECT `delete`, `insert` FROM t"""
        )

        quotedSql.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertTrue(result.allowed, "Expected allowed (quoted): $sql")
        }
    }

    @Property
    fun `complex nested queries allowed`() {
        val complexSelect = """
            WITH cte AS (
                SELECT name, ROW_NUMBER() OVER (PARTITION BY type ORDER BY name) AS rn
                FROM sys.objects
                WHERE type IN ('U', 'V')
            )
            SELECT name FROM cte WHERE rn = 1
        """.trimIndent()

        val result = SqlReadOnlyGuard.check(complexSelect, true)
        assertTrue(result.allowed)
    }

    @Property
    fun `MERGE with DML rejected`() {
        val mergeDml = "MERGE INTO dbo.target USING src ON target.id = src.id WHEN MATCHED THEN UPDATE SET name = src.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (src.id, src.name)"
        val result = SqlReadOnlyGuard.check(mergeDml, true)
        assertFalse(result.allowed)
    }

    @Property
    fun `TRUNCATE TABLE rejected`() {
        val truncateSql = "TRUNCATE TABLE dbo.large_table"
        val result = SqlReadOnlyGuard.check(truncateSql, true)
        assertFalse(result.allowed)
    }

    @Property
    fun `BACKUP DATABASE rejected`() {
        val backupSql = "BACKUP DATABASE mydb TO DISK = 'backup.bak'"
        val result = SqlReadOnlyGuard.check(backupSql, true)
        assertFalse(result.allowed)
    }

    @Property
    fun `KILL command rejected`() {
        val killSql = "KILL 55"
        val result = SqlReadOnlyGuard.check(killSql, true)
        assertFalse(result.allowed)
    }

    @Property
    fun `GRANT REVOKE DENY rejected`() {
        val securitySql = listOf(
            "GRANT SELECT ON t TO u",
            "REVOKE SELECT ON t FROM u",
            "DENY SELECT ON t TO u"
        )

        securitySql.forEach { sql ->
            val result = SqlReadOnlyGuard.check(sql, true)
            assertFalse(result.allowed, "Expected rejected: $sql")
        }
    }
}