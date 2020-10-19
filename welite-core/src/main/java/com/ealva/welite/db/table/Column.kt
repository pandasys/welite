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

package com.ealva.welite.db.table

import com.ealva.welite.db.expr.BaseSqlTypeExpression
import com.ealva.welite.db.expr.BindExpression
import com.ealva.welite.db.expr.Count
import com.ealva.welite.db.expr.Expression
import com.ealva.welite.db.expr.Op
import com.ealva.welite.db.expr.SqlTypeExpression
import com.ealva.welite.db.expr.literal
import com.ealva.welite.db.type.Identity
import com.ealva.welite.db.type.PersistentType
import com.ealva.welite.db.type.SqlBuilder
import com.ealva.welite.db.type.buildStr
import java.util.Comparator

/**
 * Represents a column in a table with the type [T]. [T] is the Kotlin type and not necessarily
 * what is stored in the database. To be stored in the DB some types may be converted to
 * a String, stored in a Blob, stored as a wider numerical type, etc.
 */
public interface Column<T> : SqlTypeExpression<T>, Comparable<Column<*>> {
  public val name: String

  public val table: Table

  public val tableIdentity: Identity

  public val isAutoInc: Boolean

  public var dbDefaultValue: Expression<T>?

  public val refersTo: Column<*>?

//  fun <S : T> refersTo(): Column<S>? {
//    @Suppress("UNCHECKED_CAST")
//    return refersTo as? Column<S>
//  }

  public var indexInPK: Int?

  public var foreignKey: ForeignKeyConstraint?

  public fun identity(): Identity

  /**
   * Create an alias for this column with [tableAlias] as the table name. If [tableAlias] is
   * null the table name will be this column's table name with an underscore and this
   * column's [name] appended, eg. pseudocode:
   * ```tableAlias="${column.table.name}_${column.name}"```
   */
  public fun makeAlias(tableAlias: String? = null): Column<T>

  /**
   * True if null may be assigned to this column because either it's set nullable or
   * represents the row id (INTEGER PRIMARY KEY) without DESC
   */
  public val nullable: Boolean

  /**
   * True if the column constraints for this column include primary key
   */
  public val definesPrimaryKey: Boolean

  public fun isOneColumnPK(): Boolean

  /**
   * Return the sql for this column as will be found in the CREATE TABLE statement
   */
  public fun descriptionDdl(): String

  /**
   * Mark this column as part of the primary key
   */
  public fun markPrimaryKey()

  /**
   * Create a Bindable argument for this column to represent that the actual value will be bound
   * into the statement/query when executed.
   */
  public fun bindArg(): BindExpression<T> = BindExpression(persistentType)

  public companion object {
    /**
     * Create a column implementation using syntax similar to a constructor
     */
    public operator fun <T> invoke(
      table: Table,
      name: String,
      persistentType: PersistentType<T>,
      initialConstraints: List<ColumnConstraint> = emptyList(),
      addTo: (Column<*>) -> Unit = {},
      block: ColumnConstraints<T>.() -> Unit = {}
    ): Column<T> = ColumnImpl(table, name, persistentType, initialConstraints).apply {
      addTo(this)
      block()
    }
  }
}

private class ColumnImpl<T>(
  override val table: Table,
  override val name: String,
  override val persistentType: PersistentType<T>,
  initialConstraints: List<ColumnConstraint>
) : BaseSqlTypeExpression<T>(), Column<T>, ColumnConstraints<T> {

  private val constraintList = ConstraintCollection(persistentType).apply {
    initialConstraints.forEach { add(it) }
  }

  override val tableIdentity: Identity
    get() = table.identity

  override val definesPrimaryKey: Boolean
    get() = constraintList.hasPrimaryKey()

  override val isAutoInc: Boolean
    get() = constraintList.hasAutoInc()

  private fun addConstraint(constraint: ColumnConstraint) = constraintList.add(constraint)

  override var foreignKey: ForeignKeyConstraint? = null

  override val refersTo: Column<*>?
    get() = foreignKey?.child

  override fun identity(): Identity = Identity.make(name)

  override var indexInPK: Int? = null

  override var dbDefaultValue: Expression<T>? = null

  @ExperimentalUnsignedTypes
  override fun default(defaultValue: T) = apply { dbDefaultValue = literal(defaultValue) }

  override fun defaultExpression(defaultValue: Expression<T>) = apply {
    dbDefaultValue = defaultValue
  }

  override fun appendTo(sqlBuilder: SqlBuilder): SqlBuilder = sqlBuilder.apply {
    append(table.identity.value)
    append('.')
    append(identity().value)
  }

  override fun isOneColumnPK(): Boolean = table.primaryKey?.columns?.singleOrNull() == this

  /** Returns the SQL representation of this column. */
  override fun descriptionDdl(): String = buildStr {
    append(identity())
    append(' ')
    append(persistentType.sqlType)
    constraintList.forEach { constraint ->
      append(' ')
      append(constraint.toString())
    }
    dbDefaultValue?.let { defaultValue ->
      append(" DEFAULT ")
      append(defaultValue.asDefaultValue())
    }
  }

  override fun compareTo(other: Column<*>): Int = columnComparator.compare(this, other)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ColumnImpl<*>) return false
    if (!super.equals(other)) return false

    if (table != other.table) return false
    if (name != other.name) return false
    return (persistentType == other.persistentType)
  }

  override fun hashCode(): Int = table.hashCode() * 31 + name.hashCode()

  override fun toString(): String = buildStr {
    append(tableIdentity.unquoted)
    append('.')
    append(name)
  }

  override fun asDefaultValue(): String = buildStr {
    append("(")
    append(tableIdentity.unquoted)
    append('.')
    append(name)
    append(")")
  }

  override val nullable: Boolean
    get() = persistentType.nullable || constraintList.isRowId(persistentType.isIntegerType)

  override fun primaryKey() = apply { addConstraint(PrimaryKeyConstraint) }

  override fun unique() = apply { addConstraint(UniqueConstraint) }

  override fun asc() = apply { addConstraint(AscConstraint) }

  override fun desc() = apply { addConstraint(DescConstraint) }

  override fun autoIncrement() = apply {
    if (!constraintList.hasPrimaryKey()) {
      primaryKey()
    }
    addConstraint(AutoIncrementConstraint)
  }

  override fun onConflict(onConflict: OnConflict) = apply {
    addConstraint(ConflictConstraint(onConflict))
  }

  override fun collateBinary() = collate(CollateBinary)
  override fun collateNoCase() = collate(CollateNoCase)
  override fun collateRTrim() = collate(CollateRTrim)
  private fun collate(collate: Collate) = apply { addConstraint(CollateConstraint(collate)) }
  override fun collate(name: String) = apply { addConstraint(CollateConstraint(CollateUser(name))) }

  @ExperimentalUnsignedTypes
  override fun check(name: String, op: (Column<T>) -> Op<Boolean>) = apply {
    table.check(name) { op(this@ColumnImpl) }
  }

  override fun <S : T> references(
    ref: Column<S>,
    onDelete: ForeignKeyAction,
    onUpdate: ForeignKeyAction,
    fkName: String?
  ) = apply {
    foreignKey = ForeignKeyConstraint(
      parentTable = tableIdentity,
      parent = this,
      childTable = ref.tableIdentity,
      child = ref,
      onUpdate = onUpdate,
      onDelete = onDelete,
      name = fkName
    )
  }

  override fun index(customIndexName: String?) = apply {
    customIndexName?.let { table.index(customIndexName, this) } ?: table.index(this)
  }

  override fun uniqueIndex(customIndexName: String?) = apply {
    customIndexName?.let { table.uniqueIndex(customIndexName, this) } ?: table.uniqueIndex(this)
  }

  override fun makeAlias(tableAlias: String?): Column<T> =
    Column(Alias(table, tableAlias ?: "${table.tableName}_$name"), name, persistentType)

  override fun markPrimaryKey() {
    val columnDefiningPrimaryKey = table.columnDefiningPrimaryKey
    check(columnDefiningPrimaryKey == null) {
      "Primary key already defined for column ${columnDefiningPrimaryKey?.name}"
    }
    check(indexInPK == null) { "$this already part of primary key" }
    indexInPK = table.columns.count { it.indexInPK != null } + 1
  }
}

private val columnComparator: Comparator<Column<*>> =
  compareBy({ column -> column.tableIdentity.value }, { column -> column.name })

@Suppress("unused")
public fun Column<*>.countDistinct(): Count = Count(this, true)