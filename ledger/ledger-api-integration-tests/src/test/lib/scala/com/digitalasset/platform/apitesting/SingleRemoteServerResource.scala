package com.digitalasset.platform.apitesting

import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.testing.utils.Resource
import com.digitalasset.ledger.api.tls.TlsConfiguration
import com.digitalasset.platform.common.LedgerIdMode

object SingleRemoteServerResource {
  def fromHostAndPort(host: String,
                      port: Int,
                      tlsConfig: Option[TlsConfiguration],
                      configuredLedgerId: LedgerIdMode,
                      packageIds: List[PackageId],
                      _esf: ExecutionSequencerFactory): SingleRemoteServerResource =
    new SingleRemoteServerResource(RemoteServerResource(host, port, tlsConfig), configuredLedgerId, packageIds, _esf)
}

class SingleRemoteServerResource(val ledgerResource: RemoteServerResource,
                                 val configuredLedgerId: LedgerIdMode,
                                 val packageIds: List[PackageId],
                                 implicit val _esf: ExecutionSequencerFactory) extends Resource[LedgerContext] {
  @volatile
  private var ledgerContext: LedgerContext = _

  /**
    * Access the resource.
    */
  override def value: LedgerContext = ledgerContext

  /**
    * Initialize the resource.
    */
  override def setup(): Unit = {
    ledgerResource.setup()
    ledgerContext = ledgerResource.map {
            case PlatformChannels(channel) =>
              LedgerContext.SingleChannelContext(channel, configuredLedgerId, packageIds)
          }.value
  }

  /** Dispose of the resource */
  override def close(): Unit = {
    ledgerResource.close()
  }

}
