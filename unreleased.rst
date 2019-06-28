.. Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
.. SPDX-License-Identifier: Apache-2.0

Release notes
#############

This page contains release notes for the SDK.

HEAD — ongoing
--------------

- [DAML Standard Library] **BREAKING CHANGE**: Remove the deprecated modules ``DA.Map``, ``DA.Set``, ``DA.Experimental.Map`` and ``DA.Experimental.Set``. Please use ``DA.Next.Map`` and ``DA.Next.Set`` instead.
- [Sandbox] DAML-LF packages used by the sandbox are now stored in Postgres,
  allowing users to resume a Postgres sandbox ledger without having to again
  specify all packages through the CLI.
  See `#1929 <https://github.com/digital-asset/daml/issues/1929>`__.