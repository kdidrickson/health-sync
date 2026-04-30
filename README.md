# terra-health

A monorepo containing two components that together form a complete health data pipeline:

- **[android/](android/)** — Kotlin Android app that reads health data directly from Health Connect and POSTs it to the service
- **[service/](service/)** — FastAPI service deployed on Vercel with Neon Postgres that stores and serves health records

---

## Prerequisites

| Tool | Notes |
|---|---|
| Android Studio (latest stable) | For building and installing the Android app |
| Python 3.11+ | For the Vercel service |
| Physical Android device, Android 8+ (API 26+) | Health Connect requires a real device |
| Samsung Health | Installed and signed into a Samsung account |
| Health Connect | Pre-installed on Android 14+; available on [Play Store](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) for older devices |
| [Neon account](https://neon.tech) | Free tier — provides a Postgres database |
| [Vercel account](https://vercel.com) | Free tier — hosts the Python service |

No Terra account, no third-party SDK, no GitHub PAT required.

---

## Step 1: Clone and configure

```bash
git clone <your-repo-url> terra-health
cd terra-health
cp android/local.properties.example android/local.properties
```

Open `android/local.properties` and fill in:

```properties
WEBHOOK_URL=https://your-app.vercel.app/webhook   # placeholder until Step 2
WEBHOOK_SECRET=<choose any random string>
```

---

## Step 2: Deploy the service

```bash
cd service/
```

1. Create a [Neon project](https://console.neon.tech) and copy the **pooled connection string** (starts with `postgresql://`).
2. Push the repo to GitHub.
3. Go to [vercel.com](https://vercel.com) → New Project → import your repo.
4. Set these environment variables in Vercel:
   - `DATABASE_URL` — your Neon pooled connection string
   - `WEBHOOK_SECRET` — the same value you chose in Step 1
5. Deploy. Note the URL (e.g. `https://terra-health-xyz.vercel.app`).
6. Update `WEBHOOK_URL` in `android/local.properties` with the real Vercel URL + `/webhook`.

Verify the service is up:

```bash
curl https://your-app.vercel.app/health
# → {"status":"ok","version":"1.0.0","counts":{}}
```

---

## Step 3: Build the Android app

1. Open the `android/` folder in Android Studio (**File → Open** → select the `android/` directory).
2. Wait for Gradle sync — all dependencies come from Maven Central and Google's Maven repo, no authentication needed.
3. **Build → Make Project** — verify zero errors before installing.

---

## Step 4: Install on your phone (no Play Store needed)

### Option A — Android Studio (easiest)

1. Enable **Developer options** on your phone:
   - Settings → About phone → Software information → tap **Build number** 7 times
   - Go back to Settings → Developer options → enable **USB debugging**
2. Connect your phone via USB; accept the "Allow USB debugging?" prompt.
3. In Android Studio, select your device from the device dropdown and click **Run (▶)**.

### Option B — ADB command line

```bash
# macOS
brew install android-platform-tools

adb devices          # verify your phone appears

# In Android Studio: Build → Build Bundle(s) / APK(s) → Build APK(s)
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

### Option C — File transfer (no computer tools)

1. Build the APK in Android Studio (same as Option B above).
2. Transfer `app-debug.apk` to your phone (email, Google Drive, or USB file transfer).
3. On your phone: Settings → Biometrics and security → Install unknown apps → enable for the transfer app.
4. Open the APK on your phone and tap **Install**.

---

## Step 5: First launch setup

1. Open **Terra Health Sync** on your phone.
2. Tap **Grant Permissions** → Health Connect permission screen → grant all permissions.
3. Tap **Sync Now** to trigger the first manual sync.
4. Verify data arrived:

```bash
curl https://your-app.vercel.app/health
# counts should be > 0 within ~30 seconds
```

---

## Step 6: Verify end-to-end

```bash
# Summary of latest record per data type
curl https://your-app.vercel.app/health/summary

# Most recent daily record
curl https://your-app.vercel.app/health/latest/daily

# WorkManager runs automatically every 24 hours in the background
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Health Connect not found on device | Install from Play Store: `com.google.android.apps.healthdata` |
| Samsung Health not syncing to Health Connect | Samsung Health → Settings → Connected services → Health Connect → enable all data types |
| Webhook 401 errors in Vercel logs | `WEBHOOK_SECRET` in `local.properties` must exactly match the Vercel environment variable |
| `/health/summary` returns empty after sync | Check Vercel → Functions tab logs for DB connection errors; confirm `DATABASE_URL` is set |
| Sync shows "No data returned" | Grant all permissions first; Samsung Health must be connected to Health Connect and have recent data |
