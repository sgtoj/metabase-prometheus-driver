# Querying

The driver executes native PromQL only. A plain query is an instant query at the
dashboard execution time:

```promql
sum(rate(http_requests_total[5m]))
```

## Directive

An optional first line controls the API endpoint. The reserved prefix is
`# metabase-mimir`.

```promql
# metabase-mimir mode=range step=auto time={{when}}
sum by (service) (rate(http_requests_total{service=~{{service}}}[$__interval]))
```

Supported keys:

| Key     | Values                             | Default                       |
| ------- | ---------------------------------- | ----------------------------- |
| `mode`  | `instant`, `range`, `label-values` | `instant`                     |
| `step`  | Prometheus duration or `auto`      | `auto` in range mode          |
| `time`  | One Metabase date/date-range tag   | Previous hour                 |
| `label` | Prometheus label name              | Required in label-values mode |

Unknown, duplicate, malformed, or mode-incompatible keys fail before any HTTP
request is sent.

## Time Tokens

The driver replaces tokens only in executable PromQL, never in quoted strings or
comments:

| Token         | Meaning                                       |
| ------------- | --------------------------------------------- |
| `$__start`    | Range start as Unix seconds                   |
| `$__end`      | Range end as Unix seconds                     |
| `$__range`    | Selected range as a Prometheus duration       |
| `$__interval` | Effective query step as a Prometheus duration |

Automatic steps are calculated as
`ceil(range_seconds / (maximum_data_points - 1))`, rounded up to whole seconds
and bounded by the configured minimum step. Explicit steps that would exceed the
point limit are rejected.

## Parameters

Text values are Prometheus string literals with correct escaping. A single text
value preserves regex metacharacters so advanced users can supply regexes. A
multi-select text value becomes an escaped capturing alternation such as
`(catalog|checkout)`. Numbers are finite decimal literals. Because PromQL has no
boolean literal, boolean parameters are rendered as valid scalar values `1` and
`0`.

Optional blocks use Metabase's normal syntax:

```promql
api_errors_per_second{status=~"5.."[[, service=~{{service}}]]}
```

The whole block is removed if any tag in it has no value. Missing required tags,
unsupported field-filter shapes, and unresolved tags fail with actionable
errors. Date and date-range field filters bind to the virtual
`query_context.timestamp` field and are interpreted in the Metabase report
timezone.

## Label Values

The query body is a required metric selector used as `match[]`:

```promql
# metabase-mimir mode=label-values label=service
http_requests_total{environment="production"}
```

The result has one text column named `value`.

Prometheus 3 UTF-8 label names are encoded automatically with Prometheus Values
Escaping for the API path. Names containing directive whitespace can be supplied
in their `U__...` Values Escaping form.

## Result Shapes

Matrix and vector responses containing only float samples produce:

```text
timestamp | series | metric | <dynamic labels...> | value
```

If any returned sample is a native histogram, three fixed columns are appended:

```text
timestamp | series | metric | <dynamic labels...> | value | histogram_count | histogram_sum | histogram_buckets
```

Each float or histogram sample produces one row. Float rows populate the numeric
`value` column and leave the histogram columns null. Histogram rows leave
`value` null, expose count and sum as numeric values, and preserve every bucket
in `histogram_buckets` as compact JSON using Prometheus tuples:
`[[boundary_rule,"left","right","count"],...]`. Boundary rules `0`, `1`, `2`,
and `3` mean open-left, open-right, open-both, and closed-both, respectively. A
histogram with no returned buckets uses `[]`. Mixed matrix samples are ordered
by timestamp within each series.

Dynamic labels are the lexical union across every returned series. Missing
labels are null. Labels colliding with standard columns are prefixed with
`label_`. Scalar and string responses produce `timestamp, value`. Non-finite
numeric samples, including histogram count and sum, become null and add a result
warning. Bucket strings are preserved exactly in their JSON tuples.

## Guardrails

The driver enforces query duration, range duration, data points per series,
returned rows, materialized cells, JSON structure, and decompressed response
bytes. Float and histogram samples both count as data points and rows; histogram
columns count toward the cell limit. Backend error text is bounded; credentials
and raw PromQL are never logged. Debug telemetry contains only mode, SHA-256
query hash, elapsed time, row/series counts, and response bytes.

## Troubleshooting

| Symptom                  | Check                                                                     |
| ------------------------ | ------------------------------------------------------------------------- |
| HTTP 401/403             | Authentication mode and credentials                                       |
| Missing tenant           | `X-Scope-OrgID` tenant setting for multitenant Mimir                      |
| HTTP 404                 | Base URL path prefix, usually `/prometheus` for Mimir                     |
| Too many points/rows     | Narrow the date range, increase the step, or adjust explicit limits       |
| Empty chart              | Metric selector, tenant, selected date range, and scrape/ingestion status |
| Unsupported visual query | Use the native PromQL editor; MBQL translation is intentionally absent    |
