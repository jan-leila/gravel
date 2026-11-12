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

Six of the apps also **list** routers (`ROUTER_API.md`, Listing routers),
each one more destination the user can route to on its own:

| App | Lists a router for | From | Shown to Gravel |
| --- | --- | --- | --- |
| `vikunja` | every project | the Vikunja API, on Refresh | until unticked |
| `actual` | every open account of the budget | the relay, on Refresh | until unticked |
| `slack` | every channel the app's bot was added to, posted to with a bot token | the Slack API, on Refresh | until unticked |
| `signal` | every contact of the relay's account | the relay, on Refresh | only once ticked |
| `email` | every contact the user adds in the app | the app's settings | always |
| `homeassistant` | every device action the user adds in the app: one action, its entities, and the data field the words go in, if any | the app's settings | always |

The declared router of each of these keeps sending to the default set in
the app (project, account, webhook, recipients, address); its setting is
optional now, and a delivery to it is refused while it is empty.

## How an app works

`:routers:common` holds what every app shares:

- `RouterReceiver` takes Gravel's broadcast, checks that the permission
  guarding it is Gravel's own (`GravelSigners`: defined by
  `com.anopticlabs.gravel` or `com.anopticlabs.gravel.signed`, Gravel's two
  listings, at signature level, in an app signed by a key
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
- `RouterList` keeps the app's listed routers, each with what its sender
  needs (the service's id for the project, an address) in values Gravel
  never sees, and `RouterListProvider` lists the shared ones to Gravel,
  answering only under Gravel's own permission, as the receiver does. A
  delivery names its listed router, and the receiver refuses one for a
  router no longer shared. An app gives its listed routers ids of its own
  prefix (`LISTED_PREFIX`), as all the apps share one namespace.
- `Settings` keeps the app's few values in its preferences, secrets sealed
  with a key the Android Keystore holds for the app (`Secrets`);
  `SettingsScreen` is the one screen each app has, with a test send.

An integration adds a module under `routers/` with its manifest (the
receiver, guarded by Gravel's permission, with the `id`, `label`,
`description` and `needs` meta-data), an `Application` that is a
`RouterApp`, a `Sender` built from a pure request builder (tested), and a
settings activity that shows `SettingsScreen` with its fields. Copy `slack`.
An app that lists routers adds the list meta-data and the provider to its
manifest, a `RouterList`, and a `RouterListSource` for its screen: fetched
from the service (`vikunja`) or declared by the user (`email`). The email
app resolves every router's recipient in `emailRecipient`, the place a
router that picks the recipient from the words would join.

## Signing, trust and distribution

The apps are signed with Gravel's release key, in the same workflow run that
signs Gravel (`.github/workflows/release.yml`), so the whole release carries
one certificate. Gravel's built-in trusted signers
(`DEFAULT_TRUSTED_ROUTER_SIGNERS` in the Notebook module) hold that
certificate's SHA-256, so the apps are listed as trusted routers without a
prompt; a user who installs a build signed by another key trusts its
certificate once in Gravel's Text Routing settings. A lost key would mean a
new digest in Gravel and a reinstall of every app.

The build signs a router app's release only when the environment names a
keystore (`ROUTER_KEYSTORE`, `ROUTER_KEYSTORE_PASSWORD`, `ROUTER_KEY_ALIAS`,
`ROUTER_KEY_PASSWORD`), and packages it unsigned otherwise, so a fresh clone
and CI build without it; the release workflow builds them unsigned and signs
them afterwards. `LOCAL_RELEASE_BUILD=true` in `local.properties` signs
release with the debug key, as it does for Gravel.

The trust runs the other way too: the apps' shared receiver takes a
delivery only when the permission guarding it is defined by Gravel's
package, at signature level, in an app signed by one of the keys in
`GravelSigners.TRUSTED` (F-Droid's key for Gravel) or by the router app's
own key, which on a release is Gravel's own. A Gravel signed with another
new key needs its digest added there before the router apps take its
deliveries.

The apps are **not** in F-Droid's main repository: F-Droid signs with its
own per-app keys, which the built-in trust would not match. Each Gravel
release carries all eight APKs as assets, named
`gravel-router-<name>-<version>.apk` and listed in the release's
`.sha256` file. Each app has its own application id,
`com.jan_leila.apps.<name>` (`slack`, `homeassistant`, `vikunja`,
`mastodon`, `osmnote`, `signal`, `actual`, `email`), so they install beside
Gravel and each other. In Obtainium, add the repository's releases page once
per app with an APK filter on the app's file name (for example
`gravel-router-slack-`), so each app updates on its own.

## Releasing

The eight apps carry Gravel's version, from `androidApp/version.properties`
(every router build file reads it), and are built, checked and published
by the Gravel release (README, Releasing). The workflow refuses a release
whose router APKs do not carry the version, the application id or the one
signing certificate.

## Building

```sh
./gradlew :routers:slack:assembleRelease
./gradlew :routers:common:testDebugUnitTest :routers:slack:testDebugUnitTest
```

`FdroidGuardrailsTest` in `:androidApp` scans these modules with the rest of
the tree, so their build files keep to the catalog and to allowed
repositories like every other module, even though F-Droid's recipe builds
`:androidApp` alone.
