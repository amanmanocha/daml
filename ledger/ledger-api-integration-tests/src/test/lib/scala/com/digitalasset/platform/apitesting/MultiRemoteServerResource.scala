package com.digitalasset.platform.apitesting

import java.io.File

import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.{PackageId, Party}
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.testing.utils.Resource
import com.digitalasset.platform.apitesting.LedgerContext.MultiChannelContext
import com.digitalasset.platform.common.LedgerIdMode
import com.typesafe.config.{ConfigFactory, ConfigObject}

import scala.collection.JavaConverters._

object MultiRemoteServerResource {
  def fromConfig(file: File,
                 defaultParty: Party,
                 configuredLedgerId: LedgerIdMode,
                 packageIds: List[PackageId],
                 _esf: ExecutionSequencerFactory): MultiRemoteServerResource = {
    val hostMappingCfg = ConfigFactory.parseFile(file)
    val hostMapping = hostMappingCfg.root().entrySet().asScala.foldRight[Map[Party,RemoteServerResource]](Map.empty) { case (entry, map) =>
        // using this config API, cannot see any other way to get inner object
        val cfg  = entry.getValue.asInstanceOf[ConfigObject].toConfig
        val host = cfg.getString("host")
        val port = cfg.getInt("port")
        map + (Ref.Party.assertFromString(entry.getKey) -> RemoteServerResource(host, port, None))
      }
    new MultiRemoteServerResource(hostMapping, defaultParty, configuredLedgerId, packageIds, _esf)
  }
}

class MultiRemoteServerResource(val mapping: Map[Party, RemoteServerResource],
                                val defaultParty: Party,
                                val configuredLedgerId: LedgerIdMode,
                                val packageIds: List[PackageId],
                                implicit val _esf: ExecutionSequencerFactory) extends Resource[LedgerContext] {

  @volatile
  private var multiLedgerContext: MultiChannelContext = _

  /**
    * Access the resource.
    */
  override def value: LedgerContext = multiLedgerContext

  /**
    * Initialize the resource.
    */
  override def setup(): Unit = {
    multiLedgerContext = new
      MultiChannelContext(mapping.mapValues { server => {
        server.setup()
        server.value match {
          case PlatformChannels(channel) =>
            LedgerContext.SingleChannelContext(channel, configuredLedgerId, packageIds)
        }}},
        defaultParty,
        _esf)
  }

  /** Dispose of the resource */
  override def close(): Unit = {
    mapping.foreach { case (_, server) =>
      server.close()
    }
  }

}
