// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.extractor.targets

sealed abstract class Target


sealed case class SQLTarget(
                      driver: String,
                      connectUrl: String,
                      user: String,
                      password: String,
                      outputFormat: String,
                      schemaPerPackage: Boolean,
                      mergeIdentical: Boolean,
                      stripPrefix: Option[String]
                    ) extends Target

final  class PostgreSQLTarget(
                                   override val driver: String,
                                   override val connectUrl: String,
                                   override val user: String,
                                   override val password: String,
                                   override val outputFormat: String,
                                   override val schemaPerPackage: Boolean,
                                   override val mergeIdentical: Boolean,
                                   override val stripPrefix: Option[String]
                                 ) extends SQLTarget(
  driver,
  connectUrl,
  user,
  password,
  outputFormat,
  schemaPerPackage,
  mergeIdentical,
  stripPrefix)


final  class MSSQLTarget(
                              override val driver: String,
                              override val connectUrl: String,
                              override val user: String,
                              override val password: String,
                              override val outputFormat: String = "single-table",
                              override val schemaPerPackage: Boolean = false,
                              override val mergeIdentical: Boolean = false,
                              override val stripPrefix: Option[String] = Option.empty
                            ) extends SQLTarget(
  driver,
  connectUrl,
  user,
  password,
  outputFormat,
  schemaPerPackage,
  mergeIdentical,
  stripPrefix)

final case object TextPrintTarget extends Target

final case class PrettyPrintTarget(width: Int, height: Int) extends Target
