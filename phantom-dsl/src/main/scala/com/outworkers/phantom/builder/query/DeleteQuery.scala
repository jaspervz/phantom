/*
 * Copyright 2013 - 2019 Outworkers Ltd.
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
package com.outworkers.phantom.builder.query

import com.datastax.driver.core.{ConsistencyLevel, Session}
import com.outworkers.phantom.CassandraTable
import com.outworkers.phantom.builder._
import com.outworkers.phantom.builder.clauses._
import com.outworkers.phantom.builder.ops.{MapKeyUpdateClause, TokenizerKey}
import com.outworkers.phantom.builder.query.engine.CQLQuery
import com.outworkers.phantom.builder.query.execution._
import com.outworkers.phantom.builder.query.prepared.{PreparedBlock, PreparedFlattener}
import com.outworkers.phantom.column.AbstractColumn
import com.outworkers.phantom.connectors.KeySpace
import org.joda.time.DateTime
import shapeless.ops.hlist.{Prepend, Reverse}
import shapeless.{=:!=, HList, HNil}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

case class DeleteQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Limit <: LimitBound,
  Order <: OrderBound,
  Status <: ConsistencyBound,
  Chain <: WhereBound,
  PS <: HList
](table: Table,
  init: CQLQuery,
  tokens: List[TokenizerKey] = Nil,
  wherePart : WherePart = WherePart.empty,
  casPart : CompareAndSetPart = CompareAndSetPart.empty,
  usingPart: UsingPart = UsingPart.empty,
  options: QueryOptions = QueryOptions.empty
) extends RootQuery[Table, Record, Status] with Batchable {

  def prepare[Rev <: HList]()(
    implicit session: Session,
    keySpace: KeySpace,
    ev: PS =:!= HNil,
    rev: Reverse.Aux[PS, Rev]
  ): PreparedBlock[Rev] = {
    val flatten = new PreparedFlattener(qb)
    new PreparedBlock(flatten.query, flatten.protocolVersion, options)
  }

  def prepareAsync[P[_], F[_], Rev <: HList]()(
    implicit session: Session,
    executor: ExecutionContextExecutor,
    keySpace: KeySpace,
    ev: PS =:!= HNil,
    rev: Reverse.Aux[PS, Rev],
    monad: FutureMonad[F],
    interface: PromiseInterface[P, F]
  ): F[PreparedBlock[Rev]] = {
    val flatten = new PreparedFlattener(qb)

    flatten.async() map { ps =>
      new PreparedBlock(ps, flatten.protocolVersion, options)
    }
  }

  def timestamp(time: Duration): DeleteQuery[Table, Record, Limit, Order, Status, Chainned, PS] = {
    copy(usingPart = usingPart append QueryBuilder.timestamp(time))
  }

  def timestamp(time: DateTime): DeleteQuery[Table, Record, Limit, Order, Status, Chainned, PS] = {
    timestamp((time.getMillis * 1000).micros)
  }

  /**
    * The where method of a select query.
    *
    * @param condition A where clause condition restricted by path dependant types.
    * @param ev        An evidence request guaranteeing the user cannot chain multiple where clauses on the same query.
    * @return
    */
  def where[
    RR,
    HL <: HList,
    Token <: HList,
    Out <: HList,
    OutTk <: HList
  ](condition: (Table) => QueryCondition[HL])(
    implicit ev: =:=[Chain, Unchainned],
    prepend: Prepend.Aux[HL, PS, Out]
  ): DeleteQuery[Table, Record, Limit, Order, Status, Chainned, Out] = {
    copy(
      wherePart = wherePart append QueryBuilder.Update.where(condition(table).qb),
      tokens = tokens ::: condition(table).tokens
    )
  }

  /**
    * The where method of a select query.
    *
    * @param condition A where clause condition restricted by path dependant types.
    * @param ev An evidence request guaranteeing the user cannot chain multiple where clauses on the same query.
    * @return
    */
  def and[
    RR,
    HL <: HList,
    Out <: HList
  ](condition: (Table) => QueryCondition[HL])(
    implicit ev: Chain =:= Chainned,
    prepend: Prepend.Aux[HL, PS, Out]
  ): DeleteQuery[Table, Record, Limit, Order, Status, Chainned, Out] = {
    copy(
      wherePart = wherePart append QueryBuilder.Update.and(condition(table).qb),
      tokens = tokens ::: condition(table).tokens
    )
  }

  def withOptions(opts: QueryOptions => QueryOptions): DeleteQuery[Table, Record, Limit, Order, Status, Chain, PS] = {
    copy(options = opts(this.options))
  }

  def consistencyLevel_=(level: ConsistencyLevel)(
    implicit ev: Status =:= Unspecified, session: Session
  ): DeleteQuery[Table, Record, Limit, Order, Specified, Chain, PS] = {
    if (session.protocolConsistency) {
      copy(options = options.consistencyLevel_=(level))
    } else {
      copy(usingPart = usingPart append QueryBuilder.consistencyLevel(level.toString))
    }
  }

  def ifExists: DeleteQuery[Table, Record, Limit, Order, Status, Chain, PS] = {
    copy(casPart = casPart append QueryBuilder.Update.ifExists)
  }

  /**
   * Generates a conditional query clause based on CQL lightweight transactions.
   * Compare and set transactions only get executed if a particular condition is true.
   *
   * @param clause The Compare-And-Set clause to append to the builder.
   * @return A conditional query, now bound by a compare-and-set part.
   */
  def onlyIf(
    clause: Table => CompareAndSetClause.Condition
  ): ConditionalDeleteQuery[Table, Record, Limit, Order, Status, Chain, PS] = {
    ConditionalDeleteQuery(
      table = table,
      init = init,
      tokens,
      wherePart = wherePart,
      casPart = casPart append QueryBuilder.Update.onlyIf(clause(table).qb),
      usingPart = usingPart,
      options = options
    )
  }

  val qb: CQLQuery = (usingPart merge wherePart merge casPart) build init

  override def executableQuery: ExecutableCqlQuery = ExecutableCqlQuery(qb, options, tokens)
}


trait DeleteImplicits {
  implicit def columnUpdateClauseToDeleteCondition(clause: MapKeyUpdateClause[_, _]): DeleteClause.Default = {
    new DeleteClause.Condition(QueryBuilder.Collections.mapColumnType(clause.column, clause.keyName))
  }

  implicit def columnClauseToDeleteCondition(col: AbstractColumn[_]): DeleteClause.Default = {
    new DeleteClause.Condition(CQLQuery(col.name))
  }
}

object DeleteQuery {

  type Default[T <: CassandraTable[T, _], R] = DeleteQuery[T, R, Unlimited, Unordered, Unspecified, Unchainned, HNil]
  type Prepared[T <: CassandraTable[T, _], R, PS <: HList] = DeleteQuery[T, R, Unlimited, Unordered, Unspecified, Unchainned, PS]

  def apply[T <: CassandraTable[T, _], R](table: T)(implicit keySpace: KeySpace): DeleteQuery.Default[T, R] = {
    new DeleteQuery(table, QueryBuilder.Delete.delete(QueryBuilder.keyspace(keySpace.name, table.tableName).queryString))
  }

  def apply[T <: CassandraTable[T, _], R](table: T, conds: CQLQuery*)(implicit keySpace: KeySpace): DeleteQuery.Default[T, R] = {
    new DeleteQuery(
      table,
      QueryBuilder.Delete.delete(
        QueryBuilder.keyspace(keySpace.name, table.tableName).queryString,
        conds
      )
    )
  }


  def prepared[
    T <: CassandraTable[T, _],
    R,
    PS <: HList
  ](table: T, conds: CQLQuery)(implicit keySpace: KeySpace): DeleteQuery.Prepared[T, R, PS] = {
    new DeleteQuery(
      table,
      QueryBuilder.Delete.delete(
        QueryBuilder.keyspace(keySpace.name, table.tableName).queryString,
        conds
      )
    )
  }
}

sealed case class ConditionalDeleteQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Limit <: LimitBound,
  Order <: OrderBound,
  Status <: ConsistencyBound,
  Chain <: WhereBound,
  PS <: HList
](table: Table,
  init: CQLQuery,
  tokens: List[TokenizerKey],
  wherePart : WherePart = WherePart.empty,
  casPart : CompareAndSetPart = CompareAndSetPart.empty,
  usingPart: UsingPart = UsingPart.empty,
  options: QueryOptions
 ) extends RootQuery[Table, Record, Status] with Batchable {

  val qb: CQLQuery = (usingPart merge wherePart merge casPart) build init

  final def and(
    clause: Table => CompareAndSetClause.Condition
  ): ConditionalDeleteQuery[Table, Record, Limit, Order, Status, Chain, PS] = {
    copy(
      casPart = casPart append QueryBuilder.Update.and(clause(table).qb)
    )
  }

  override def executableQuery: ExecutableCqlQuery = ExecutableCqlQuery(qb, options, tokens)
}
