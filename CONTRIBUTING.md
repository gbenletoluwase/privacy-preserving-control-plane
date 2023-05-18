# Contributing

Issues and pull requests are welcome.

## Before opening a pull request

```bash
mvn verify
```

Everything must pass; CI runs the same command on JDK 21 and 25. Record user-visible
changes in [CHANGELOG.md](CHANGELOG.md) under *Unreleased*. New behaviour needs a test, and the test should state in its
`@DisplayName` what property it is protecting rather than which method it calls.

## Style

- Java 21, standard conventions, four-space indent, 110 column soft limit.
- No new runtime dependencies. Test-scope dependencies are fine.
- Any method that depends on the current time takes an `Instant` parameter.
- Document the thread-safety contract of any class that holds state.
- Javadoc should say why a choice was made where the choice is not obvious. Restating
  the method signature in prose is not documentation.

## Scope

The four mechanisms are the scope. Integrations with specific orchestrators, model
registries or storage systems belong in separate repositories that depend on this one,
so that this one stays dependency free.

## Security

Do not open public issues for redaction bypasses or other vulnerabilities. See
[SECURITY.md](SECURITY.md).
