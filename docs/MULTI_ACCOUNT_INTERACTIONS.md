# Multi-account interactions

## State ownership

| State | Owner | Persistence |
| --- | --- | --- |
| NetEase accounts and purpose assignments | `NeteaseCookieRepository` | EncryptedSharedPreferences |
| Bilibili accounts and purpose assignments | `BiliCookieRepository` | EncryptedSharedPreferences |
| Add-account transaction | Platform cookie repository | Memory, until login succeeds or is cancelled |
| Account management dialog visibility | `SettingsScreen` | Not persisted |

## Event flow

1. Opening a platform row shows the account management dialog when at least one account exists.
2. Selecting Primary, Play history, or Streaming updates the encrypted assignment immediately.
3. Primary assignment updates browsing, search, library, and playlist clients.
4. Streaming assignment updates playback URL resolution, download resolution, and media stream Cookie headers.
5. Play history assignment updates the isolated platform history client. Eligible playback is reported without blocking playback.
6. Add account marks the next successful QR, web, SMS, or Cookie login as a new account. Cancelling clears the marker.
7. Deleting an assigned account normalizes invalid assignments back to the primary account.

## Edge cases

- Empty: the platform row opens the existing sign-in flow.
- Login error: no account is added.
- Deleted primary: the first remaining account becomes primary and invalid assignments fall back to it.
- Concurrent requests: primary, streaming, and history use separate client instances so Cookie context is not switched in place.
- Legacy upgrade: the previous single account is migrated as account 1 and assigned to all three purposes.
