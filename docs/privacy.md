# Brook privacy policy

Effective November 9, 2026.

Brook is an Android app that reads feeds from a server you choose: freshgo or another server that speaks the Google Reader API. Questions about this policy can be sent to [denis@evsyukov.org](mailto:denis@evsyukov.org).

## Data on the device

For every account Brook stores the server address, the user name and the API token the server returns at sign-in. The token is encrypted with a key held by Android Keystore. Brook does not store your password.

Brook also stores what it downloads from the server: subscriptions, categories, labels, entries with their read and starred state, pictures of entries, feed icons and the texts of full-text articles. Changes you make without a network wait on the device until the server accepts them. Reader settings and the state of push stay on the device too.

If you use translation, Brook stores translated titles and HTML separately from the originals, identified by account, entry, selected source, source fingerprint and target language. Your translation language and consent choice are stored per account. Brook does not store the server's translation-provider key.

## Data sent over the network

Brook has no developer-operated service. It connects to these places only:

- **Your server.** At sign-in Brook sends the user name and the API password to get a token. After that it sends the token with every request, along with the changes you make: read and starred state, labels, subscriptions you add, rename or remove, and the feeds of an OPML file you import. The operator of that server decides how it stores this data; read its policy if you do not run the server yourself.
- **The translation service selected by your server administrator, through your server, only if you consent and request translation.** Brook sends the article identifier, target language and revision/progress to your reader server using its existing API authorization. The server sends parts of the article it already holds to its configured translation service, which may charge the server operator and handles data under its own policy. Brook downloads the translation from your server; it does not connect to the AI provider, forward reader credentials to it or upload locally extracted full text. Translation is not automatic during sync or prefetch. There is no silent fallback to another provider.
- **Sites of the entries.** Brook downloads pictures of entries, feed icons and, for full text, the page of an entry from the sites that publish them. These requests carry no account data, but the site operator receives your IP address and ordinary request information. Pages shown in the reader can load other content from their sites in the same way.
- **A UnifiedPush distributor, if you turn on push.** Brook registers with the distributor app you pick, such as ntfy, and sends the endpoint and encryption keys it gets to your server. The server then sends notices about new and changed entries through that distributor. The notices are encrypted for Brook.

Release builds of Brook accept HTTPS only, so all of these connections are encrypted in transit.

Links you open go to the browser or another app you choose. Sharing an entry passes its title and address to the app you pick in the Android share sheet. Notifications about new entries are made on the device.

## Retention and control

Read entries stay on the device for the number of days set in the settings (30 by default); starred entries stay until you unstar them. Removing an account deletes its data from the device and asks its server to forget the push endpoint. Uninstalling Brook removes all of its data. Android backup and device-to-device transfer are turned off for Brook. Data on your server stays there until you delete it on the server.

Translation options in the reader overflow clear the account's local translation cache. Cached translations are also deleted with their entry/account. They do not overwrite original or extracted text. Clearing the device cache does not delete translations kept by the reader server or data retained by its translation provider. Cancelling a translation does not guarantee cancellation or refund of a provider request already accepted.

Brook contains no advertising, analytics or crash reporting. Google Play, F-Droid and Android can process installation, update and device information under their own terms; that is separate from Brook.

This page will be updated when the app's data handling changes.
