.. Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
.. SPDX-License-Identifier: Apache-2.0

Release notes
#############

This page contains release notes for the SDK.

HEAD — ongoing
--------------

- [Scala bindings] Contract keys are exposed on CreatedEvent. See `#1681 <https://github.com/digital-asset/daml/issues/1681>`__.
- [Navigator] Contract keys are show in the contract details page. See `#1681 <https://github.com/digital-asset/daml/issues/1681>`__.
- [DAML Standard Library] **BREAKING CHANGE**: Remove the deprecated modules ``DA.Map``, ``DA.Set``, ``DA.Experimental.Map`` and ``DA.Experimental.Set``. Please use ``DA.Next.Map`` and ``DA.Next.Set`` instead.
- [Sandbox] Fixed an issue when CompletionService returns offsets having inclusive semantics when used for re-subscription. 
  See `#1932 <https://github.com/digital-asset/daml/pull/1932>`__.
  
- [DAML Compiler] The default output path for all artifacts is now in the ``.daml`` directory.
  In particular, the default output path for .dar files in ``daml build`` is now
  ``.daml/dist/<projectname>.dar``.
- [Sandbox] Added `--log-level` command line flag.
- [Ledger API] Added new CLI flags ``--stable-party-identifiers`` and
  ``--stable-command-identifiers`` to the :doc:`Ledger API Test Tool
  </tools/ledger-api-test-tool/index>` to allow disabling randomization of party
  and command identifiers. It is useful for testing of ledgers which are
  configured with a predefined static set of parties.
- [DAML-LF]: Release version 1.6. This versions provides:

  + ``enum`` types.  See `#105 <https://github.com/digital-asset/daml/issues/105>`__.
  + new builtins for (un)packing strings. See `#16 <https://github.com/digital-asset/daml/issues/16>`__.
  + intern package IDs. See `#1614 <https://github.com/digital-asset/daml/pull/1614>`__.

- [Ledger API] Add support for ``enum`` types.

- [Java Codegen]: Add support for ``enum`` types.

- [Scala Codegen]: Add support for ``enum`` types.

- [Navigator]: Add support for ``enum`` types.

- [Extractor]: Add support for ``enum`` types.

- [DAML Compiler]: Add support for DAML-LF 1.6. DAML variants type that look
  like enumerations (i.e., those variants without type parameters and without
  argument) are compiled to DAML-LF ``enum`` type when daml-lf 1.6 target is
  selected.