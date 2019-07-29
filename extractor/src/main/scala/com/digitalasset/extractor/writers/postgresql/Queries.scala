// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.extractor.writers.postgresql

import java.time.Instant

import com.digitalasset.daml.lf.value.{Value => V}
import com.digitalasset.extractor.json.JsonConverters._
import com.digitalasset.extractor.ledger.types._
import doobie._
import doobie.implicits._
import scalaz._

object Queries {

  implicit val timeStampWrite: Write[V.ValueTimestamp] =
    Write[Instant].contramap[V.ValueTimestamp](_.value.toInstant)

  val dropTransactionsTable: Fragment = dropTableIfExists("transaction")

  val createTransactionsTable: Fragment = sql"""
        CREATE TABLE
          transaction
          (transaction_id TEXT PRIMARY KEY NOT NULL
          ,seq BIGSERIAL UNIQUE NOT NULL
          ,workflow_id TEXT
          ,effective_at TIMESTAMP NOT NULL
          ,extracted_at TIMESTAMP DEFAULT NOW()
          ,ledger_offset TEXT NOT NULL
          )
      """

  val createStateTable: Fragment = sql"""
        CREATE TABLE IF NOT EXISTS
          state
          (key TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL
          )
      """

  val checkStateTableExists: Fragment = isTableExists("state")

  def getState(key: String): Fragment = {
    sql"""
      SELECT value FROM state WHERE key = ${key} LIMIT 1
    """
  }

  def setState(key: String, value: String): Fragment = {
    sql"""
      INSERT INTO
        state (key, value)
      VALUES
        (${key}, ${value})
      ON CONFLICT (key) DO UPDATE
        SET value = excluded.value
    """
  }

  def deleteState(key: String): Fragment = {
    sql"""
        DELETE FROM state WHERE key = ${key} LIMIT 1
    """
  }

  val transactionsIndex: Fragment = createIndex("transaction", NonEmptyList("workflow_id"))

  def insertTransaction(t: TransactionTree): Fragment = {
    sql"""
       INSERT INTO
         transaction
         (transaction_id, workflow_id, effective_at, ledger_offset)
         VALUES (${t.transactionId}, ${t.workflowId}, ${t.effectiveAt}, ${t.offset})
    """
  }

  def lastOffset: Fragment = {
    sql"""
       SELECT ledger_offset FROM transaction ORDER BY seq DESC LIMIT 1
    """
  }

  def dropTableIfExists(table: String): Fragment = Fragment.const(s"DROP TABLE IF EXISTS ${table}")

  def isTableExists(table: String): Fragment =
    sql"""SELECT EXISTS (
      SELECT 1
      FROM   pg_tables
      WHERE  tablename = ${table}
    );"""

  def createIndex(table: String, columns: NonEmptyList[String]): Fragment =
    Fragment.const(s"CREATE INDEX ON ${table} (${columns.stream.mkString(", ")})")

  val createExerciseTable: Fragment = sql"""
        CREATE TABLE
          exercise
          (event_id TEXT PRIMARY KEY NOT NULL
          ,transaction_id TEXT NOT NULL
          ,is_root_event BOOLEAN NOT NULL
          ,contract_id TEXT NOT NULL
          ,package_id TEXT NOT NULL
          ,template TEXT NOT NULL
          ,contract_creating_event_id TEXT NOT NULL
          ,choice TEXT NOT NULL
          ,choice_argument JSONB NOT NULL
          ,acting_parties JSONB NOT NULL
          ,consuming BOOLEAN NOT NULL
          ,witness_parties JSONB NOT NULL
          ,child_event_ids JSONB NOT NULL
          )
      """

  def insertExercise(event: ExercisedEvent, transactionId: String, isRoot: Boolean): Fragment = {
    sql"""
        INSERT INTO exercise
        VALUES (
          ${event.eventId},
          ${transactionId},
          ${isRoot},
          ${event.contractId},
          ${event.templateId.packageId},
          ${event.templateId.name},
          ${event.contractCreatingEventId},
          ${event.choice},
          ${toJsonString(event.choiceArgument)}::jsonb,
          ${toJsonString(event.actingParties)}::jsonb,
          ${event.consuming},
          ${toJsonString(event.witnessParties)}::jFsonb,
          ${toJsonString(event.childEventIds)}::jsonb
        )
      """
  }

  object SingleTable {
    val dropContractsTable: Fragment = dropTableIfExists("contract")

    val createContractsTable: Fragment = sql"""
      CREATE TABLE
        contract
        (event_id TEXT PRIMARY KEY NOT NULL
        ,archived_by_event_id TEXT DEFAULT NULL
        ,contract_id TEXT NOT NULL
        ,transaction_id TEXT NOT NULL
        ,archived_by_transaction_id TEXT DEFAULT NULL
        ,is_root_event BOOLEAN NOT NULL
        ,package_id TEXT NOT NULL
        ,template TEXT NOT NULL
        ,create_arguments JSONB NOT NULL
        ,witness_parties JSONB NOT NULL
        )
    """

    def setContractArchived(
        eventId: String,
        transactionId: String,
        archivedByEventId: String): Fragment =
      sql"""
        UPDATE contract
        SET
          archived_by_transaction_id = ${transactionId},
          archived_by_event_id = ${archivedByEventId}
        WHERE event_id = ${eventId}
      """

    def insertContract(event: CreatedEvent, transactionId: String, isRoot: Boolean): Fragment =
      sql"""
        INSERT INTO contract
        VALUES (
          ${event.eventId},
          DEFAULT, -- archived_by_event_id
          ${event.contractId},
          ${transactionId},
          DEFAULT, -- archived_by_transaction_id
          ${isRoot},
          ${event.templateId.packageId},
          ${event.templateId.name},
          ${toJsonString(event.createArguments)}::jsonb,
          ${toJsonString(event.witnessParties)}::jsonb
        )
      """
  }
}
