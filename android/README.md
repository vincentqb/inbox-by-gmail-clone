# Inbox for Android

Native Kotlin/Compose port of the clone. It talks straight to the Gmail REST
API, so the phone needs no Mac and no server in the middle.
`gmail/Bundling.kt`, `gmail/Mime.kt` and `gmail/Unsub.kt` are line by line ports
of `app.py`'s classifiers and parsers (by way of the SwiftUI app in `ios/`);
keep the three in sync when the triage model changes. The visual system tracks
`static/style.css`, not stock Material: Material Icons rendered by codepoint
from the bundled font, Roboto in three weights, separated rounded cards with the
same borders and shadows, read mail receding to gray, the 4px unread edge and
dot, yellow pin dots, gray chips with blue icons, the red active pill in the
drawer, and the yellow mailing-list banner. `ui/Theme.kt` holds the tokens;
`MIcon` is the `<i class="material-icons">` equivalent.

## The setup wizard

The point of this build: hand someone the APK and they connect their own Gmail
without touching a terminal. First launch runs `ui/SetupWizard.kt`.

1. **Sign in with Google.** If the APK was built with `client.properties`
   present, it carries an OAuth client and the welcome screen's main button
   goes straight to a one-screen heads-up about Google's warning, then to
   Google's consent page: two taps. Everyone who uses such a build has to be
   allowed on that one Google Cloud project (a test user, or the app
   published), and the project owner can see the usage.
2. **Your own Google Cloud project.** The link under that button (or the only
   route, in a build with no baked-in client) walks through creating a
   project, enabling the Gmail API, filling in the consent screen, and creating
   a **Desktop app** OAuth client, with a button per step that opens the exact
   console page. Then paste the downloaded `client_secret.json` (or the ID and
   secret on two lines) and sign in. Nothing then depends on anyone else.

Either way Google's consent page opens in a Chrome Custom Tab, and it shows
"Google hasn't verified this app" because this app is not going through Google's
review. Advanced, then "Go to Inbox (unsafe)". The wizard says so up front.
When Google redirects back, the app pops the tab and returns to the foreground
by itself; the tab's page also says it is safe to close.

The attempt lives in `auth/AuthSession.kt` for the life of the process, not in
the wizard's composition, so a rotation or a recreated activity while the
browser is up does not close the port Google is about to redirect to. The
listener in `auth/OAuthFlow.kt` answers and ignores anything that is not its
redirect (Chrome's spare preconnects, the favicon fetch, a hit with a foreign
`state`) and only ever completes on a request carrying its own `state`, so
another app on the phone cannot end or spoof the sign-in. Waiting has a Cancel
button. `OAuthFlowTest` drives all of that on a plain JVM.

### Why the OAuth client must be "Desktop app"

The app catches the OAuth redirect on a one-shot `127.0.0.1` listener with PKCE,
exactly like the Mac server does. Google has deprecated the loopback redirect
for clients registered as **Android**, and disabled custom URI schemes for them,
steering those toward Play services and the Google Identity Services SDK. A
**Desktop app** client still accepts a loopback redirect, which keeps the flow
working from a sideloaded APK with no Play-services dependency, no hosted
redirect page, and no signing-fingerprint registration. A "Web application"
client is rejected by the wizard, because its redirect rules do not fit.

### The 7-day sign-out

A Cloud project whose consent screen is **External** and whose publishing status
is still **Testing** gets refresh tokens that expire after 7 days ([Google's
docs](https://developers.google.com/identity/protocols/oauth2#expiration)).
The app notices (`invalid_grant`) and offers **Sign in again**, while keeping
downloaded mail and local drafts available. Publishing the consent screen to
**Production** removes this Testing-specific expiry; Google can still revoke
tokens for other reasons. Publishing is separate from Google's verification
process and from building the release APK.

### Which credential to get from Google Cloud

Create a Google Cloud project, enable **Gmail API**, configure **Google Auth
Platform → Branding / Audience**, and add your Gmail address as a test user
while the app is in Testing. Under **Clients**, create an OAuth client with
application type **Desktop app** and download its client JSON.

Paste that JSON into the phone's setup wizard, then complete **Sign in with
Google** in the browser. The app obtains and refreshes the account's OAuth
tokens itself. A Google Cloud **API key** does not grant access to private Gmail;
you do not need to obtain or paste a short-lived access token manually.

## Offline mail, drafts, and Outbox

On sign-in, the Android app automatically downloads a rolling two-month window
of email. It uses UTC calendar-month arithmetic: on October 30, the window
starts on August 30 at the same UTC time. It includes Inbox, Sent, archived,
and snoozed mail, and excludes Spam, Trash, Gmail Drafts, and Chats.

Full message bodies, MIME inline images, and attachments are saved in the app's
private storage. Downloads resume from a durable checkpoint after a connection
failure or process restart. Gmail history updates keep the cache current, a
daily inventory checks for missed changes, and older mail is removed from the
downloaded window. **Settings → Offline mail** shows saved and fully downloaded
counts and offers **Sync now**. Let the first download finish while connected
before relying on airplane mode; a saved body may become readable before its
attachments finish downloading.

While offline, you can read downloaded threads and open downloaded attachments
in Inbox, Done, Sent, and Snoozed. Offline search matches ordinary words against
cached subjects, senders, previews, and text bodies; Gmail query operators work
when connected. Earlier messages in a conversation, externally hosted images,
**Show original**, and Gmail changes such as archive, pin, snooze, or unsubscribe
require a connection.

Compose and reply/reply-all save typed changes locally. **Save draft** finishes
the save before closing; the **Drafts** drawer entry lets you reopen it after
closing or restarting the app. These drafts live on this phone and are not
synced to Gmail's Drafts folder or another device. Discarding a draft requires
confirmation.

**Send** saves a frozen copy in **Outbox**, including its recipients, reply
thread, and a stable Message-ID. The app delivers it when Gmail is reachable,
including after the app has closed. Queued and rejected messages can be
returned to Drafts for editing; sent items disappear from Outbox.

Gmail does not provide an idempotent send API. If a connection fails after a
send might have reached Gmail, the app searches Sent for the Message-ID and
shows **Delivery unconfirmed** until delivery can be established. It never repeats
that uncertain send automatically. **Retry** checks Sent again and asks
for confirmation when delivery is uncertain, because a manual retry could
produce a duplicate.

Download and Outbox workers run independently of the notification switch, use
Wi-Fi or mobile data, and run periodically at Android's 15-minute minimum.
Reopening the app or reconnecting also schedules work. Android battery
restrictions can delay it; after a force-stop, open the app to resume.
Signing out, resetting setup, uninstalling, or switching Gmail accounts removes
the phone's downloaded mail and local drafts/Outbox. Signing out and resetting
show the number of unsent items before clearing them.

## Features

- Inbox with Bundles (the same keyword classifier plus `Bundle/<name>` label
  overrides), pinned shortlist merged into page one, IMPORTANT+unread float,
  date separators
- Four configurable swipe zones (short and full, each edge), set in Settings.
  Defaults: right = Done, full right = Share, left = Snooze, full left = Pin.
  The reveal color and icon swap at the zone boundary with a haptic. Snoozed and
  Done keep swipe right as Move to Inbox. Long-press for the rest.
- Snooze presets ported from `resolve_snooze`, plus pick date and time. Each
  snooze is a WorkManager job that does the label flip and posts the banner;
  foreground and the periodic worker both re-check, so a dropped job is not a
  lost snooze.
- Reader: one WebView renders the thread as message cards, embedded `cid:`
  images are served from the attachment endpoint through
  `shouldInterceptRequest`, quoted history sits behind a toggle, attachments
  open in whatever app handles the type
- Sender details, same as Gmail's: the card header shows the sender's address,
  and the caret on the recipients line opens the from, reply-to, to, cc, bcc,
  date, subject, mailed-by, signed-by and security rows. "Show original" opens
  the raw source with a Share button. `messageDetails` in `Bundling.kt` is the
  port of `message_details`.
- Reply and reply-all (never addressed to yourself), compose, threaded with
  In-Reply-To/References, sent as your display name
- Snoozed / Done / Sent views, Gmail-syntax search when connected, local search
  offline, pull to refresh, infinite scroll, the pinned-only app-bar switch,
  undo snackbar, plus local Drafts and Outbox
- Bundle sweep two ways: the `done_all` button in the bundle head, or swipe the
  bundle card right
- **Unsubscribe** (RFC 2369 / RFC 8058): an inline link on the card, a banner in
  the reader, and a long-press action. Senders that bury the opt-out in the body
  get a one-time background body scan whose verdict is cached, same as the Mac's
  `unsub_scan` table. Both surfaces confirm first, then `UnsubResolver` walks
  the same ladder as the Mac's api_unsubscribe, stopping at the first rung that
  works: the RFC 8058 one-click POST; the sender's confirmation page driven to
  completion in-app (redirects including meta-refresh and window.location, the
  confirm form with its hidden tokens, the opt-out radio, your address in the
  "which address?" box, the reason dropdown, a second confirm screen, up to four
  rounds); a one-click POST the sender never advertised, accepted only when the
  response says in words that you are off the list; `UnsubWebDriver`, which
  loads the page again in an offscreen WebView for opt-outs that only exist once
  the page's scripts have run; then the mailto route sent from your account. The
  browser opens only when all five have failed. Every hop and every subresource
  goes through the same resolve-to-public-only SSRF guard as the Mac (plain
  `http://` click trackers are followed on GET, POSTs stay https-only, which is
  why the manifest allows cleartext traffic), login forms are never submitted, a
  page reading "sorry to see you go" above a confirm button is not mistaken for
  success, and a page whose "you are unsubscribed" wording is static while a
  script does the real opt-out (FullRail, used by HungerRush) goes to the
  WebView rung instead of being believed. `UnsubResolverTest` runs the same
  fixtures the Mac and iOS ports are checked against.
- New-mail banners from a 15-minute WorkManager poll (Android's floor for
  periodic work), toggleable in Settings
- `inboxclone://thread/<id>` opens that exact thread, so a shared link or a
  notification lands on the right mail

## Not ported

Block sender, move-to-bundle filters, aliases, multi-select, undo send,
live SSE. **Send to Things 3** is Apple-only, so the same slot is a share
intent: it hands any task app the subject, the `inboxclone://` backlink and the
Gmail permalink.

## Build

Needs **JDK 17**, the Android SDK with **platforms;android-34** and
**build-tools;34.0.0**, and platform-tools for USB installation. Android Studio's
SDK Manager can install these. On macOS, JDK 17 is also available with
`brew install --cask temurin@17` or `brew install openjdk@17`.

From the repository root, with your own JDK and SDK paths:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./android/scripts/build-apk.sh
```

It creates `keystore/inbox-release.jks` on first run (gitignored, and worth
backing up together with `android/keystore.properties`: later APKs need that
same signing key to upgrade an installed copy), builds the minified release
variant, and prints the signing fingerprints and SHA-256 of
**`android/build/Inbox.apk`**. The APK runs on Android 8.0 or newer.

To bake an OAuth client into the build, copy
`android/client.properties.example` to `android/client.properties` first.
Leave it out and every user goes through the
bring-your-own-project route.

## Install on your phone

Enable **Developer options → USB debugging**, connect the phone, and accept
the USB authorization prompt. From the repository root:

```sh
"$ANDROID_HOME/platform-tools/adb" devices
"$ANDROID_HOME/platform-tools/adb" install -r android/build/Inbox.apk
```

Alternatively, copy `Inbox.apk` to the phone, open it, allow **Install unknown
apps** for the app opening the file, then install and follow the setup wizard.
The release app and the `.debug` variant install separately.

For updates, keep the signing key and use `install -r` so downloaded mail and
local drafts stay intact. If Android reports a signing-key mismatch, rebuild
with the original key; uninstalling the existing app deletes its local data.

## Verification

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease
```

The JVM suite covers database reopening, account isolation, calendar cutoffs,
paged download recovery, expired history/page tokens, large text parts and
attachments, frozen outbox contents, interrupted sends, and Sent reconciliation.
MockWebServer checks that the send transport does not replay POSTs or follow
redirects.

Before using it with your mailbox, verify on a phone: let the initial download
finish, enable airplane mode, reopen a downloaded thread and attachment, save
and reopen a draft, and queue a message to yourself. Restart Inbox while still
offline, confirm the draft and Outbox persist, then reconnect and check Sent
for delivery. Real Gmail authorization and device background scheduling require
this device check; JVM tests use fake Gmail responses.

## Layout

    app/src/main/java/com/baestheorem/inbox/
      auth/     AuthStore (encrypted client + refresh token), OAuthFlow
                (loopback PKCE + state), AuthSession (the in-flight
                attempt, process-scoped), ClientCredentials (paste parser)
      gmail/    GmailClient (REST, retry, metadata cache, history cursor),
                Bundling / Mime / Unsub / UnsubResolver (app.py ports),
                Models, Net
      data/     MailStore (the view model), MailDatabase (private offline
                index/files + drafts), OfflineSync (resumable downloads),
                OfflineMail (app-lifetime draft writer), OutboxProcessor,
                Connectivity, SnoozeStore, UnsubScanCache, UnsubWebDriver
                (the JS-page rung), SwipeConfig, Prefs
      ui/       Theme + MIcon, SetupWizard, RootScreen, MailList,
                SwipeableRow, ThreadScreen (+ ThreadHtml), ComposeScreen,
                DraftList, SnoozeSheet, SettingsScreen
      work/     MailWork (offline downloads + outbox), SnoozeWorker (wakes
                one thread), NewMailWorker (poll + banners), Notifications
