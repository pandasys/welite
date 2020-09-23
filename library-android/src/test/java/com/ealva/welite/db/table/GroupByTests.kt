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

package com.ealva.welite.db.table

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ealva.welite.db.expr.GroupConcat
import com.ealva.welite.db.expr.count
import com.ealva.welite.db.expr.eq
import com.ealva.welite.db.expr.groupConcat
import com.ealva.welite.db.expr.max
import com.ealva.welite.test.common.CoroutineRule
import com.ealva.welite.test.common.expect
import com.ealva.welite.test.common.runBlockingTest
import com.nhaarman.expect.expect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.LOLLIPOP])
class GroupByTests {
  @get:Rule var coroutineRule = CoroutineRule()

  private lateinit var appCtx: Context

  @Before
  fun setup() {
    appCtx = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun `test group by Place name with count and count alias`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = listOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      query {
        val cAlias = Person.id.count().alias("c")
        val list = (Place innerJoin Person)
          .select(Place.name, Person.id.count(), cAlias)
          .all()
          .groupBy(Place.name)
          .sequence { Triple(it[Place.name], it[Person.id.count()], it[cAlias]) }
          .toList()

        expect(list).toHaveSize(2)
        expect(list).toBe(
          listOf(
            Triple("Cleveland", 1, 1),
            Triple("South Point", 2, 2)
          )
        )
      }
    }
  }

  @Test
  fun `test groupBy having`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = listOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      query {
        val list = (Place innerJoin Person)
          .select(Place.name, Person.id.count())
          .all()
          .groupBy(Place.name)
          .having { Person.id.count() eq 1 }
          .sequence { Pair(it[Place.name], it[Person.id.count()]) }
          .toList()

        expect(list).toHaveSize(1)
        expect(list).toBe(listOf(Pair("Cleveland", 1)))
      }
    }
  }

  @Test
  fun `test groupBy having and orderBy`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = listOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      query {
        val maxExp = Place.id.max()
        val list = (Place innerJoin Person)
          .select(Place.name, Person.id.count(), maxExp)
          .all()
          .groupBy(Place.name)
          .having { Person.id.count() eq maxExp }
          .orderBy(Place.name)
          .sequence { Triple(it[Place.name], it[Person.id.count()], it[maxExp]) }
          .toList()

        expect(list).toHaveSize(2)
        expect(list).toBe(
          listOf(
            Triple("Cleveland", 1, 1),
            Triple("South Point", 2, 2)
          )
        )
      }
    }
  }

  @Test
  fun `test groupConcat`() = coroutineRule.runBlockingTest {
    withPlaceTestDatabase(
      context = appCtx,
      tables = listOf(Place, Person, Review),
      testDispatcher = coroutineRule.testDispatcher
    ) {
      query {
        fun <T : String?> GroupConcat<T>.check(assertBlock: (Map<String, String?>) -> Unit) {
          val result = mutableMapOf<String, String?>()
          (Place leftJoin Person)
            .select(Place.name, this)
            .all()
            .groupBy(Place.id, Place.name)
            .forEach { result[it[Place.name]] = it.getOptional(this) }
          assertBlock(result)
        }

        Person.name.groupConcat().check { map ->
          expect(map).toHaveSize(3)
        }

        Person.name.groupConcat(separator = " | ").check { map ->
          expect(map).toHaveSize(3)
          expect(map).toContain(Pair("Cleveland", "Louis"))
          expect(map).toContain(Pair("South Point", "Mike | Rick"))
          expect(map).toContain(Pair("Cincinnati", null))
        }
      }
    }
  }
}
