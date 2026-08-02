# Releasing

Release work uses the same Atmos entry points locally and in CI.

```bash
atmos release verify --version 1.0.0
```

The artifact command creates:

```text
build/release/metabase-prometheus-driver-1.0.0.jar
build/release/metabase-prometheus-driver-1.0.0.jar.sha256
build/release/metabase-prometheus-driver-1.0.0.cdx.json
```

`atmos release reproducible --version 1.0.0` builds the exact version twice and
compares JAR bytes. The versioned verify command loads that exact JAR into every
supported Metabase/backend pairing. Tagged GitHub releases additionally create
keyless Sigstore bundles, attach GitHub build provenance, and publish every
release file. Signing requires an OIDC-capable CI environment; no long-lived
signing key is stored.

To verify a downloaded `1.0.0` release:

```bash
sha256sum --check metabase-prometheus-driver-1.0.0.jar.sha256
cosign verify-blob \
  --bundle metabase-prometheus-driver-1.0.0.jar.sigstore.json \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp \
    '^https://github.com/cruxstack/metabase-prometheus-driver/.github/workflows/release.yaml@refs/tags/v1\\.0\\.0$' \
  metabase-prometheus-driver-1.0.0.jar
gh attestation verify metabase-prometheus-driver-1.0.0.jar \
  --repo cruxstack/metabase-prometheus-driver
```
