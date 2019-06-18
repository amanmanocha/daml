// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.index

import java.io.FileWriter
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.daml.ledger.participant.state.v2.{ReadService, WriteService}
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.daml.lf.engine.Engine
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.domain
import com.digitalasset.ledger.api.domain.LedgerId
import com.digitalasset.ledger.server.apiserver.{ApiServer, ApiServices, LedgerApiServer}
import com.digitalasset.platform.index.StandaloneIndexServer.{
  asyncTolerance,
  createInitialState,
  logger
}
import com.digitalasset.platform.index.config.Config
import com.digitalasset.platform.sandbox.BuildInfo
import com.digitalasset.platform.sandbox.config.SandboxConfig
import com.digitalasset.platform.sandbox.metrics.MetricsManager
import com.digitalasset.platform.sandbox.stores.InMemoryPackageStore
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

object StandaloneIndexServer {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val asyncTolerance = 30.seconds

  def apply(
      config: Config,
      readService: ReadService,
      writeService: WriteService): StandaloneIndexServer =
    new StandaloneIndexServer(
      "sandbox",
      config,
      readService,
      writeService
    )

  // We memoize the engine between resets so we avoid the expensive
  // repeated validation of the sames packages after each reset
  private val engine = Engine()

  // if requested, initialize the ledger state with the given scenario
  private def createInitialState(packageContainer: InMemoryPackageStore): Unit = {
    // [[ScenarioLoader]] needs all the packages to be already compiled --
    // make sure that that's the case
    for {
      (pkgId, _) <- packageContainer.listLfPackagesSync()
      pkg <- packageContainer.getLfPackageSync(pkgId)
    } {
      engine
        .preloadPackage(pkgId, pkg)
        .consume(
          { _ =>
            sys.error("Unexpected request of contract")
          },
          packageContainer.getLfPackageSync, { _ =>
            sys.error("Unexpected request of contract key")
          }
        )
    }
  }
}

class StandaloneIndexServer(
    actorSystemName: String,
    config: Config,
    readService: ReadService,
    writeService: WriteService) {

  case class ApiServerState(
      ledgerId: LedgerId,
      apiServer: ApiServer,
      indexAndWriteService: AutoCloseable
  ) extends AutoCloseable {
    def port: Int = apiServer.port

    override def close: Unit = {
      apiServer.close() //fully tear down the old server.
      indexAndWriteService.close()
    }
  }

  case class Infrastructure(
      actorSystem: ActorSystem,
      materializer: ActorMaterializer,
      metricsManager: MetricsManager)
      extends AutoCloseable {
    def executionContext: ExecutionContext = materializer.executionContext

    override def close: Unit = {
      materializer.shutdown()
      Await.result(actorSystem.terminate(), asyncTolerance)
      metricsManager.close()
    }
  }

  case class SandboxState(apiServerState: ApiServerState, infra: Infrastructure)
      extends AutoCloseable {
    override def close(): Unit = {
      apiServerState.close()
      infra.close()
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ExplicitImplicitTypes"))
  private def buildAndStartApiServer(infra: Infrastructure): ApiServerState = {
    implicit val mat = infra.materializer
    implicit val ec: ExecutionContext = infra.executionContext
    implicit val mm: MetricsManager = infra.metricsManager

    val packageStore = new InMemoryPackageStore()
    config.archiveFiles.foreach { f =>
      packageStore.putDarFile(Instant.now, f.getName, f)
    }
    createInitialState(packageStore)

    val initF = for {
      cond <- readService.getLedgerInitialConditions().runWith(Sink.head)
      indexService <- PostgresIndex(
        readService,
        domain.LedgerId(cond.ledgerId),
        config.jdbcUrl,
        packageStore)
    } yield (cond.ledgerId, cond.config.timeModel, indexService)

    val (actualLedgerId, timeModel, indexService) = Try(Await.result(initF, asyncTolerance))
      .fold(t => {
        val msg = "Could not create SandboxIndexAndWriteService"
        logger.error(msg, t)
        sys.error(msg)
      }, identity)

    val apiServer = Await.result(
      LedgerApiServer.create(
        (am: ActorMaterializer, esf: ExecutionSequencerFactory) =>
          ApiServices
            .create(
              writeService,
              indexService,
              StandaloneIndexServer.engine,
              TimeProvider.UTC,
              timeModel,
              SandboxConfig.defaultCommandConfig,
              None)(am, esf),
        config.port,
        None,
        config.tlsConfig.flatMap(_.server)
      ),
      asyncTolerance
    )

    val newState = ApiServerState(
      domain.LedgerId(actualLedgerId),
      apiServer,
      indexService
    )

    logger.info(
      "Initialized index server version {} with ledger-id = {}, port = {}, dar file = {}, ledger = {}, daml-engine = {}",
      BuildInfo.Version,
      actualLedgerId,
      newState.port.toString,
      packageStore
    )

    writePortFile(newState.port)

    newState
  }

  def start(): SandboxState = {
    val actorSystem = ActorSystem(actorSystemName)
    val infrastructure =
      Infrastructure(actorSystem, ActorMaterializer()(actorSystem), MetricsManager(false))
    val apiState = buildAndStartApiServer(infrastructure)

    logger.info("Started Index Server")

    SandboxState(apiState, infrastructure)
  }

  private def writePortFile(port: Int): Unit = {
    config.portFile.foreach { f =>
      val w = new FileWriter(f)
      w.write(s"$port\n")
      w.close()
    }
  }

}
