## Alexandrite

### Maven artifacts

Every module under `libraries/` is published to Maven Central in the group `org.unitedsoftware.alexandrite`, and
`alexandrite-bom` aligns their versions.

For plugin authors:

- `alexandrite-plugin-sdk`: the plugin SDK, the only stable API. From 1.0 it follows semantic versioning.
- `alexandrite-testkit`: test doubles and a harness that runs a plugin in a runtime of its own.
- `alexandrite-ksp` and the Gradle plugin `org.unitedsoftware.alexandrite.plugin`: build a plugin.
- `alexandrite-channel-common`: helpers for a channel plugin, such as pairing chats with `alexandrite pair`.
- `alexandrite-provider-common`: helpers for a model provider plugin, such as HTTP endpoints and streamed answers.

For hosts that embed Alexandrite, the implementation modules: `alexandrite-runtime`, `alexandrite-internal`,
`alexandrite-agent`, `alexandrite-tools`, `alexandrite-commands`, `alexandrite-mcp`,
`alexandrite-channel-telegram`, `alexandrite-provider-anthropic-compatible`,
`alexandrite-provider-deepseek`, `alexandrite-provider-lmstudio`, `alexandrite-provider-openai-compatible`,
`alexandrite-provider-openai-responses`, `alexandrite-provider-openrouter`, `alexandrite-provider-pleochroic`,
`alexandrite-store-sqlite`,
`alexandrite-automation-common`, `alexandrite-automation-heartbeat`, `alexandrite-automation-schedule`,
`alexandrite-sandbox-bubblewrap`, `alexandrite-sandbox-seatbelt` and `alexandrite-account-pleochroic` (a Pleochroic
account's device login, self-hosted sign-in, status and two-way config/secret/skill sync, that `app`'s own CLI
commands are a thin wrapper over). Their API may change in any release, so a plugin builds on the libraries for
plugin authors only.
