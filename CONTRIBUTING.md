# Contributing

Use synthetic messages and disposable local databases. Run npm build/check/test and Android unit tests before submitting a change. Changes to receiving, MMS, notifications, Direct Boot, authorization or reconciliation require the relevant acceptance tests.

Never commit personal configuration or signing files. Run `npm run secret-scan` before publishing. Do not run untrusted public pull requests on a persistent personal runner containing deployment credentials. Signing/deployment triggers must be restricted to trusted branches or reviewed releases.

Keep the production footprint to one application and the configured existing PostgreSQL instance. Do not add analytics or another service without a concrete product requirement.
