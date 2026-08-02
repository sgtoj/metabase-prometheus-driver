# Local Demo

`atmos demo up` builds the plugin and starts only:

- Grafana Mimir in single-binary development mode
- Mimir's built-in continuous-test metric producer
- Metabase Community Edition with the exact packaged plugin JAR

It does not start a Prometheus server. The bootstrap creates a multitenant Mimir
connection and three saved cards on a `Mimir / Prometheus Demo` dashboard. Login
with `admin@example.test` / `MetabaseLocal123!`.

```bash
atmos demo up
atmos demo logs mimir
atmos demo down
atmos demo reset
```

Data is persisted across `down` and deleted by `reset` or `down --volumes`.

CSV export is exercised by the automated Metabase smoke suite. To test dashboard
subscriptions, configure SMTP in Metabase, open the generated dashboard, choose
Subscriptions, add the desired cards and recipients, and send a test. The demo
does not configure or emulate an SMTP server because a successful API response
without actual delivery would be misleading.
