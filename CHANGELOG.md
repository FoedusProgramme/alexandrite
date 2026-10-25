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
