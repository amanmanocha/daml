// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.engine

import com.digitalasset.daml.lf.transaction.VersionTimeline
import com.digitalasset.daml.lf.language.{LanguageVersion, LanguageMajorVersion => LMV}
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.language.Ast.Package
import com.digitalasset.daml.lf.command._

/** Tx authorization is parametrized by the language version that "originated it".
  * For scenarios we just use the version of the module where the scenario
  * definition comes from. For Ledger API commands, we use the latest version
  * amongst the versions of the modules from where the templates of the commands
  * come from.
  *
  * We crash if we cannot find modules.
  */
case class CommandVersion(getPackage: PackageId => Package) {
  def templateVersion(templateId: Identifier): LanguageVersion =
    getPackage(templateId.packageId).modules(templateId.qualifiedName.module).languageVersion

  def commandVersion(command: Command): LanguageVersion =
    templateVersion(command.templateId)

  def commandsVersion(commands: Commands): LanguageVersion =
    // for no commands the version is irrelevant -- we just return
    // the earliest one.
    commands.commands
      .map { cmd =>
        templateVersion(cmd.templateId)
      }
      .foldLeft(LanguageVersion(LMV.V1, LMV.V1.acceptedVersions.head))(VersionTimeline.maxVersion)
}
