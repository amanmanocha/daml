// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.extractor.writers

import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.digitalasset.extractor.Types._
import com.digitalasset.extractor.config.ExtractorConfig
import com.digitalasset.extractor.ledger.LedgerReader
import com.digitalasset.extractor.ledger.types._
import com.digitalasset.extractor.targets.PostgreSQLTarget
import com.digitalasset.extractor.writers.Writer._
import com.digitalasset.extractor.writers.postgresql.DataFormatState._
import com.digitalasset.extractor.writers.postgresql._
import com.typesafe.scalalogging.StrictLogging
import doobie._
import doobie.free.connection
import doobie.implicits._
import scalaz.Scalaz._
import scalaz._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class PostgreSQLWriter(config: ExtractorConfig, target: PostgreSQLTarget, ledgerId: String)
    extends Writer
    with StrictLogging {

  // Uncomment this to have queries logged
  // implicit val lh = doobie.util.log.LogHandler.jdkLogHandler

  import postgresql.Queries._

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private val singleTableFormat = new SingleTableDataFormat()

  @volatile private var witnessedPackages: Set[String] = Set.empty

  // A transactor that gets connections from java.sql.DriverManager
  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", // driver classname
    target.connectUrl, // connect URL (driver-specific)
    target.user,
    target.password
  )

  def init(): Future[Unit] = {
    logger.info("PostgreSQLWriter initializing...")

    val io = for {
      _ <- StateHandler.init()
      previousState <- StateHandler.retrieveStatus
      io <- previousState.fold {
        // There were no state, start with a clean slate
        val drop = dropTransactionsTable.update.run
        val createTrans = createTransactionsTable.update.run
        val indexTrans = transactionsIndex.update.run
        val createExercise = createExerciseTable.update.run
        val singleInit = singleTableFormat.init()

        drop *> createTrans *> indexTrans *> createExercise *> singleInit
      } { statusOrRetrieveError =>
        val statusOrError = for {
          prevStatus <- statusOrRetrieveError
          _ <- StateHandler.validateArgumentsAgainstStatus(prevStatus, ledgerId, config, target)
        } yield prevStatus

        statusOrError.fold(
          e => connection.raiseError(DataIntegrityError(e)), { status =>
            witnessedPackages = status.witnessedPackages

            connection.pure(())
          }
        )
      }
    } yield io

    io.transact(xa).unsafeToFuture()
  }

  def handlePackages(packageStore: LedgerReader.PackageStore): Future[Unit] = {

    val updatedWitnessedPackages = packageStore.keySet

    val saveStatus = StateHandler.saveStatus(
      ledgerId,
      config,
      target,
      updatedWitnessedPackages)

    (connection.pure(()) *> saveStatus)
      .transact(xa)
      .map { _ =>
        witnessedPackages = updatedWitnessedPackages

        logger.trace(s"Witnessed packages: ${witnessedPackages}")
      }
      .unsafeToFuture()
  }

  def handleTransaction(transaction: TransactionTree): Future[RefreshPackages \/ Unit] = {
    logger.trace(s"Handling transaction: ${com.digitalasset.extractor.pformat(transaction)}")

    val insertIO = insertTransaction(transaction).update.run.void

    val createdEvents: List[CreatedEvent] = transaction.events.values.collect {
      case e @ CreatedEvent(_, _, _, _, _) => e
    }(scala.collection.breakOut)

    val exercisedEvents: List[ExercisedEvent] = transaction.events.values.collect {
      case e @ ExercisedEvent(_, _, _, _, _, _, _, _, _, _) => e
    }(scala.collection.breakOut)

    logger.trace(s"Create events: ${com.digitalasset.extractor.pformat(createdEvents)}")
    logger.trace(s"Exercise events: ${com.digitalasset.extractor.pformat(exercisedEvents)}")

    (for {
      archiveIOsSingle <-
        exercisedEvents.traverseU(
          singleTableFormat.handleExercisedEvent(SingleTableState, transaction, _))

      createIOsSingle <-
        createdEvents.traverseU(
          singleTableFormat.handleCreatedEvent(SingleTableState, transaction, _))
    } yield {
      val sqlTransaction =
        (archiveIOsSingle ++ createIOsSingle)
          .foldLeft(insertIO)(_ *> _)

      sqlTransaction.transact(xa).unsafeToFuture()
    }).sequence
  }

  def getLastOffset: Future[Option[String]] = {
    lastOffset.query[String].option.transact(xa).unsafeToFuture()
  }
}
