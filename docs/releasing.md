# Releasing

Release work uses the same Atmos entry points locally and in CI.

```bash
atmos release verify --version 1.0.0
```

The artifact command creates:

```text
build/release/prometheus.metabase-driver.jar
build/release/prometheus.metabase-driver.jar.sha256
build/release/metabase-prometheus-driver-1.0.0.cdx.json
```

The JAR keeps the community-driver `<driver>.metabase-driver.jar` name that
Metabase's own driver build produces, so the published asset is byte-identical
to the file an administrator drops into the plugins directory. The release
version is carried inside the artifact by the JAR manifest
`Implementation-Version` and by `metabase-plugin.yaml`, and in the SBOM
filename.

`atmos release reproducible --version 1.0.0` builds the exact version twice and
compares JAR bytes. The versioned verify command loads that exact JAR into every
supported Metabase/backend pairing. Tagged GitHub releases additionally create
keyless Sigstore bundles, attach GitHub build provenance, and publish every
release file. Signing requires an OIDC-capable CI environment; no long-lived
signing key is stored.

To verify a downloaded `1.0.0` release:

```bash
sha256sum --check prometheus.metabase-driver.jar.sha256
cosign verify-blob \
  --bundle prometheus.metabase-driver.jar.sigstore.json \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp \
    '^https://github\.com/cruxstack/metabase-prometheus-driver/\.github/workflows/release\.yaml@refs/tags/v1\.0\.0$' \
  prometheus.metabase-driver.jar
gh attestation verify prometheus.metabase-driver.jar \
  --repo cruxstack/metabase-prometheus-driver
```

To confirm which version a downloaded JAR contains:

```bash
unzip -p prometheus.metabase-driver.jar META-INF/MANIFEST.MF | grep Implementation-Version
unzip -p prometheus.metabase-driver.jar metabase-plugin.yaml | grep '^  version:'
```

## Community Driver Listing

Metabase lists third-party drivers in
[`docs/developers-guide/community-drivers.md`](https://github.com/metabase/metabase/blob/master/docs/developers-guide/community-drivers.md).
Listing is a documentation pull request against `metabase/metabase`; there is no
review, signing, or approval gate, and listed drivers are still installed at the
administrator's own risk. Add one alphabetically ordered row:

```markdown
| [Prometheus / Grafana Mimir](https://github.com/cruxstack/metabase-prometheus-driver) | ![GitHub stars](https://img.shields.io/github/stars/cruxstack/metabase-prometheus-driver) | ![GitHub (Pre-)Release Date](https://img.shields.io/github/release-date-pre/cruxstack/metabase-prometheus-driver) |
```

The badges Metabase renders read the repository's stars and latest release date,
so a published GitHub release is a prerequisite for a useful listing.
