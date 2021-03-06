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

import android.os.Build.VERSION_CODES.LOLLIPOP
import com.ealva.welite.db.table.Column
import com.ealva.welite.db.table.Table
import com.ealva.welite.db.table.TableDependencies
import com.nhaarman.expect.expect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [LOLLIPOP])
class TableDependenciesTests {
  @Test
  fun `test cycle detection`() {
    val deps = TableDependencies(setOf(TableA, TableB, TableC))
    expect(
      deps.tablesAreCyclic()
    ).toBe(true)
  }
}

object TableA : Table() {
  val id = long("_id") { primaryKey() }
  val aName = text("aName") { collateNoCase() }
  val tableCId = reference("tablec_id", TableC.id)
}

object TableB : Table() {
  val id = long("_id") { primaryKey() }
  val bName = text("bName") { collateNoCase() }
  val tableCId: Column<Long> = reference("tablea_id", TableA.id)
}

object TableC : Table() {
  val id = long("_id") { primaryKey() }
  val cName = text("cName") { collateNoCase() }
  val tableCId = reference("tableb_id", TableB.id)
}
