// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.extractor.targets

sealed abstract class Target
final case class PostgreSQLTarget(
    connectUrl: String,
    user: String,
    password: String
) extends Target
final case object TextPrintTarget extends Target
final case class PrettyPrintTarget(width: Int, height: Int) extends Target
