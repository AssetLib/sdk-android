# Security and preview limits

This is an early developer preview. No independent security audit or production SLA is claimed.

Trust originates in the public configuration distributed by the app developer. Its Ed25519 public key, organization/app IDs, and exact HTTPS manifest endpoint are checked before use. HTTPS is still required. Published artwork and delivery URLs are public, even though the console uses authenticated administration. Never include API/admin secrets or confidential media in configuration or reports.

The default app-private storage persists the highest signed sequence and exact payload identity. Corruption fails closed; clearing all app data, reinstalling, or a privileged attacker rolling back the entire app storage resets that history. There is no secure hardware monotonic counter, server attestation, key-rotation protocol, or background update scheduler in this preview. Keep the last known good bundle and preserve placements for older clients. A server rollback must issue a higher sequence.

Limits apply per connection namespace. Custom storage/transport implementations are responsible for preserving the same guarantees. This SDK does not pin TLS certificates; normal Android trust plus a separately pinned signing key is used. Image parsing uses the platform decoder after signature, size, hash and pixel-bound checks, so platform security updates remain relevant.

Report a suspected vulnerability using the repository's GitHub private vulnerability reporting facility when enabled. If unavailable, open a minimal issue requesting a private reporting channel; do not post exploitable details, personal information, public configurations tied to private workspaces, or secrets. No response time guarantee is offered.
