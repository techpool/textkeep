# Security

Do not post credentials, OTPs, private SMS, signing keys or production logs in public issues. Report vulnerabilities privately through the repository's GitHub security advisory feature.

Scope includes owner/device substitution, OAuth/PKCE/redirect/audience handling, sensitive-scope retrieval paths, key handling and plaintext leakage. Deployment secrets and signing credentials belong in private secret storage. Public Firebase client configuration is not an authorization boundary.

The personal deployment denies accounts outside its explicit verified Google allowlist. Empty allowlists and production emulator/debug configuration fail closed. SMS is treated as inert untrusted text; neither REST nor MCP executes message instructions. MCP has no SMS-send, SQL, shell, arbitrary fetch or deletion tool.

Application-layer encryption protects message content in a database-only leak, subject to retained queryable metadata. A compromised application server or authorized client may expose content. This product does not promise zero knowledge, perfect classifier filtering, guaranteed clipboard erasure or disaster recovery.
