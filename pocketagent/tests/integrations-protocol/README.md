# Codex integration boundary fixtures

Run `tests/run-integrations-protocol-tests.sh` with `POCKETAGENT_JSON_JAR` pointing
to JSON-Java 20250517. The runner verifies its SHA-256 and uses real JSON methods,
without Android SDK stubs or provider credentials.

The schemas under `schemas/codex-0.154.0` are copied from the JSON schema output
of the pinned Codex 0.154.0 executable used for the earlier protocol smoke check.
They are generated from the OpenAI Codex Apache-2.0 project:
https://github.com/openai/codex. Fixtures check required and supported request
field names against these snapshots; this is not a complete JSON Schema validator.

The tests exercise fixed MCP/app config writes, path/name constraints, browser
handoff URLs, exact current-catalog selection, typed skill input, marketplace
identity, plugin availability and actual-versus-unknown connector runtime state.
They do not establish that a particular MCP service, OAuth issuer, plugin or
connector will work with a real user account on an Android phone.
