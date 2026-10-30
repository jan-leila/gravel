# Notebook router API

Gravel's Notebook turns a voice recording into text and sends that text
through a routing graph the user sets up under Settings > Notebook > Text
Routing. An Android app can declare a **router**: a destination the user can
pick in that graph. When the user routes text to it, Gravel hands the app the
complete transcript and the time the recording started. Nothing flows the
other way: Gravel sends and never receives.

This document is the contract. The names in it are public API: a change to
one would break installed apps, so a new field gets a new name and the
version number moves.

## Declaring a router

Declare an exported broadcast receiver that filters on the action and is
guarded by Gravel's permission:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.tasks">
    <!-- Reference the permission; never define it (see "The permission" below). -->
    <application>
        <receiver
            android:name=".NotebookRouter"
            android:exported="true"
            android:permission="com.anopticlabs.gravel.permission.ROUTE_NOTEBOOK_TEXT">
            <intent-filter>
                <action android:name="com.anopticlabs.gravel.notebook.action.ROUTE_TEXT" />
            </intent-filter>
            <meta-data android:name="com.anopticlabs.gravel.notebook.router.id" android:value="tasks" />
            <meta-data android:name="com.anopticlabs.gravel.notebook.router.label" android:value="Add as a task" />
            <meta-data android:name="com.anopticlabs.gravel.notebook.router.description" android:value="Creates a task from what you said" />
        </receiver>
    </application>
</manifest>
```

- `id` names the router within your app's namespace (below): 1 to 32
  lowercase letters, digits or dashes. It is required; a receiver without
  one is not a router. Keep it stable across releases: the user's routing
  refers to it, and a receiver that starts declaring another id is no
  longer the router the user picked.
- `label` (up to 64 characters) is what the router is called in Gravel's
  settings; without it Gravel shows the receiver's label. `description` (up
  to 200 characters) is a line shown under it. Both are plain text.
- One app may declare several routers, one receiver each, with distinct
  ids.
- Gravel lists a receiver only when it is exported **and** guarded by exactly
  this permission. A receiver without the guard is not a router: any app on
  the phone could broadcast to it in Gravel's name. A receiver guarded by a
  different permission is skipped by the system without a word, which Gravel
  could not tell from "it ran and did not answer".

## Namespaces

Gravel's routing refers to every router by an `id` and a `namespace`.
Routers the user makes in Gravel's settings have no namespace: the key is
absent. A router your app declares is in the namespace of the certificate
that signed your app: the lowercase hex SHA-256 of the certificate's DER bytes,
as `apksigner verify --print-certs` prints it. So:

- A router is either one of Gravel's own, with no namespace, or an app's,
  with a certificate digest as its namespace; Gravel refuses a stored
  routing with any other shape. So nothing an app declares can name,
  replace or collide with one of Gravel's own routers, or with a router of
  an app signed by another key.
- Two apps signed by the same key share a namespace, so their router ids
  must not collide; that is the signer's responsibility.
- An app signed by several certificates has no single namespace, and its
  receivers are not listed as routers.
- At every delivery Gravel checks that the receiver still declares the id
  the user picked and that the app is still signed by that namespace (a
  rotated key answers for it through the platform's signing lineage).
  Otherwise the recording is flagged and nothing is sent.

In Gravel's settings every router an installed app declares is listed with
the app's name and its namespace under it, so two routers with the same
label from different apps can be told apart.

## The permission

`com.anopticlabs.gravel.permission.ROUTE_NOTEBOOK_TEXT` is defined by Gravel
with `protectionLevel="signature"`. Only an app signed with Gravel's key can
hold it, so a receiver guarded by it takes broadcasts from Gravel and from
nothing else. Your app does not request it and must not define it:

- Before Gravel is installed the permission is undefined, and Android denies
  every sender to a receiver guarded by an undefined permission. Your receiver
  is safe either way.
- An app that defines a permission of that name itself blocks Gravel from
  installing on that phone (Android refuses a second definition of a
  permission from a different signer), and once Gravel is installed first,
  your app would not install. Reference it, never define it.

## Trust

Gravel forwards text only to apps the user trusts. An app is trusted when its
signing certificate is on Gravel's built-in list or when the user marked the
app as trusted under Settings > Notebook > Text Routing: picking one of
its routers, or the Trust button under Apps with routers, shows the app's
certificate SHA-256 (its namespace) and asks for the trust. The user's trust
is for that app, not for every app from the same signer. An app stays
trusted across key rotation (the platform's signing lineage). Trust is
checked again at
every delivery, so a package replaced under the same name, or a trust the
user withdrew, stops deliveries at once.

## The delivery

Gravel sends one **explicit ordered broadcast** to the receiver, with the
foreground flag set, carrying these extras and nothing else:

| Extra | Type | Meaning |
| --- | --- | --- |
| `api_version` | int | `1` for this document. |
| `capture_id` | String | Identifies the recording. The same recording can be delivered more than once (below); treat a repeated id as the same text. |
| `recorded_at` | String | When the recording started, as ISO-8601 with the offset of the zone it was recorded in, to the second: `2026-10-29T14:03:11-04:00`. This is the moment the user pressed record, not when Gravel processed the recording. |
| `text` | String | The complete transcript, exactly as transcribed. |

The receiver **acknowledges** by setting the result code to
`Activity.RESULT_OK`. Gravel waits up to 12 seconds for the broadcast to
complete; a receiver that does not set `RESULT_OK`, or that runs out of time,
is treated as not having taken the text, and Gravel tries that recording
again on a later run, up to its usual attempt limit. A receiver gets the
system's foreground broadcast budget (about 10 seconds): acknowledge first
and hand longer work to a service or a job.

```kotlin
class NotebookRouter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra("api_version", 0) != 1) return
        val id = intent.getStringExtra("capture_id") ?: return
        val recordedAt = intent.getStringExtra("recorded_at") ?: return
        val text = intent.getStringExtra("text") ?: return
        // Persist, then acknowledge; keep this quick.
        TaskStore(context).addFromNotebook(id, recordedAt, text)
        resultCode = Activity.RESULT_OK
    }
}
```

A delivery can be repeated: Gravel removes a recording from its queue only
after the acknowledgement arrives, and an app end or a storage failure
between the two makes it deliver again on the next run. `capture_id` is the
key to recognize that with.

## What Gravel does not do

- It does not take text back. A router that wants to hand text on to
  another router has no way to do so in this version.
- It does not tell the receiver which router in the user's graph delivered,
  what the user named it, or anything about the recording beyond the extras
  above (no length, source or zone fields).
- It does not retry a delivery that was acknowledged, and it cannot send the
  text again later on the app's request.
