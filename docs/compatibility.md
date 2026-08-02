# Compatibility

## Initial Matrix

| Component       | Supported version      |
| --------------- | ---------------------- |
| Metabase OSS    | `v0.60.3.8`, `v0.63.2` |
| Grafana Mimir   | `3.1.4`                |
| Prometheus      | `3.13.2`               |
| Driver bytecode | Java 21                |
| Host Kotlin ABI | Kotlin stdlib 2.2      |

`atmos workflow compatibility -f compatibility` tests the same packaged JAR
against Mimir and Prometheus directly and through each Metabase version. The
Metabase smoke suite performs fresh setup, driver discovery, connection testing,
virtual metadata sync, instant and range queries, typed parameters, date-range
field filters, UTF-8 label values, native histogram conversion, empty results,
CSV export, and persisted Basic/Bearer secret resolution against an
authorization-enforcing fixture.

`atmos test metabase-synthetic` loads the same packaged JAR into current
Metabase and exercises a deterministic, in-process Prometheus read fixture. It
requires no external backend, data, or credentials and covers exact request
forms, result metadata, parameters, saved visualizations, CSV export, timeout
and backend failures, and client-disconnect cancellation. The fixture generates
only the public `api_requests_per_second`, `api_errors_per_second`,
`api_latency_milliseconds_bucket`, and `api_latency_milliseconds_sum` metrics.
Its query window is fixed at `2026-01-01T00:00:00Z` through
`2026-01-01T06:00:00Z` with a 60-second effective step.

## Policy

- Current supported patch versions are tested on every pull request.
- Dependency and image updates are reviewed monthly; critical security fixes are
  handled out of cycle.
- A backend or Metabase line is not listed as supported until its packaged-JAR
  matrix target passes.
- Removal of a supported line is announced in release notes at least one driver
  minor release before removal, except where a security issue requires immediate
  action.
- Mimir and Prometheus API differences remain behind the common Prometheus HTTP
  API contract. Backend-specific behavior must have its own test.

Future candidates include newer Metabase lines, Grafana Cloud/Mimir deployments,
Thanos, and other Prometheus-compatible read APIs. They are not supported merely
because a basic query happens to work.
