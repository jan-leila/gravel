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

## How an app works

`:routers:common` holds what every app shares:

- `RouterReceiver` takes Gravel's broadcast, writes the delivery to the
  app's **outbox** (one file per capture id, so a repeated delivery of one
  recording replaces the first), asks WorkManager to send, and acknowledges
  within the receiver's budget. An app can refuse a delivery at once
  (`RouterApp.refusal`, for one that can never be sent as the app is set
  up), with a reason Gravel shows the user.
- `OutboxWorker` sends what waits once there is a network: a sent delivery
  leaves the outbox; a refused one leaves it and is reported in a
  notification; one to retry stays, with WorkManager's backoff.
- `Http` sends with the platform's client. Cleartext goes only to a private
  address (a LAN name, a private or link-local range), and only when the app
  allows it: the self-hosted relays often have no TLS on a LAN. Everything
  else is https.
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

Gravel trusts the certificate's SHA-256 as a built-in trusted signer
(`DEFAULT_TRUSTED_ROUTER_SIGNERS` in the Notebook module), so the apps are
listed as trusted routers without a prompt. A lost key would mean a new
digest in Gravel and a reinstall of every router app.

The apps are **not** in F-Droid's main repository: F-Droid signs with its
own per-app keys, which the built-in trust would not match. They are
published as GitHub releases (Obtainium reads those) and through Gravel's
own F-Droid repository, both signed with this key.

## Building

```sh
./gradlew :routers:slack:assembleRelease
./gradlew :routers:common:testDebugUnitTest :routers:slack:testDebugUnitTest
```

`FdroidGuardrailsTest` in `:androidApp` scans these modules with the rest of
the tree, so their build files keep to the catalog and to allowed
repositories like every other module, even though F-Droid's recipe builds
`:androidApp` alone.
