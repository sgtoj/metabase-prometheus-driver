# Architecture

The artifact contains a Kotlin core and a thin Clojure Metabase adapter.

```text
Metabase QP
  -> Clojure multimethod adapter
  -> typed parameter bridge and query compiler
  -> bounded JDK HTTP transport
  -> dependency-free JSON parser and result normalizer
  -> Metabase result callback
```

The Kotlin core owns configuration validation, lexical substitution, date and
step semantics, HTTP, authentication, cancellation, JSON parsing, guardrails,
and result metadata. The adapter only registers driver multimethods, obtains
effective connection details, maps Metabase metadata, listens to the Metabase
cancellation channel, and reports results synchronously.

## Metabase Boundary

The adapter uses public `metabase.driver-api.core` functions wherever possible.
Four compatibility-sensitive references are isolated in
`src/main/clojure/metabase/driver/prometheus.clj`:

| API                                            | Reason                                                          |
| ---------------------------------------------- | --------------------------------------------------------------- |
| `metabase.driver.connection/effective-details` | Respect routed and overlaid connection details                  |
| `metabase.util.log`                            | Use Metabase's structured logging implementation                |
| `clojure.core.async`                           | React to the query cancellation channel while HTTP is in flight |
| Dynamic stage substitution method registration | Metabase 0.63 replaced the 0.60 native-parameter method         |

The stage method is resolved dynamically so one source and one JAR load on both
supported Metabase versions. Every boundary is exercised by packaged-JAR smoke
tests, not only by Kotlin mocks.

## Runtime Dependencies

No HTTP or JSON runtime library is bundled. The plugin uses JDK 21 APIs and the
Kotlin standard library supplied by supported Metabase versions. Packaging fails
if Kotlin or Clojure core classes are accidentally included.

## Security Model

Database administrators intentionally configure the backend URL, so the
connection test and query paths share the same network policy. TLS verification
uses the JVM trust store and cannot be disabled. Redirects are limited and may
not change scheme, host, or effective port. Arbitrary custom headers are not
supported.
