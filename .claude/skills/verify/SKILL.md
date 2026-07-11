---
name: verify
description: Build, launch and drive mobility-manager locally to verify changes end-to-end
---

# Verifying mobility-manager locally

## Launch

```bash
mkdir -p data   # SQLite lives at ./data/mobility-manager.db relative to cwd; boot fails without the dir
GITHUB_CLIENT_SECRET=dummy SERVER_PORT=18080 ./mvnw spring-boot:run
```

- `GITHUB_CLIENT_SECRET` is only needed at OAuth token exchange — a dummy value boots fine.
- App is up within ~10s; probe `http://127.0.0.1:18080/actuator/health` until 200.

## Drive

- `/actuator/health` is permitAll — reachable anonymously (this is what the Docker healthcheck polls every 30s).
- Everything else redirects anonymous requests to `/login` (GitHub OAuth) — a real login can't be driven locally; authenticated flows are covered by MockMvc integration tests with `oauth2Login()`.
- Inspect session persistence directly: `sqlite3 data/mobility-manager.db "SELECT COUNT(*) FROM SPRING_SESSION;"`

## Gotchas

- Anonymous requests must not create sessions (`Set-Cookie: SESSION=…`) — the 30s healthcheck once filled prod with ~88k empty 30-day sessions.
- The exact prod healthcheck: `bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'`
