/*
 * Copyright 2020 eAlva.com
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

@file:Suppress(
  "NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING",
  "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE_WARNING"
)

package com.ealva.welite.db

import android.content.Context
import android.net.Uri
import android.os.Build.VERSION_CODES.LOLLIPOP
import androidx.test.core.app.ApplicationProvider
import com.ealva.welite.db.expr.eq
import com.ealva.welite.db.statements.updateColumns
import com.ealva.welite.db.table.OnConflict
import com.ealva.welite.test.db.table.Person
import com.ealva.welite.test.db.table.Place
import com.ealva.welite.test.db.table.Review
import com.ealva.welite.test.db.table.withPlaceTestDatabase
import com.ealva.welite.test.shared.AlbumTable
import com.ealva.welite.test.shared.ArtistAlbumTable
import com.ealva.welite.test.shared.ArtistTable
import com.ealva.welite.test.shared.CoroutineRule
import com.ealva.welite.test.shared.MEDIA_TABLES
import com.ealva.welite.test.shared.MediaFileTable
import com.ealva.welite.test.shared.withTestDatabase
import com.nhaarman.expect.expect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [LOLLIPOP])
class UpdateTests {
  @get:Rule var coroutineRule = CoroutineRule()

  private lateinit var appCtx: Context

  @Before
  fun setup() {
    appCtx = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun `test update`() = coroutineRule.runBlockingTest {
    withTestDatabase(appCtx, MEDIA_TABLES, coroutineRule.testDispatcher) {
      val badName = "Led Zepelin"
      val goodName = "Led Zeppelin"
      transaction {
        val uri = Uri.fromFile(File("""/Music/Song.mp3"""))
        insertData(badName, "Houses of the Holy", uri)
        setSuccessful()
      }
      transaction {
        ArtistTable.updateColumns { it[artistName] = goodName }
          .where { artistName eq badName }
          .update()

        setSuccessful()
      }
      query {
        expect(ArtistTable.selectWhere(ArtistTable.artistName eq goodName).count()).toBe(1)
      }
    }
  }

  @Test
  fun `test update Person name`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = setOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      val nathaliaId = "nathalia"
      query {
        expect(
          Person
            .select(Person.name)
            .where { Person.id eq nathaliaId }
            .sequence { it[Person.name] }
            .single()
        ).toBe("Nathalia")
      }

      val newName = "Natalie"
      transaction {
        Person
          .updateColumns { it[name] = newName }
          .where { id eq nathaliaId }
          .update()
      }

      query {
        expect(
          Person
            .select(Person.name)
            .where { Person.id eq nathaliaId }
            .sequence { it[Person.name] }
            .single()
        ).toBe(newName)
      }
    }
  }

  @Test
  fun `test update with join`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = setOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      val join = Person.innerJoin(Review)
      transaction {
        join.updateColumns {
          it[Review.post] = Person.name
          it[Review.value] = 123
        }
      }
      query {
        join.selectAll().sequence {
          expect(it[Person.name]).toBe(it[Review.post])
          expect(it[Review.value]).toBe(123)
        }
      }
    }
  }

  private fun Transaction.insertData(
    artist: String,
    album: String,
    uri: Uri
  ): Triple<Long, Long, Long> {
    val idArtist: Long = ArtistTable.select(ArtistTable.id)
      .where { ArtistTable.artistName eq artist }
      .sequence { it[ArtistTable.id] }
      .singleOrNull() ?: ArtistTable.insert { it[artistName] = artist }

    val idAlbum: Long = AlbumTable.select(AlbumTable.id)
      .where { AlbumTable.albumName eq album }
      .sequence { it[AlbumTable.id] }
      .singleOrNull() ?: AlbumTable.insert {
      it[albumName] = album
      it[artistName] = artist
    }

    ArtistAlbumTable.insert(OnConflict.Ignore) {
      it[artistId] = idArtist
      it[albumId] = idAlbum
    }

    val mediaId = MediaFileTable.insert {
      it[mediaTitle] = "Some Title"
      it[mediaUri] = uri.toString()
      it[artistId] = idArtist
      it[albumId] = idAlbum
    }
    return Triple(idArtist, idAlbum, mediaId)
  }
}
