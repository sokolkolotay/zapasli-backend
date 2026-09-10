# Production deployment

Production endpoint: `https://api.zapasli.sokolkolotaj.ru`.

The deployment uses `compose.yaml` together with `compose.prod.yaml`:

- Caddy is the only service publishing host ports `80` and `443`;
- Ktor is reachable only from the private `edge` Docker network;
- PostgreSQL is reachable only from the internal `backend` Docker network;
- Caddy obtains and renews the public TLS certificate automatically;
- Caddy access logs redact credential headers by default;
- persistent volumes retain PostgreSQL data and Caddy certificates.

## Prerequisites

- the domain A record points to the VDS;
- Docker Engine and Docker Compose are installed;
- inbound TCP `80` and `443` are allowed only when Caddy is ready to start;
- the checkout is placed in `/opt/zapasli/backend`;
- `.env` exists only on the server, is owned by `root`, and has mode `600`.

## Secrets

Generate every value independently. Do not copy terminal output to issues,
logs, commits, chat messages, or CI variables unless that environment needs it.

Required `.env` keys are documented in `.env.example`. Production must use:

- a random database password;
- at least 32 random bytes, Base64 encoded, for JWT signing;
- a different random value for refresh-token HMAC hashing;
- `APP_VERSION` and `GIT_COMMIT` matching the deployed revision.

Never use the placeholders from `.env.example` in a running environment.

## Validate and start

Run from `/opt/zapasli/backend`:

```sh
docker compose -f compose.yaml -f compose.prod.yaml config --quiet
docker compose -f compose.yaml -f compose.prod.yaml build --pull backend
docker compose -f compose.yaml -f compose.prod.yaml up -d --remove-orphans
docker compose -f compose.yaml -f compose.prod.yaml ps
```

After deployment, verify HTTPS and the operational endpoints from another
machine. Do not include credentials or refresh tokens in shell history.

## Rollback

Checkout the previous tested tag, rebuild only `backend`, and run the same
`up -d` command. Do not remove named volumes during rollback. Database
migrations must remain backward compatible with the previous application tag.
