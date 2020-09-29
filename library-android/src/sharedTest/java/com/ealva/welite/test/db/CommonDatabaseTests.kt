/*
 * Copyright 2020 Eric A. Snell
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ealva.welite.test.db

import android.content.Context
import com.ealva.welite.db.Database
import com.ealva.welite.db.OpenParams
import com.ealva.welite.db.table.ColumnMetadata
import com.ealva.welite.db.table.FieldType
import com.ealva.welite.db.table.Table
import com.ealva.welite.test.shared.AlbumTable
import com.ealva.welite.test.shared.ArtistAlbumTable
import com.ealva.welite.test.shared.ArtistTable
import com.ealva.welite.test.shared.MediaFileTable
import com.ealva.welite.test.shared.SqlExecutorSpy
import com.ealva.welite.test.shared.withTestDatabase
import com.ealva.welite.test.db.trigger.DeleteAlbumTrigger
import com.ealva.welite.test.db.trigger.DeleteArtistTrigger
import com.ealva.welite.test.db.trigger.DeleteMediaTrigger
import com.ealva.welite.test.db.trigger.InsertMediaTrigger
import com.ealva.welite.test.db.view.FullMediaView
import com.nhaarman.expect.ListMatcher
import com.nhaarman.expect.StringMatcher
import com.nhaarman.expect.expect
import com.nhaarman.expect.fail
import kotlinx.coroutines.CoroutineDispatcher

object CommonDatabaseTests {
  suspend fun testInTransaction(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(SomeMediaTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      var visitedOngoing = false
      expect(inTransaction).toBe(false)
      transaction {
        expect(this@withTestDatabase.inTransaction).toBe(true)
        this@withTestDatabase.ongoingTransaction {
          visitedOngoing = true
        }
        expect(visitedOngoing).toBe(true)
        rollback()
      }
      expect(inTransaction).toBe(false)
      query {
        expect(this@withTestDatabase.inTransaction).toBe(true)
      }
      expect(inTransaction).toBe(false)
    }
  }

  suspend fun testSqliteVersion(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(SomeMediaTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        // supports "n.nn.nn"  nn is 1 or 2 numbers
        expect(sqliteVersion).matches(Regex("""[3-9]\.[0-9]{1,2}\.[0-9]{1,2}"""))
      }
    }
  }

  suspend fun testDbLifecycle(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    var onConfigureCalled = false
    var onCreateCalled = false
    var onOpenCalled = false

    val db = Database(
      context = appCtx,
      version = 1,
      tables = listOf(SomeMediaTable),
      migrations = emptyList(),
      requireMigration = false,
      openParams = OpenParams(
        allowWorkOnUiThread = true,
        dispatcher = testDispatcher
      )
    ) {
      onConfigure {
        onConfigureCalled = true
      }
      onCreate {
        onCreateCalled = true
      }
      onOpen {
        onOpenCalled = true
      }
    }
    db.transaction {
      expect(onConfigureCalled).toBe(true) { "onConfigure not called" }
      expect(onCreateCalled).toBe(true) { "onCreate not called" }
      expect(onOpenCalled).toBe(true) { "onOpen not called" }
      rollback()
    }
    db.close()
  }

  suspend fun createTableTest(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(SomeMediaTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(SomeMediaTable.exists).toBe(true)
        val description = SomeMediaTable.description
        expect(description.tableName).toBe(SomeMediaTable.tableName)
        expect(description.columnsMetadata.size).toBe(6)
        expect(description.columnsMetadata[0]).toBe(
          ColumnMetadata(0, SomeMediaTable.id.name, FieldType.IntegerField, false, "NULL", 1)
        )
        expect(description.columnsMetadata[1]).toBe(
          ColumnMetadata(1, SomeMediaTable.mediaUri.name, FieldType.TextField, false, "NULL", 0)
        )
        expect(description.columnsMetadata[2]).toBe(
          ColumnMetadata(2, SomeMediaTable.fileName.name, FieldType.TextField, false, "NULL", 0)
        )
        expect(description.columnsMetadata[3]).toBe(
          ColumnMetadata(
            3,
            SomeMediaTable.mediaTitle.name,
            FieldType.TextField,
            false,
            "'Title'",
            0
          )
        )
        expect(description.columnsMetadata[4]).toBe(
          ColumnMetadata(4, SomeMediaTable.real.name, FieldType.RealField, false, "NULL", 0)
        )
        expect(description.columnsMetadata[5]).toBe(
          ColumnMetadata(5, SomeMediaTable.blob.name, FieldType.BlobField, true, "NULL", 0)
        )

        val tableIdentity = SomeMediaTable.identity.value

        SqlExecutorSpy().let { spy ->
          SomeMediaTable.create(spy)
          val statements = spy.execSqlList
          expect(statements).toHaveSize(2)
          expect(statements[0]).toBe(
            "CREATE TABLE IF NOT EXISTS " + tableIdentity + " (" +
              SomeMediaTable.columns.joinToString { it.descriptionDdl() } + ")"
          )
          expect(statements[1]).toBe(
            "CREATE INDEX IF NOT EXISTS \"SomeMedia_MediaFileName_Real\" ON " +
              "\"SomeMedia\"(\"MediaFileName\", \"Real\")"
          )
        }
        val tableSql = SomeMediaTable.sql
        expect(tableSql.tableName).toBe("SomeMedia")
        expect(tableSql.table.first()).toBe(
          "CREATE TABLE $tableIdentity (\"_id\" INTEGER NOT NULL PRIMARY KEY, \"MediaUri\" TEXT " +
            "NOT NULL UNIQUE, \"MediaFileName\" TEXT NOT NULL COLLATE NOCASE, \"MediaTitle\" " +
            "TEXT NOT NULL COLLATE NOCASE DEFAULT 'Title', \"Real\" REAL NOT NULL, \"Blob\" BLOB)"
        )
        expect(tableSql.indices.first()).toBe(
          "CREATE INDEX \"SomeMedia_MediaFileName_Real\" ON " +
            "\"SomeMedia\"(\"MediaFileName\", \"Real\")"
        )
        expect(tableSql.triggers).toBeEmpty()
        expect(tableSql.views).toBeEmpty()
      }
    }
  }

  suspend fun testCreateTablesRearrangeOrderForCreate(
    appCtx: Context,
    testDispatcher: CoroutineDispatcher
  ) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(ArtistAlbumTable, MediaFileTable, ArtistTable, AlbumTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(MediaFileTable.exists).toBe(true)
        expect(AlbumTable.exists).toBe(true)
        expect(ArtistTable.exists).toBe(true)
        expect(ArtistAlbumTable.exists).toBe(true)

        // This is coupled too tightly to the algorithm
        // We only care about table A before table B relationships, not specific ordering
        val tables = this@withTestDatabase.tables
        expect(tables[0]).toBe(ArtistTable)
        expect(tables[1]).toBe(AlbumTable)
        expect(tables[2]).toBe(ArtistAlbumTable)
        expect(tables[3]).toBe(MediaFileTable)
      }
    }
  }

  suspend fun testIntegritySunnyDay(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(ArtistAlbumTable, MediaFileTable, ArtistTable, AlbumTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(MediaFileTable.exists).toBe(true)
        expect(AlbumTable.exists).toBe(true)
        expect(ArtistTable.exists).toBe(true)
        expect(ArtistAlbumTable.exists).toBe(true)

        val tegridy = tegridyCheck()
        expect(tegridy).toHaveSize(1)
        expect(tegridy[0]).toBe("ok")
      }
    }
  }

  suspend fun testCreateTablesWithForeignKeys(
    appCtx: Context,
    testDispatcher: CoroutineDispatcher
  ) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(ArtistAlbumTable, MediaFileTable, ArtistTable, AlbumTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(MediaFileTable.exists).toBe(true)
        expect(AlbumTable.exists).toBe(true)
        expect(ArtistTable.exists).toBe(true)
        expect(ArtistAlbumTable.exists).toBe(true)

        ArtistTable.sql.let { artistSql ->
          expect(artistSql.table).toHaveSize(1)
          expect(artistSql.table[0]).toBe(
            """CREATE TABLE "Artist" ("_id" INTEGER NOT NULL PRIMARY KEY, """ +
              """"ArtistName" TEXT NOT NULL COLLATE NOCASE)"""
          )
          expect(artistSql.indices).toHaveSize(1)
          expect(artistSql.indices[0]).toBe(
            """CREATE UNIQUE INDEX "Artist_ArtistName_unique" ON "Artist"("ArtistName")"""
          )
        }

        AlbumTable.sql.let { albumSql ->
          expect(albumSql.table).toHaveSize(1)
          expect(albumSql.table[0]).toBe(
            """CREATE TABLE "Album" ("_id" INTEGER NOT NULL PRIMARY KEY, "AlbumName" TEXT """ +
              """NOT NULL COLLATE NOCASE, "ArtistName" TEXT NOT NULL COLLATE NOCASE)"""
          )
          expect(albumSql.indices).toHaveSize(1)
          expect(albumSql.indices[0]).toBe(
            """CREATE UNIQUE INDEX "Album_AlbumName_ArtistName_unique" ON""" +
              """ "Album"("AlbumName", "ArtistName")"""
          )
        }

        ArtistAlbumTable.sql.let { artistAlbum ->
          expect(artistAlbum.table).toHaveSize(1)
          expect(artistAlbum.table[0]).toBe(
            """CREATE TABLE "ArtistAlbum" ("_id" INTEGER NOT NULL PRIMARY KEY,""" +
              """ "ArtistId" INTEGER NOT NULL, "AlbumId" INTEGER NOT NULL, CONSTRAINT""" +
              """ "fk_ArtistAlbum_ArtistId__id" FOREIGN KEY ("ArtistId") REFERENCES""" +
              """ "Artist"("_id"), CONSTRAINT "fk_ArtistAlbum_AlbumId__id"""" +
              """ FOREIGN KEY ("AlbumId") REFERENCES "Album"("_id"))"""
          )
          expect(artistAlbum.indices).toHaveSize(3)
          expect(artistAlbum.indices[0])
            .toBe("""CREATE INDEX "ArtistAlbum_ArtistId" ON "ArtistAlbum"("ArtistId")""")
          expect(artistAlbum.indices[1])
            .toBe("""CREATE INDEX "ArtistAlbum_AlbumId" ON "ArtistAlbum"("AlbumId")""")
          expect(artistAlbum.indices[2]).toBe(
            """CREATE UNIQUE INDEX "ArtistAlbum_ArtistId_AlbumId_unique" ON""" +
              """ "ArtistAlbum"("ArtistId", "AlbumId")"""
          )
        }

        MediaFileTable.sql.let { mediaSql ->
          expect(mediaSql.table).toHaveSize(1)
          expect(mediaSql.table[0]).toBe(
            """CREATE TABLE "MediaFile" ("_id" INTEGER NOT NULL PRIMARY KEY,""" +
              """ "MediaTitle" TEXT NOT NULL, "MediaUri"""" +
              """ TEXT NOT NULL UNIQUE, "ArtistId" INTEGER NOT NULL, "AlbumId"""" +
              """ INTEGER NOT NULL, CONSTRAINT "fk_MediaFile_ArtistId__id" FOREIGN KEY""" +
              """ ("ArtistId") REFERENCES "Artist"("_id"), CONSTRAINT""" +
              """ "fk_MediaFile_AlbumId__id" FOREIGN KEY ("AlbumId") REFERENCES "Album"("_id"))"""
          )
          expect(mediaSql.indices).toHaveSize(0)
        }
      }
    }
  }

  suspend fun testCreateAndDropTable(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = emptyList(),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      transaction { MediaFileTable.create() }
      query { expect(MediaFileTable.exists).toBe(true) }
      transaction { MediaFileTable.drop() }
      query { expect(MediaFileTable.exists).toBe(false) }
    }
  }

  suspend fun testCreateAndDropView(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(ArtistAlbumTable, MediaFileTable, ArtistTable, AlbumTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(MediaFileTable.exists).toBe(true)
        expect(AlbumTable.exists).toBe(true)
        expect(ArtistTable.exists).toBe(true)
        expect(ArtistAlbumTable.exists).toBe(true)
      }
      transaction { FullMediaView.create() }
      query { expect(FullMediaView.exists).toBe(true) }
      transaction { FullMediaView.drop() }
      query { expect(FullMediaView.exists).toBe(false) }
    }
  }

  suspend fun testCreateAndDropTrigger(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    withTestDatabase(
      context = appCtx,
      tables = listOf(ArtistAlbumTable, MediaFileTable, ArtistTable, AlbumTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query {
        expect(MediaFileTable.exists).toBe(true)
        expect(AlbumTable.exists).toBe(true)
        expect(ArtistTable.exists).toBe(true)
        expect(ArtistAlbumTable.exists).toBe(true)
      }
      transaction {
        DeleteArtistTrigger.create()
        DeleteAlbumTrigger.create()
        InsertMediaTrigger.create()
        DeleteMediaTrigger.create()
      }
      query {
        expect(DeleteArtistTrigger.exists).toBe(true)
        expect(DeleteAlbumTrigger.exists).toBe(true)
        expect(InsertMediaTrigger.exists).toBe(true)
        expect(DeleteMediaTrigger.exists).toBe(true)
      }
      transaction {
        DeleteArtistTrigger.drop()
        DeleteAlbumTrigger.drop()
        InsertMediaTrigger.drop()
        DeleteMediaTrigger.drop()
      }
      query {
        expect(DeleteArtistTrigger.exists).toBe(false)
        expect(DeleteAlbumTrigger.exists).toBe(false)
        expect(InsertMediaTrigger.exists).toBe(false)
        expect(DeleteMediaTrigger.exists).toBe(false)
      }
    }
  }

  suspend fun testCreateAndDropIndex(appCtx: Context, testDispatcher: CoroutineDispatcher) {
    val aTable = object : Table() {
      @Suppress("unused") val id = long("_id") { primaryKey() }
      val artistName = text("ArtistName") { collateNoCase() }
    }
    withTestDatabase(
      context = appCtx,
      tables = listOf(aTable),
      testDispatcher = testDispatcher,
      enableForeignKeyConstraints = true
    ) {
      query { expect(aTable.exists).toBe(true) }
      val index = transaction { aTable.index(aTable.artistName).also { index -> index.create() } }
      query {
        expect(index.exists).toBe(true)
        expect(aTable.indices).toContain(index)
      }
      transaction { index.drop() }
      query {
        expect(index.exists).toBe(false)
        expect(aTable.indices).toNotContain(index)
      }
    }
  }
}

fun <T> ListMatcher<T>.toNotContain(expected: T, message: (() -> Any?)? = null) {
  if (actual == null) {
    fail("Expected value to contain $expected, but the actual value was null.", message)
  }

  if ((actual as List<T>).contains(expected)) {
    fail("Expected $actual to contain $expected", message)
  }
}

private fun StringMatcher.matches(regex: Regex) {
  val value = actual ?: fail("Expected to match value against $regex but was null")
  if (!value.matches(regex)) fail("Expected `$value` to match $regex")
}

object SomeMediaTable : Table() {
  val id = integer("_id") { primaryKey() }
  val mediaUri = text("MediaUri") { unique() }
  val fileName = text("MediaFileName") { collateNoCase() }
  val mediaTitle = text("MediaTitle") { collateNoCase().default("Title") }
  val real = double("Real")
  val blob = optBlob("Blob")

  init {
    index(fileName, real)
  }
}
