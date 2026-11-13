# ✦ AI Keyboard for Android

An AI-powered Android keyboard with a built-in Claude AI chat panel and unlimited cloud notes — all inside the keyboard itself.

---

## Features

| Feature | Description |
|---|---|
| ⌨️ Full keyboard | QWERTY + symbols, shift, delete |
| ↑ Send to App | Commits typed text to WhatsApp, forms, etc. |
| ↓ Send to AI | Sends typed text directly to Claude as a prompt |
| ✦ AI Chat Panel | Full conversational Claude AI inside the keyboard |
| 📋 Notes Panel | Unlimited cloud notes stored in Supabase |
| 🚀 One-tap paste | Tap ↑ on any note to instantly insert it |

---

## Setup Instructions

### Step 1 — Prerequisites

- Android Studio (latest stable)
- Android device or emulator running Android 8.0+ (API 26+)
- An Anthropic API key: https://console.anthropic.com
- A free Supabase account: https://supabase.com

---

### Step 2 — Supabase Database

1. Go to **supabase.com** → Create a new project
2. Once created, go to **SQL Editor** → **New Query**
3. Paste the entire contents of `supabase_setup.sql` and click **Run**
4. Go to **Project Settings → API** and copy:
   - **Project URL** (looks like: `https://xxxx.supabase.co`)
   - **anon / public** key (long JWT string)

---

### Step 3 — Build the App

1. Open Android Studio
2. **File → Open** → select the `AiKeyboard` folder
3. Wait for Gradle sync to complete
4. Click **Run ▶** (or `Shift+F10`) to build and install on your device

---

### Step 4 — Configure the App

1. Open the **AI Keyboard** app on your device
2. Enter your **Anthropic API key**
3. Enter your **Supabase URL** and **Anon Key**
4. Tap **Save Settings**
5. Tap **Enable Keyboard** → find "AI Keyboard" in the list → toggle it ON
6. Tap **Set as Default** → select "AI Keyboard"

---

## How to Use

### Keyboard Tab (⌨)
- Type normally — text appears in the app you're using
- **↑ button** — sends the enter/submit action (like pressing Send in WhatsApp)
- **↓ button** — takes your current text and sends it to Claude as a prompt, switching to the AI panel

### AI Chat Tab (✦)
- Chat directly with Claude
- Tap **📋** to insert Claude's last response into your current text field
- Tap **copy** under any AI bubble to insert that message
- Chat history persists during the keyboard session

### Notes Tab (📋)
- Tap **+ New** to create a note
- Add a title and paste any content (scripts, cheat codes, templates, etc.)
- Tap **↑** on any note to instantly insert it into your current text field
- Notes sync to Supabase — available on any device, never expire, no limits

---

## Architecture

```
AiKeyboard/
├── app/src/main/
│   ├── java/com/aikeyboard/
│   │   ├── keyboard/
│   │   │   └── AiKeyboardService.kt   ← The IME service (main keyboard logic)
│   │   ├── ai/
│   │   │   └── ClaudeClient.kt        ← Anthropic API calls
│   │   ├── data/
│   │   │   ├── Models.kt              ← Note, ChatMessage data classes
│   │   │   ├── PrefsManager.kt        ← Encrypted API key storage
│   │   │   └── SupabaseRepository.kt  ← Notes CRUD via Supabase REST API
│   │   ├── notes/
│   │   │   └── NotesAdapter.kt        ← RecyclerView adapter for notes list
│   │   └── ui/
│   │       └── SetupActivity.kt       ← Settings/onboarding screen
│   └── res/
│       ├── layout/
│       │   ├── keyboard_view.xml      ← Full keyboard + all panels
│       │   ├── item_note.xml          ← Note list item
│       │   ├── item_ai_bubble.xml     ← AI chat bubble
│       │   └── activity_setup.xml    ← Setup screen
│       └── ...
├── supabase_setup.sql                 ← Run this in Supabase SQL Editor
└── README.md
```

---

## Customization

### Change AI model
In `ClaudeClient.kt`, change `MODEL`:
```kotlin
const val MODEL = "claude-haiku-4-5-20251001"   // Fast, cheap (default)
// const val MODEL = "claude-sonnet-4-6"         // More capable, slower
```

### Change AI personality
Edit `SYSTEM_PROMPT` in `ClaudeClient.kt` to customize how Claude responds.

### Colors / Theme
All colors are in `res/values/colors.xml`. The app uses a dark purple theme by default.

---

## Privacy Note

This keyboard only sends text to the Anthropic API when you **explicitly tap ↓** or the **Send** button in the AI panel. It does **not** log or transmit keystrokes passively. Your API keys are stored using Android's `EncryptedSharedPreferences`.

---

## Troubleshooting

| Problem | Solution |
|---|---|
| Keyboard doesn't appear | Go to Settings → Language & Input → enable AI Keyboard |
| AI returns error | Check your Anthropic API key in the app settings |
| Notes not loading | Verify Supabase URL and anon key; check you ran the SQL setup |
| Build fails | Make sure you're using Android Studio Hedgehog or newer |
