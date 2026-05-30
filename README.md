# mobility-manager

Spring Boot / Kotlin app for tracking car fuel costs with a distraction-free UI.
Login is via GitHub OAuth2; data is stored in a local SQLite file.

## Deployment

Images are built on every push to `main` by the
[`build-and-push-image`](.github/workflows/build-image.yml) GitHub Action and
published to GHCR under a fixed tag:

```
ghcr.io/unividuell/mobility-manager:latest
```

The image is produced by the Spring Boot Maven plugin (Cloud Native Buildpacks,
no Dockerfile). The `latest` tag is always overwritten, so redeploying is a
`docker compose pull && docker compose up -d`.

### Running on the server

The container runs with the `production` Spring profile. The SQLite database
lives on the host under `/opt/unividuell/mobility-manager` and is bind-mounted
into the container at `/data`, so it survives image and container replacement.

**One-time host setup.** The buildpack run image runs as the non-root `cnb`
user (uid `1002`, gid `1000`), so the data directory must be owned by that uid —
otherwise the app cannot create the SQLite file and crashes on startup with
`SQLITE_CANTOPEN`:

```bash
sudo mkdir -p /opt/unividuell/mobility-manager
sudo chown 1002:1000 /opt/unividuell/mobility-manager
```

If a future image bumps the uid, read the correct value from the image with
`docker run --rm ghcr.io/unividuell/mobility-manager:latest id` and chown to it.

**Authenticate to GHCR** (the package is private) with a GitHub token that has
`read:packages`:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <github-username> --password-stdin
```

**Secrets** are passed as environment variables, all prefixed with
`MOBILITY_MANAGER_`. Put them in a `.env` file next to the compose file
(`chmod 600 .env`, never commit it):

```dotenv
# .env — required
MOBILITY_MANAGER_GITHUB_CLIENT_SECRET=<github-oauth-app-client-secret>

# optional: only if production uses a separate GitHub OAuth app
#MOBILITY_MANAGER_GITHUB_CLIENT_ID=<client-id>
```

### docker-compose snippet

```yaml
services:
  mobility-manager:
    image: ghcr.io/unividuell/mobility-manager:latest
    container_name: mobility-manager
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: production
      # secrets — sourced from the .env file, all MOBILITY_MANAGER_-prefixed
      MOBILITY_MANAGER_GITHUB_CLIENT_SECRET: ${MOBILITY_MANAGER_GITHUB_CLIENT_SECRET}
      #MOBILITY_MANAGER_GITHUB_CLIENT_ID: ${MOBILITY_MANAGER_GITHUB_CLIENT_ID}
    volumes:
      # host dir -> /data; the SQLite file lands at
      # /opt/unividuell/mobility-manager/mobility-manager.db
      - /opt/unividuell/mobility-manager:/data
    # The buildpack run image (run-noble-base) has bash but no curl/wget, so the
    # probe does a raw HTTP/1.0 GET to the (public) actuator health endpoint via
    # bash's /dev/tcp and checks for {"status":"UP"}.
    healthcheck:
      test: ['CMD', 'bash', '-c', 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf ''GET /actuator/health HTTP/1.0\r\n\r\n'' >&3 && grep -q UP <&3']
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s
```

Bring it up:

```bash
docker compose pull
docker compose up -d
```

> Note: the GitHub OAuth app's *Authorization callback URL* must point at the
> production host (e.g. `https://<your-domain>/login/oauth2/code/github`).
