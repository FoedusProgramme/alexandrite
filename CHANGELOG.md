# Changelog

## Unreleased

- A channel can give each sub-agent run a reply of its own (`ChannelCapabilities.runReplies`), which shows the run's
  words, thoughts, tool calls and report apart from the turn that delegated it.
- A channel is told what each sub-agent run was asked to do (`DelegatedRun.task`).
- A person can write to a running sub-agent run with `Submission.ToRun`; the run takes the message in after its
  current calls.
- When a person stops a run, the turn above is told who stopped it and what the run wrote last.
- An operator can resume or fork a sub-agent run that ended (`RunWay.RESUME`, `RunWay.FORK`): a turn of the chat's
  agent delegates a new run that goes on from the run's conversation, and ends with its report, which the agent reads
  in its next turn.
- Breaking SDK change: `Submission` gains the subtype `ToRun`.
- A Pleochroic account: `alexandrite login pleochroic` signs in by device login, or with `--service-url` and
  `--service-token` for a self-hosted standalone-service; `alexandrite logout pleochroic` revokes the key at the
  service and forgets it; `alexandrite account` shows the signed-in account's plan, scopes, credits and
  entitlements (`GET /v1/service`). The key is kept in `secrets.env` like any other secret, never in the config
  file, and signing in turns on `tools.web.searchBackend` / `fetchBackend` where the account is entitled for them.
- A new `libraries/providers/pleochroic` model provider: the signed-in (or self-hosted) account's Chat Completions
  API, with model listing and capabilities (context window, max output, image input, reasoning effort) read the way
  OpenRouter's are, and thinking read from `reasoning_content`.
- `web.search` and `web.fetch` gain a Pleochroic backend (`tools.web.searchBackend` / `fetchBackend` = `pleochroic`,
  `tools.web.pleochroicApiBase` / `pleochroicApiKey`), which `alexandrite login pleochroic` turns on by itself where
  the account is entitled; a `not_entitled`, `quota_exceeded` or `insufficient_credits` answer is told to the model
  as a clear refusal rather than a generic failure.
- Two-way sync of config documents (providers' endpoints, agents, mcp servers, the app's settings, instructions
  files), secrets and skills with a Pleochroic account's or a self-hosted standalone-service's sync API: `alexandrite
  sync enable [--prefer local|remote|merge]` (off until run), `sync disable`, `sync now [--dry-run]` and
  `sync status`. A later change on both sides is a conflict, resolved by the chosen policy; an object document under
  `merge` keeps the remote's keys the local side never touched. Skills sync to `skills/<name>` (shared) and
  `agents/<id>/skills/<name>` (per agent) beside the config file. Sub-agent profiles are not synced yet.
- Sync's threat model: the account's server may be compromised or impersonated, so a pull must never be able to
  harm the local machine on its own. A pulled change to an mcp server, a provider endpoint, an instructions file, an
  agent's permission, sandbox, tools, channels, workspace or inline instructions, a settings value that is a URL or
  secret reference, or a secret that would overwrite an existing local value, is held for the owner to review rather
  than applied; so is overwriting or deleting a local item that was never synced before (including on the first
  sync). `alexandrite sync review [--json]` lists what is held, with why and a diff against current local content;
  `sync apply [--all] [<key>...]` applies it (re-deciding against current local content, so a local edit made since
  it was staged is not lost); `sync discard [--all] [<key>...]` dismisses it without applying it. A pulled config
  document's secret reference (`${secret:NAME}`) is only accepted for one of the account's own synced secrets; any
  other reference, including the local `${env:NAME}` form, is never applied silently. Skill names, scopes, agent
  ids and file paths a pull names are validated and resolved strictly under their own directory, never following a
  symlink or a traversal segment, with caps on a skill's file count and total size.
- The Pleochroic account's sign-in, status and two-way sync moved into a published library,
  `libraries/accounts/pleochroic` (Maven coordinates `org.unitedsoftware.alexandrite:alexandrite-account-pleochroic`),
  so a host other than `app` (the alx terminal front end, for one) can embed the same device login, self-hosted
  sign-in and sync engine without reimplementing them.

## 1.0.0-alpha.2

- Config keys with dots, like model names, are written in brackets: `models["gpt-6.1-sol"]`.
- A model with an unknown context window is managed as if it had 128k tokens.
- A large request, or an answer that breaks off, is retried once.
- Proxies no longer count streamed answers as failed.
- `alexandrite uninstall` also removes the PATH lines the install script added.

## 1.0.0-alpha.1

- Publish every library module on Maven Central.
- `alexandrite config` opens an interactive menu on a terminal.
- Actually make Windows usable.
- Folders are trusted per agent, and agents' messages act for the person who started them.
- Breaking SDK changes: approval answers, untaint results, turn phases and trust control.

## 0.1.0-alpha.1

The first public build. Alexandrite is an agent harness framework on the JVM. Expect breaking changes until 1.0, from
which the SDK follows semantic versioning.
Plugin SDK, KSP processor, testkit, Gradle plugin and BOM should be available on Maven Central shortly after release.
