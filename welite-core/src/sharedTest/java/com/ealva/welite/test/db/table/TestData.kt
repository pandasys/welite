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

package com.ealva.welite.test.db.table

import android.content.Context
import com.ealva.welite.db.Database
import com.ealva.welite.db.OpenParams
import com.ealva.welite.db.table.Column
import com.ealva.welite.db.table.Table
import kotlinx.coroutines.CoroutineDispatcher

public object Place : Table() {
  public val id: Column<Long> = long("place_id") { autoIncrement() }
  public val name: Column<String> = text("name")
}

public object Person : Table() {
  public val id: Column<String> = text("id")
  public val name: Column<String> = text("name")
  public val cityId: Column<Long?> = optReference("place_id", Place.id)
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

public object Review : Table() {
  public val userId: Column<String> = reference("person_id", Person.id)
  public val post: Column<String> = text("post")
  public val value: Column<Int> = integer("value")
}

public suspend fun withPlaceTestDatabase(
  context: Context,
  tables: Set<Table>,
  testDispatcher: CoroutineDispatcher,
  enableForeignKeyConstraints: Boolean = true,
  block: suspend Database.() -> Unit
) {
  val db = TestDatabase(context, tables, testDispatcher, enableForeignKeyConstraints)
  if (tables.contains(Place)) {
    db.transaction {
      val clevelandId = Place.insert {
        it[name] = "Cleveland"
      }

      val southPointId = Place.insert {
        it[name] = "South Point"
      }

      Place.insert {
        it[name] = "Cincinnati"
      }

      Person.insert {
        it[id] = "louis"
        it[name] = "Louis"
        it[cityId] = clevelandId
      }

      Person.insert {
        it[id] = "rick"
        it[name] = "Rick"
        it[cityId] = southPointId
      }

      Person.insert {
        it[id] = "mike"
        it[name] = "Mike"
        it[cityId] = southPointId
      }

      Person.insert {
        it[id] = "amber"
        it[name] = "Amber"
        it[cityId] = null
      }

      Person.insert {
        it[id] = "nathalia"
        it[name] = "Nathalia"
        it[cityId] = null
      }

      Review.insert {
        it[userId] = "nathalia"
        it[post] = "Nathalia is here"
        it[value] = 2
      }

      Review.insert {
        it[userId] = "nathalia"
        it[post] = "Where's McDreamy"
        it[value] = 3
      }

      Review.insert {
        it[userId] = "mike"
        it[post] = "Mike's post"
        it[value] = 4
      }

      Review.insert {
        it[userId] = "rick"
        it[post] = "Sup Dude"
        it[value] = 5
      }

      setSuccessful()
    }
  }
  try {
    db.block()
  } finally {
    db.close()
  }
}

/**
 * Make in-memory database
 */
@Suppress("TestFunctionName")
public fun TestDatabase(
  context: Context,
  tables: Set<Table>,
  testDispatcher: CoroutineDispatcher,
  enableForeignKeyConstraints: Boolean
): Database {
  return Database(
    context = context,
    version = 1,
    tables = tables,
    migrations = emptyList(),
    openParams = OpenParams(
      dispatcher = testDispatcher,
      allowWorkOnUiThread = true,
      enableForeignKeyConstraints = enableForeignKeyConstraints
    )
  )
}
