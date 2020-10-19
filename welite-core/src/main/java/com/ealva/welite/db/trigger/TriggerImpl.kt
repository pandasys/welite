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

package com.ealva.welite.db.trigger

import com.ealva.welite.db.expr.Expression
import com.ealva.welite.db.expr.Op
import com.ealva.welite.db.statements.ColumnValues
import com.ealva.welite.db.statements.DeleteStatement
import com.ealva.welite.db.statements.InsertStatement
import com.ealva.welite.db.statements.UpdateStatement
import com.ealva.welite.db.table.Column
import com.ealva.welite.db.table.Creatable
import com.ealva.welite.db.table.MasterType
import com.ealva.welite.db.table.OnConflict
import com.ealva.welite.db.table.QueryBuilder
import com.ealva.welite.db.table.SelectFrom
import com.ealva.welite.db.table.SqlExecutor
import com.ealva.welite.db.table.Table
import com.ealva.welite.db.table.WeLiteMarker
import com.ealva.welite.db.type.Identity
import com.ealva.welite.db.type.SqlBuilder
import com.ealva.welite.db.type.StatementSeed
import com.ealva.welite.db.type.append
import com.ealva.welite.db.type.asIdentity
import com.ealva.welite.db.type.buildStr

private const val CREATE_TRIGGER = "CREATE TRIGGER IF NOT EXISTS "
private const val CREATE_TEMP_TRIGGER = "CREATE TEMP TRIGGER IF NOT EXISTS "

public interface Trigger<T : Table> : Creatable {
  public enum class BeforeAfter(public val value: String) {
    BEFORE(" BEFORE"),
    AFTER(" AFTER")
  }

  public enum class Event(public val value: String) {
    INSERT(" INSERT"),
    UPDATE(" UPDATE"),
    DELETE(" DELETE")
  }
}

private class TriggerImpl<T : Table>(
  name: String,
  private val temp: Boolean,
  private val beforeAfter: Trigger.BeforeAfter,
  private val event: Trigger.Event,
  private val updateCols: List<Column<*>>,
  private val table: T,
  private val whenCondition: Expression<Boolean>?,
  private val statements: List<StatementSeed>
) : Trigger<T> {

  init {
    require(statements.isNotEmpty()) { "A trigger is required to have at least 1 statement" }
  }

  override val masterType: MasterType = MasterType.Trigger
  override val identity = name.asIdentity()

  override fun create(executor: SqlExecutor, temporary: Boolean) {
    executor.exec(makeCreateStatement())
  }

  private fun makeCreateStatement(): String = buildStr {
    append(if (temp) CREATE_TEMP_TRIGGER else CREATE_TRIGGER)
    append(identity.value)
    append(beforeAfter.value)
    append(event.value)
    if (event == Trigger.Event.UPDATE && updateCols.isNotEmpty()) {
      append(" OF ")
      updateCols.forEachIndexed { index, column ->
        append(column.identity())
        if (index < updateCols.size - 1) {
          append(", ")
        }
      }
    }
    append(" ON ")
    append(table.identity)

    whenCondition?.let {
      append(" WHEN ")
      append(whenCondition)
    }

    append(" BEGIN ")
    statements.forEach { statement ->
      check(statement.types.isEmpty()) {
        "Trigger does not support statements with bindable arguments. SQL=${statement.sql}"
      }
      append(statement)
      append("; ")
    }
    append("END;")
  }

  override fun drop(executor: SqlExecutor) {
    executor.exec(makeDropStatement())
  }

  private fun makeDropStatement(): String = buildStr {
    append("DROP TRIGGER IF EXISTS ")
    append(identity.value)
  }
}

public enum class NewOrOld(public val value: String) {
  NEW("NEW."),
  OLD("OLD.");

  override fun toString(): String = value
}

private class NewOrOldColumn<T>(original: Column<T>, newOrOld: NewOrOld) : Column<T> by original {
  override val name: String = "$newOrOld${original.name}"
  override fun identity(): Identity = name.asIdentity()

  override fun appendTo(sqlBuilder: SqlBuilder): SqlBuilder = sqlBuilder.apply {
    append(name)
  }
}

public interface TriggerUpdate<T : Table> {
  public fun addWhere(where: T.() -> Op<Boolean>)
}

@WeLiteMarker
public interface TriggerStatements {
  public val statements: List<StatementSeed>

  public fun <T : Table> T.insert(
    onConflict: OnConflict = OnConflict.Unspecified,
    assignColumns: T.(ColumnValues) -> Unit
  )

  public fun <T : Table> T.delete(where: () -> Op<Boolean>)

  public fun <T : Table> T.update(
    onConflict: OnConflict = OnConflict.Unspecified,
    assignColumns: T.(ColumnValues) -> Unit
  ): TriggerUpdate<T>

  public fun <T : Table> TriggerUpdate<T>.where(where: T.() -> Op<Boolean>)

  public fun select(vararg columns: Expression<*>): SelectFrom =
    SelectFrom(columns.distinct(), null)

  public fun SelectFrom.where(where: Op<Boolean>?)

  /**
   * Refer to a column as "NEW.columnName" in a trigger
   */
  public fun <T> new(column: Column<T>): Column<T>

  /**
   * Refer to a column as "OLD.columnName" in a trigger
   */
  public fun <T> old(column: Column<T>): Column<T>

  public companion object {
    /**
     * Create a [TriggerStatements], from [table] specifying the [event] causing the execution of
     * the Trigger, which will contain the statements to execute when the [Trigger] is fired.
     */
    public operator fun invoke(table: Table, event: Trigger.Event): TriggerStatements {
      return TriggerStatementsImpl(table, event)
    }
  }
}

private class TriggerStatementsImpl(
  private val table: Table,
  private val event: Trigger.Event
) : TriggerStatements {
  override val statements: MutableList<StatementSeed> = mutableListOf()

  override fun <T> new(column: Column<T>): Column<T> {
    check(event === Trigger.Event.INSERT || event === Trigger.Event.UPDATE) {
      "NEW reference not valid for a${Trigger.Event.DELETE} trigger"
    }
    check(column.table == table) { "NEW.column must refer to a ${table.tableName} column" }
    return NewOrOldColumn(column, NewOrOld.NEW)
  }

  override fun <T> old(column: Column<T>): Column<T> {
    check(event === Trigger.Event.DELETE || event === Trigger.Event.UPDATE) {
      "OLD reference not valid for an${Trigger.Event.INSERT} trigger"
    }
    check(column.table == table) { "OLD.column must refer to a ${table.tableName} column" }
    return NewOrOldColumn(column, NewOrOld.OLD)
  }

  override fun <T : Table> T.insert(
    onConflict: OnConflict,
    assignColumns: T.(ColumnValues) -> Unit
  ) {
    statements += InsertStatement.statementSeed(this, onConflict, assignColumns)
  }

  override fun <T : Table> T.delete(where: () -> Op<Boolean>) {
    statements += DeleteStatement.statementSeed(this, where())
  }

  override fun <T : Table> T.update(
    onConflict: OnConflict,
    assignColumns: T.(ColumnValues) -> Unit
  ): TriggerUpdate<T> {
    return object : TriggerUpdate<T> {
      override fun addWhere(where: T.() -> Op<Boolean>) {
        statements += UpdateStatement.statementSeed(
          this@update,
          onConflict,
          this@update.where(),
          assignColumns
        )
      }
    }
  }

  override fun <T : Table> TriggerUpdate<T>.where(where: T.() -> Op<Boolean>) {
    addWhere(where)
  }

  override fun SelectFrom.where(where: Op<Boolean>?) {
    statements += QueryBuilder(this, where).statementSeed()
  }
}

/**
 * Make a [Trigger] with [name] for table [T] to be executed [beforeAfter] the given [event] and
 * call [addStatements] to add all the statements to be executed when the trigger fires.
 */
public fun <T : Table> T.trigger(
  name: String,
  beforeAfter: Trigger.BeforeAfter,
  event: Trigger.Event,
  temporary: Boolean = false,
  cond: Expression<Boolean>? = null,
  addStatements: TriggerStatements.() -> Unit
): Trigger<T> {
  return TriggerImpl(
    name,
    temporary,
    beforeAfter,
    event,
    emptyList(),
    this,
    cond,
    TriggerStatements(this, event).apply(addStatements).statements
  )
}