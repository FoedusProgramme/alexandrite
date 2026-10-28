# Changelog

## 1.0.0-alpha.3

- `alexandrite init` walks through ten steps: language and time zone, model (offering side models for images, audio
  and video when the chosen model takes none), channels, Pleochroic account, importing skills and MCP servers,
  web search and fetch, browser automation, writing the config, the background service, and a summary.
- Init imports skills and MCP servers from Hermes, Claude Code, Codex and OpenClaw, and from a Pleochroic account.
  Secrets they carry move to `secrets.env`.
- New channels: OneBot v11 and the official QQ bot platform.
- In a QQ group, an operator is recognized only after linking their group id with `/link` in a paired private chat;
  the group's own owner and admin roles grant nothing.
- An approval lists every rule that allowing it for good grants.
- A guest's approval is sent only to the operators' private chats.
- `/approve` and `/deny` work in any case, and an approval waits between 1 and 1440 minutes.
- A Pleochroic account: `alexandrite login pleochroic` signs in with a device code, or with `--service-url` and
  `--service-token` to a self-hosted [standalone-service](https://github.com/Pleochroic-Ltd/standalone-service);
  `alexandrite logout pleochroic` and `alexandrite account`.
- A Pleochroic model provider, and Pleochroic as a backend of `web.search` and `web.fetch`.
- `browser.use`: a headless browser, one session per conversation, offered where `tools.web.browserBackend` names a
  backend; Pleochroic's is the first, turned on by `alexandrite login pleochroic` when the plan includes it, and init's
  browser step (`--browser-backend`) picks it once signed in. Screenshots come back as images.
- SearXNG and DuckDuckGo backends for `web.search`.
- Two-way sync of config, secrets and skills with a Pleochroic account: `alexandrite sync enable`, `disable`, `now`
  and `status`. A pulled change that could harm this machine, such as any skill change, an MCP server, a provider
  endpoint or an agent's permissions, is held until `alexandrite sync review` and `sync apply`.
- Sub-agent runs: a channel can show each run apart from the turn that delegated it, a person can write to a running
  run, and an operator can resume or fork a run that ended.
- Side models are picked by role, and a turn's images, audio and video go to the model that takes them.
- `fs.read` returns images.
- A heartbeat's gated calls are asked about in its agent's channel.
- The judge reads the operators' last messages before the turn.
- `allowedit` runs file writes unasked only where the turn's writes may land.
- `secrets.env` is written owner-only from its first byte.
- `alexandrite uninstall` also removes the config backup.
- Every command loads plugins as a start does, and reports those that cannot load.
- Breaking SDK changes: `Submission` gains `ToRun`; values the host makes are internal; closed kinds Alexandrite may
  extend are open.
- New libraries: `alexandrite-account-pleochroic` (sign-in and sync), `alexandrite-discovery` (finding other tools'
  skills and MCP servers), `alexandrite-channel-onebot` and `alexandrite-channel-qqbot`; `WebBrowserBackend` in the
  plugin SDK, a session (`open`, `act`, `close`) of the steps Pleochroic's browser takes.

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
