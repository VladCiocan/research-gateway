# Custom CA certificates (optional)

Drop any `*.crt` PEM files here to have them trusted **during the Docker build**.

This is only needed if you build behind a **TLS-intercepting proxy** (corporate
egress gateway, Zscaler, etc.) where Maven / npm cannot validate the certificate
chain to public registries.

- On a normal machine with direct internet access this directory can stay empty —
  the Docker build trusts the standard public CAs and works unchanged.
- Any `.crt` placed here is imported into the JVM truststore (Maven stage) and the
  system CA bundle (Node stage) before dependencies are downloaded.

`*.crt` files are gitignored so environment-specific CAs are never committed.
