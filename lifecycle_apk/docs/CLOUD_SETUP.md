# 🌐 Community Cloud Learning Setup Guide
## Share your bot's learnings with the community!

This guide takes **2-3 minutes** to set up a shared community database.

---

## Step 1: Create Supabase Project (1 min)

1. Go to **https://supabase.com**
2. Click **"Start your project"** → Sign up with GitHub (fastest)
3. Click **"New Project"**
   - Name: `lifecyclebot-community` (or anything)
   - Password: Generate a strong one (save it!)
   - Region: Pick closest to you
4. Click **"Create new project"** → Wait ~1 minute

---

## Step 2: Run the Schema (30 sec)

1. In your Supabase dashboard, click **"SQL Editor"** (left sidebar)
2. Click **"New query"**
3. Copy the ENTIRE contents of `docs/SUPABASE_SCHEMA.sql`
4. Paste into the editor
5. Click **"Run"** (or Ctrl+Enter)
6. You should see "Success" ✅

---

## Step 3: Get Your Keys (30 sec)

1. Click **"Project Settings"** (gear icon, bottom left)
2. Click **"API"** in the sidebar
3. Copy these two values:

```
Project URL:  https://xxxxxxxxxxxxx.supabase.co
anon public:  eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xxxxx...
```

---

## Step 4: Update the App (30 sec)

Open `app/src/main/kotlin/com/lifecyclebot/engine/CloudLearningSync.kt`

Find lines 42-43:
```kotlin
private const val SUPABASE_URL = "https://lifecyclebot-community.supabase.co"
private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

Replace with YOUR values from Step 3.

---

## Step 5: Share with Community! 🎉

**Option A: Keep it private**
- Only your devices will sync
- Good for testing

**Option B: Share with everyone (recommended)**
- Post your Project URL and anon key in the Telegram group
- Other users update their `CloudLearningSync.kt`
- Everyone's bots learn from each other!

> ⚠️ The `anon` key is safe to share - it only allows insert/read on specific tables via RLS policies.

---

## How It Works

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Your Bot      │     │   Supabase DB    │     │  Other Bots     │
│                 │     │                  │     │                 │
│  Learn from     │────▶│  contributions   │◀────│  Learn from     │
│  your trades    │     │  table           │     │  their trades   │
│                 │     │                  │     │                 │
│  Download       │◀────│  aggregated_     │────▶│  Download       │
│  community      │     │  weights table   │     │  community      │
│  knowledge      │     │                  │     │  knowledge      │
└─────────────────┘     └──────────────────┘     └─────────────────┘
```

**Privacy:**
- ✅ Shared: Feature weights, pattern win rates, trade counts
- ❌ Never shared: Wallet addresses, individual trades, P&L amounts

---

## Troubleshooting

**"Upload failed" in logs:**
- Check your internet connection
- Verify SUPABASE_URL and SUPABASE_ANON_KEY are correct
- Make sure you ran the SQL schema

**"No community data" on startup:**
- Database is new, needs more contributions
- After 3+ bots contribute 20+ trades each, aggregation kicks in

**Want to reset and start fresh?**
- In Supabase SQL Editor, run: `TRUNCATE contributions, aggregated_weights;`

---

## Questions?

The community database makes ALL bots smarter over time.
More users = better patterns = more profits for everyone! 🚀
