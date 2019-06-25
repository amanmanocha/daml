// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.http

import com.digitalasset.ledger.api.v1.value.Identifier
import com.digitalasset.ledger.client.services.pkg.PackageClient
import com.digitalasset.ledger.service.{LedgerReader, TemplateIds}
import scalaz.Scalaz._
import scalaz._

import scala.concurrent.{ExecutionContext, Future}

class PackageService(packageClient: PackageClient)(implicit ec: ExecutionContext) {
  import PackageService._

  def getTemplateIdMap(): Future[Error \/ TemplateIdMap] =
    EitherT(LedgerReader.createPackageStore(packageClient)).map { packageStore =>
      val templateIds = TemplateIds.getTemplateIds(packageStore.values.toSet)
      buildMap(templateIds)
    }.run

  private def buildMap(ids: Set[Identifier]): TemplateIdMap =
    ids.foldLeft(Map.empty[(String, String), Identifier]) { (b, a) =>
      b.updated((a.moduleName, a.entityName), a)
    }
}

object PackageService {
  type Error = String
  type TemplateIdMap = Map[(String, String), Identifier]

  def resolveTemplateIds(m: TemplateIdMap)(as: Set[domain.TemplateId]): Error \/ List[Identifier] =
    for {
      bs <- as.toList.traverseU(resolveTemplateId(m))
      _ <- validate(as, bs)
    } yield bs

  def resolveTemplateId(m: TemplateIdMap)(a: domain.TemplateId): Error \/ Identifier =
    a.packageId.map { x =>
      Identifier(packageId = x, moduleName = a.moduleName, entityName = a.entityName)
    } match {
      case Some(x) => \/.right(x)
      case None => findTemplateId(m)((a.moduleName, a.entityName))
    }

  private def findTemplateId(m: TemplateIdMap)(a: (String, String)): Error \/ Identifier =
    m.get(a)
      .fold(\/.left(s"Cannot resolve $a"): Error \/ Identifier)(x => \/.right(x))

  private def validate(
      requested: Set[domain.TemplateId],
      resolved: List[Identifier]): Error \/ Unit =
    if (requested.size == resolved.size) \/.right(())
    else
      \/.left(
        s"Template ID resolution error, the sizes of requested and resolved collections should match. " +
          s"requested: $requested, resolved: $resolved")
}