package com.digitalasset.platform.index

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.ledger.participant.state.index.v2
import com.daml.ledger.participant.state.index.v2.IndexService
import com.daml.ledger.participant.state.v2.ReadService
import com.digitalasset.ledger.api.domain._
import com.digitalasset.platform.common.util.{DirectExecutionContext => DEC}
import com.digitalasset.platform.sandbox.metrics.MetricsManager
import com.digitalasset.platform.sandbox.stores.{InMemoryPackageStore, LedgerBackedIndexService}
import com.digitalasset.platform.sandbox.stores.ledger.Ledger
import com.digitalasset.platform.sandbox.stores.ledger.{MeteredReadOnlyLedger, SandboxContractStore}

import scala.concurrent.Future

object PostgresIndex {
  def apply(
      readService: ReadService,
      ledgerId: LedgerId,
      jdbcUrl: String,
      packageStore: InMemoryPackageStore)(
      implicit mat: Materializer,
      mm: MetricsManager): Future[IndexService with AutoCloseable] =
    Ledger
      .postgresReadOnly(jdbcUrl, ledgerId)
      .map { ledger =>
        val contractStore = new SandboxContractStore(ledger)
        new LedgerBackedIndexService(MeteredReadOnlyLedger(ledger), packageStore, contractStore) {
          override def getLedgerConfiguration(): Source[v2.LedgerConfiguration, NotUsed] =
            readService.getLedgerInitialConditions().map { cond =>
              v2.LedgerConfiguration(cond.config.timeModel.minTtl, cond.config.timeModel.maxTtl)
            }
        }
      }(DEC)
}
