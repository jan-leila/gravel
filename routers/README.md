# Gravel's first-party router apps

Gravel's Notebook turns a voice recording into text and sends it through a
routing graph the user sets up; an Android app can declare a **router** the
user can route to (`ROUTER_API.md` at the root of this repository is the
contract). The apps in this directory are Gravel's own routers, one app per
integration, each a small Android app on the shared `:routers:common`
library. They are also the worked examples of the API: an integration is a
manifest, a settings screen and one sender.

| App | Router id | Sends the text to | Needs from the recording |
| --- | --- | --- | --- |
| `slack` | `message` | a Slack channel, through an incoming webhook | nothing |
| `homeassistant` | `assist` | Home Assistant's Assist, as a typed sentence | nothing |
| `vikunja` | `task` | a task in a Vikunja project, titled with the text | the recording time, for the description |
| `mastodon` | `post` | a status on a Mastodon account, followers-only by default | nothing |
| `osmnote` | `note` | an OpenStreetMap note where the recording was made | the location; a recording without one is refused |
| `signal` | `signal` | a Signal message, through a signal-cli-rest-api relay the user runs | nothing |
| `actual` | `transaction` | a transaction in Actual Budget, through an actual-http-api relay the user runs | the recording time, for the date |
| `email` | `mail` | an email through an SMTP server the user sets up (a Proton Bridge, for Proton) | the recording time, for the subject and date |

## How an app works

`:routers:common` holds what every app shares:

- `RouterReceiver` takes Gravel's broadcast, checks that the permission
  guarding it is Gravel's own (`GravelSigners`: defined by
  `com.anopticlabs.gravel` at signature level, in an app signed by a key
  Gravel ships under, or by the router app's own key for local builds;
  the system checks the permission by name alone, which an app could
  define itself while Gravel is absent), writes the delivery to the app's
  **outbox** (one file per capture id, so a repeated delivery of one
  recording replaces the first), asks WorkManager to send, and acknowledges
  within the receiver's budget. An app can refuse a delivery at once
  (`RouterApp.refusal`, for one that can never be sent as the app is set
  up: a missing setting, a bad address, no amount to book), with a reason
  Gravel shows the user.
- `OutboxWorker` sends what waits once there is a network: a sent delivery
  leaves the outbox; a refused one (an answer no retry changes: a bad
  token, a redirect, no such API at the address, a certificate the pin
  does not match) is set aside with its reason and reported in a
  notification, which the settings screen asks permission for; one to
  retry stays, with WorkManager's backoff, and is set aside the same way
  after twelve runs. The settings screen lists what was set aside, with
  the text and the reason, to try again or discard: Gravel keeps no copy
  of the text once a router took it.
- `Http` sends with the platform's client. Cleartext goes only to a private
  address (a LAN name or a name with no dot, a private, shared (100.64/10)
  or link-local range), and only when the app allows it: the self-hosted
  relays often have no TLS on a LAN. Everything else is https.
- `Settings` keeps the app's few values in its preferences, secrets sealed
  with a key the Android Keystore holds for the app (`Secrets`);
  `SettingsScreen` is the one screen each app has, with a test send.

An integration adds a module under `routers/` with its manifest (the
receiver, guarded by Gravel's permission, with the `id`, `label`,
`description` and `needs` meta-data), an `Application` that is a
`RouterApp`, a `Sender` built from a pure request builder (tested), and a
settings activity that shows `SettingsScreen` with its fields. Copy `slack`.

## Signing, trust and distribution

The apps are signed with one key of their own, generated once and kept
outside the tree (`*.jks` is ignored):

```sh
keytool -genkeypair -keystore routers.jks -alias gravel-routers -keyalg RSA -keysize 4096 -validity 10000
apksigner verify --print-certs slack-release.apk | grep SHA-256   # the digest Gravel trusts
```

The build signs release only when the environment names the keystore
(`ROUTER_KEYSTORE`, `ROUTER_KEYSTORE_PASSWORD`, `ROUTER_KEY_ALIAS`,
`ROUTER_KEY_PASSWORD`), and packages release unsigned otherwise, so a fresh
clone and CI build without it. `LOCAL_RELEASE_BUILD=true` in
`local.properties` signs release with the debug key, as it does for Gravel.

Once the key exists, its certificate's SHA-256 goes into Gravel's built-in
trusted signers (`DEFAULT_TRUSTED_ROUTER_SIGNERS` in the Notebook module,
with a comment naming these apps), so the apps are listed as trusted
routers without a prompt; until then the user trusts the certificate once
in Gravel's Text Routing settings. A lost key would mean a new digest in
Gravel and a reinstall of every router app.

The trust runs the other way too: the apps' shared receiver takes a
delivery only when the permission guarding it is defined by Gravel's
package, at signature level, in an app signed by one of the keys in
`GravelSigners.TRUSTED` (F-Droid's key for Gravel) or by the router app's
own key. A Gravel signed with a new key needs its digest added there
before the router apps take its deliveries.

The apps are **not** in F-Droid's main repository: F-Droid signs with its
own per-app keys, which the built-in trust would not match. They are
published as GitHub releases (Obtainium reads those) and through Gravel's
own F-Droid repository, both signed with this key, by the
`Router apps release` workflow (`.github/workflows/routers-release.yml`)
when a `routers-v*` tag is pushed. The workflow needs the apps' keystore and
the repository's own index-signing keystore as secrets (named at the top of
the workflow file), and the `fdroid-repo` branch to exist (an empty branch
with GitHub Pages serving it; `fdroid update --create-metadata` fills it).
Users add `https://<owner>.github.io/<repository>/repo` to their F-Droid
client, or the GitHub releases page to Obtainium.

## Releasing

The eight apps share one version, in `routers/version.properties`, which
every router build file reads. A release commit raises `versionCode` by
one and sets `versionName` to the version the tag will name, and the tag
is `routers-v<versionName>` on that commit. The workflow refuses a tag that
does not match the file, an APK whose version differs from it, and a
`versionCode` not above the one the F-Droid repository already indexes
for that package: F-Droid clients offer an update by `versionCode` alone,
so a release that re-shipped the last one would never reach them.

## Building

```sh
./gradlew :routers:slack:assembleRelease
./gradlew :routers:common:testDebugUnitTest :routers:slack:testDebugUnitTest
```

`FdroidGuardrailsTest` in `:androidApp` scans these modules with the rest of
the tree, so their build files keep to the catalog and to allowed
repositories like every other module, even though F-Droid's recipe builds
`:androidApp` alone.
