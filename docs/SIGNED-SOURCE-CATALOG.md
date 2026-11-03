# Signed source catalog

ProxyX 6 supports an optional remotely hosted source catalog. The app accepts a catalog only when its payload has a valid ECDSA P-256/SHA-256 signature, a supported schema, a future expiry, unique public HTTPS URLs, and at most 100 entries. A failed or expired remote catalog falls back to the last valid signed cache and then to the bundled catalog.

Configure release builds outside version control:

```properties
sourceCatalog.url=https://example.org/proxyx/catalog-envelope.json
sourceCatalog.publicKey=BASE64_X509_EC_PUBLIC_KEY
```

Environment equivalents are `PROXYX_SOURCE_CATALOG_URL` and `PROXYX_SOURCE_CATALOG_PUBLIC_KEY`.

The envelope format is:

```json
{
  "payload": "BASE64_UTF8_JSON",
  "signature": "BASE64_DER_ECDSA_SIGNATURE"
}
```

Decoded payload format:

```json
{
  "schemaVersion": 1,
  "issuedAt": 1788537600000,
  "expiresAt": 1789142400000,
  "mode": "MERGE",
  "sources": [
    {
      "name": "Example MTProto feed",
      "url": "https://example.org/mtproto.txt",
      "description": "Continuously maintained public MTProto links",
      "enabled": true
    }
  ]
}
```

Keep the private signing key offline. Only the X.509 public key belongs in release configuration. Sign the exact decoded payload bytes; changing whitespace after signing invalidates the envelope.
