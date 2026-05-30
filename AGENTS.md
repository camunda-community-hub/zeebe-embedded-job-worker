# AGENTS

- Build and test with `./mvnw verify`.
- Keep changes small and focused; avoid unrelated refactors.
- Prefer existing Camunda/Zeebe APIs and utilities over adding new dependencies.
- For integration tests, guard Docker-dependent execution with assumptions.
- When changing exporter behavior, update README configuration/usage notes in the same PR.
