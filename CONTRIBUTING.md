# Contributing

Use Atmos for every project operation; do not install Java, Gradle, Kotlin, or
Clojure on the host for this repository.

```bash
atmos project doctor
atmos workflow verify -f verify
atmos workflow compatibility -f compatibility
```

Keep the Clojure adapter small and isolate any additional Metabase-internal API
behind a documented compatibility boundary. Query, transport, parsing, and
result behavior belongs in Kotlin with fast local tests. Add a live backend or
packaged-JAR test for every backend- or Metabase-specific behavior.

Never commit credentials, tokens, generated demo data, build output, or local
tool caches. Do not log raw PromQL or connection details. Run
`atmos project lock` when Gradle dependencies change and include the resulting
lock and verification metadata updates.
