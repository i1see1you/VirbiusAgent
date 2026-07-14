# Contributing to VirbiusAgent

Thank you for your interest in contributing! We welcome contributions of all forms.

## Code of Conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you agree to uphold this code.

## How to Contribute

### Reporting Issues

- **Bug reports**: Include steps to reproduce, expected vs actual behavior, and environment details.
- **Feature requests**: Describe the use case and why it would benefit the project.
- **Security vulnerabilities**: Please report via [SECURITY.md](SECURITY.md) — do **not** file public issues.

### Pull Requests

1. Fork the repository.
2. Create a feature branch: `git checkout -b feat/my-feature`.
3. Make your changes following the code style conventions below.
4. Write or update tests as needed.
5. Run tests locally: `cargo test && mvn test`.
6. Commit with a descriptive message. Use [conventional commits](https://www.conventionalcommits.org/) format:
   - `feat: ...` for new features
   - `fix: ...` for bug fixes
   - `docs: ...` for documentation
   - `refactor: ...` for code refactoring
   - `test: ...` for test changes
7. Push and open a PR against the `main` branch.

### Development Setup

See [README.md](README.md#quick-start) for local development setup.

## Code Style

| Language | Style |
|----------|-------|
| Rust | `cargo fmt`, follow clippy suggestions |
| Java | 4-space indentation, follow existing conventions |
| Go | `gofmt` (tabs) |
| SQL | UPPERCASE keywords, lowercase identifiers |

## Project Structure

```
virbius-core/        # Rust: SDK, prechecks, DLP, license
virbius-mcp-proxy/   # Rust: MCP proxy server
virbius-kernel/      # Rust: Falco plugin, sandbox
virbius-gateway/     # Go: WASM plugin for Higress
virbius-control/     # Java: Spring Boot control plane
virbius-engine/      # Java: Spring Boot security engine
virbius-compiler/    # Java: bundle compiler
virbius-policy/      # Java: policy domain model
virbius-groovy-l3/   # Java: Groovy L3 adjudicator
```

## Testing

- Rust: `cargo test --workspace`
- Java: `mvn test`
- E2E: `cargo test --test e2e_integration`
- Smoke: `./scripts/smoke-test.sh`

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
