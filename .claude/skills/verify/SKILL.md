---
name: verify
description: Build, launch and drive mobility-manager locally to verify changes end-to-end
---

# Verifying mobility-manager locally

## Launch

```bash
mkdir -p data   # SQLite lives at ./data/mobility-manager.db relative to cwd; boot fails without the dir
SERVER_PORT=18080 ./mvnw spring-boot:run
```

- No profile and no secrets: sign-in is the auth lib's test-user picker, there is no GitHub client locally.
- App is up within ~10s; probe `http://127.0.0.1:18080/actuator/health` until 200.

## Sign in

Browser: `/login` → „Mit GitHub anmelden" → `/login/start` shows the picker → pick a user (Fry, Leela, …).
The first pick provisions a `users` row with provider `test`.

curl, with a cookie jar (the auth lib keeps the CSRF token in the `XSRF-TOKEN` cookie):

```bash
B=http://127.0.0.1:18080; J=$(mktemp)
curl -s -c $J -b $J -o /dev/null $B/login/start
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -c $J -b $J -o /dev/null -X POST $B/login/test/as --data-urlencode login=Fry --data-urlencode "_csrf=$T"
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)   # read it again after signing in
curl -s -c $J -b $J $B/vehicles                                                                  # a page
curl -s -c $J -b $J -X POST -H "HX-Request: true" -H "X-XSRF-TOKEN: $T" $B/fuel/reset           # htmx: header
curl -s -c $J -b $J -X POST $B/vehicles --data-urlencode name=Kombi --data-urlencode color=#06b6d4 --data-urlencode "_csrf=$T"   # form: field
```

A mutating request without the token answers 403.

## Gotchas

- Anonymous requests must not create sessions (`Set-Cookie: SESSION=…`) — the 30s healthcheck once filled prod with ~88k empty 30-day sessions. Every response carries `Set-Cookie: XSRF-TOKEN=…`; that is the CSRF cookie, not a session.
- An anonymous page GET also sets `Set-Cookie: REDIRECT_URI=…` — the page to return to after sign-in, kept in a cookie, not a session; every picker sign-in expires it (`Max-Age=0`).
- Inspect session persistence directly: `sqlite3 data/mobility-manager.db "SELECT COUNT(*) FROM SPRING_SESSION;"`
- The exact prod healthcheck: `bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'`
- The GitHub door only exists under the `production` profile; it cannot be driven locally.
