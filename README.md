# mobility-manager

Spring Boot / Kotlin app for tracking car fuel costs with a distraction-free UI.
Sign-in comes from the auth lib (see [Auth lib](#auth-lib)): the organisation's GitHub App in
production, a test-user picker locally. Data is stored in a local SQLite file.

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

Deployment lives in `deploy/` (`compose.prod.yaml`, `.env.example`, `update.sh`). The app
runs behind the shared **edge-caddy** (TLS + `mobility.unividuell.org` routing); it publishes
**no host port** and joins the external `edge` network as `container_name: mobility-manager`.

Server dir: **`/opt/unividuell/mobility-manager/`**. The SQLite DB lives under `./data/`
(bind-mounted to `/data`), so it survives image/container replacement.

**One-time host setup.** The buildpack run image runs as the non-root `cnb` user
(uid `1002`, gid `1000`), so the data dir must be owned by that uid — otherwise the app
crashes on startup with `SQLITE_CANTOPEN`:

```bash
sudo mkdir -p /opt/unividuell/mobility-manager/data
sudo chown -R 1002:1000 /opt/unividuell/mobility-manager/data
```

Authenticate to GHCR (private package) with a token that has `read:packages`:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <github-username> --password-stdin
```

### Bootstrap / update

```bash
mkdir -p /opt/unividuell/mobility-manager && cd /opt/unividuell/mobility-manager
curl -fsSL https://raw.githubusercontent.com/unividuell/mobility-manager/main/deploy/update.sh -o update.sh && chmod +x update.sh
./update.sh          # fetches compose + .env template, then stops
# edit .env: MOBILITY_MANAGER_GITHUB_CLIENT_SECRET (the GitHub App's client secret)
./update.sh          # ensures edge net, pulls, starts
```

> The shared edge-caddy stack must be up (it owns 80/443 + TLS). The organisation's GitHub App
> (client ID in `application-production.yaml`) must list the callback URL
> `https://mobility.unividuell.org/login/oauth2/code/github`.

## Auth lib

Sign-in, CSRF and logout come from `org.unividuell:auth-spring-boot-starter`
(`/opt/unividuell/projects/auth-spring-boot-starter`). It is not on Maven Central: the build
reads it from `maven-repo/`, which is committed. The app provides the lib's one hook,
`AccountProvisioner` (`AppUserService`).

| | locally (no profile) | tests (profile `test`) | production |
|---|---|---|---|
| `/login/start` | test-user picker | GitHub | GitHub |

To take a new build of the snapshot, replace it in place and commit:

```bash
rm -rf maven-repo/org/unividuell/auth-spring-boot-starter
(cd /opt/unividuell/projects/auth-spring-boot-starter && ./mvnw -B deploy -DskipTests -Dmaven.install.skip=true -DaltDeploymentRepository=app::file://$OLDPWD/maven-repo)
```

The repository's `updatePolicy=always` makes a redeployed snapshot win over the copy in `~/.m2`
and in CI's Maven cache.
