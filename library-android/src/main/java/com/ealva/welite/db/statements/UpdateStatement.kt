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

package com.ealva.welite.db.statements

import android.database.sqlite.SQLiteDatabase
import com.ealva.welite.db.expr.Op
import com.ealva.welite.db.expr.appendTo
import com.ealva.welite.db.table.ArgBindings
import com.ealva.welite.db.table.OnConflict
import com.ealva.welite.db.table.Table
import com.ealva.welite.db.type.PersistentType
import com.ealva.welite.db.type.buildSql

fun <T : Table> T.update(
  onConflict: OnConflict = OnConflict.Unspecified,
  assignColumns: T.(ColumnValues) -> Unit
): UpdateBuilder<T> {
  return UpdateBuilder(this, onConflict, assignColumns)
}

fun <T : Table> T.updateAll(
  onConflict: OnConflict = OnConflict.Unspecified,
  assignColumns: T.(ColumnValues) -> Unit
): UpdateStatement {
  return UpdateStatement(this, onConflict, assignColumns)
}

class UpdateBuilder<T : Table>(
  private val table: T,
  private val onConflict: OnConflict,
  private val assignColumns: T.(ColumnValues) -> Unit
) {
  fun where(where: T.() -> Op<Boolean>): UpdateStatement {
    return UpdateStatement(table, onConflict, table.where(), assignColumns)
  }
}

interface UpdateStatement : BaseStatement {

  companion object {
    operator fun <T : Table> invoke(
      table: T,
      onConflict: OnConflict,
      assignColumns: T.(ColumnValues) -> Unit
    ): UpdateStatement {
      return UpdateStatementImpl(buildStatement(onConflict, table, null, assignColumns))
    }

    operator fun <T : Table> invoke(
      table: T,
      onConflict: OnConflict = OnConflict.Unspecified,
      where: Op<Boolean>,
      assignColumns: T.(ColumnValues) -> Unit
    ): UpdateStatement {
      return UpdateStatementImpl(buildStatement(onConflict, table, where, assignColumns))
    }

    private fun <T : Table> buildStatement(
      onConflict: OnConflict,
      table: T,
      where: Op<Boolean>?,
      assignColumns: T.(ColumnValues) -> Unit
    ): Pair<String, List<PersistentType<*>>> {
      return buildSql {
        append(onConflict.updateOr)
        append(table.identity.value)

        val columnValues = ColumnValues()
        table.assignColumns(columnValues)

        columnValues.columnValueList.appendTo(this, prefix = " SET ") { columnValue ->
          append(columnValue)
        }

        where?.let { where ->
          append(" WHERE ")
          append(where)
        }
      }
    }
  }
}

private class UpdateStatementImpl(
  sqlAndTypes: Pair<String, List<PersistentType<*>>>
) : UpdateStatement {
  private val sql: String = sqlAndTypes.first
  private val types: List<PersistentType<*>> = sqlAndTypes.second

  private var statementAndTypes: StatementAndTypes? = null

  override fun execute(db: SQLiteDatabase, bindArgs: (ArgBindings) -> Unit): Long =
    (statementAndTypes ?: StatementAndTypes(db.compileStatement(sql), types))
      .executeUpdate(bindArgs)
}
