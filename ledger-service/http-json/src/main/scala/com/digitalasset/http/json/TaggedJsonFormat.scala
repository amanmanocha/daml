// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.http.json

import scalaz.{@@, Tag}
import spray.json.JsonFormat

object TaggedJsonFormat {
  def taggedJsonFormat[A: JsonFormat, T]: JsonFormat[A @@ T] = Tag.subst(implicitly[JsonFormat[A]])
}
