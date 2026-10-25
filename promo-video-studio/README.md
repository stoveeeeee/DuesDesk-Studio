# DuesDesk Promo Video Studio

Automated Node + Playwright + Remotion + FFmpeg pipeline for generating professional promotional videos.

## 🎬 What This Does

Creates two videos:
1. **90-second launch trailer** (`duesdesk-launch-trailer.mp4`) - Full feature showcase
2. **15-second hero loop** (`duesdesk-hero-loop.mp4`) - Quick teaser

## 🛡️ Safety First

- **NEVER touches real user data**
- All demo data stored in `./demo-store.json` (gitignored)
- Spring Boot runs with `STORE_FILE_PATH=./demo-store.json` (isolated)
- No access to `~/Library/Application Support/DuesDesk`, `%LOCALAPPDATA%\DuesDesk`, or `~/.fraternity-dues`

## 📋 Prerequisites

1. **Java 17+** (for Spring Boot)
2. **Node.js 18+** and npm
3. **FFmpeg** (for video encoding)
   - macOS: `brew install ffmpeg`
   - Windows: Download from https://ffmpeg.org/download.html
4. **Maven** (to build DuesDesk first)

## 🚀 Quick Start

```bash
# 1. Build DuesDesk first
cd .. && mvn clean package -DskipTests && cd promo-video-studio

# 2. Install dependencies
npm install

# 3. Generate demo data (72 members, realistic stats)
npm run seed

# 4. Capture 8 scenes with Playwright (automated browser recording)
npm run capture

# 5. Render videos with Remotion
npm run render:trailer
npm run render:teaser

# 6. Encode finals with FFmpeg (H.264 + AAC for universal playback)
npm run encode

# Or run the entire pipeline at once:
npm run build
```

## 📁 Output Files

- `./duesdesk-launch-trailer.mp4` - 90-second full trailer (1920x1080, 60fps, H.264)
- `./duesdesk-hero-loop.mp4` - 15-second teaser (1920x1080, 60fps, H.264)

Both encoded with:
- Codec: H.264 (libx264)
- Pixel format: yuv420p
- CRF: 16 (high quality)
- Audio: AAC 192kbps (subtle ambient track for QuickTime compatibility)
- Fast start flag (web-optimized)

## 🎥 What Gets Captured

### Scene 1: Dashboard & Member Ledger
- Stats display ($48,250 collected, 91.4% rate)
- "Needs attention" queue
- Full-screen member review with sticky headers
- Bulk tools (collapsed state with scroll retention)

### Scene 2: Bank Reconciliation
- Match "Susan Walding" deposit to "Conner Stover"
- "Remember this payer name" checkbox
- Auto-suggestion demo

### Scene 3: SMS & Pay Link Reminders
- SMS template with `{{venmo_link}}` and `{{pay_links}}`
- Zero-Twilio reminders (Messages/Phone Link, Email, Copy)

### Scene 4: Payment Plans & Clearance
- Printable payment plan agreement
- Social clearance rules (tolerance, plans, linked rent)
- Expiring manual vouch
- Privacy-first door list (name | clearance state only, no balances)

### Scene 5: Housing & Cash Flow
- Linked house rent roster
- Cash flow calendar
- Spending calculator + IHQ tracker
- Test $3,200 event

### Scene 6: Chair Budgets
- Color-coded budget cards (Social, Recruitment, Brotherhood, House)
- Receipt upload (auto-resizes to 1600px JPEG)
- Auditable dues credit to member

### Scene 7: Reports & Rollover
- Anonymous meeting packet
- Encrypted handoff ZIP
- "Start a New Semester" (archive, consolidate, clear vouches, reset budgets)

### Scene 8: Security & Cloud Snapshots
- Cloud sync buttons (iCloud, OneDrive, Dropbox, Google Drive)
- Daily AES-GCM encrypted snapshots
- macOS Keychain / Windows DPAPI protection

## 🎨 Video Composition

### Intro (0–6s)
- "Stop losing $4,000/yr to Greek billing fees."
- "Stop spending 6 hours every Sunday in broken spreadsheets."
- "Meet DuesDesk."

### Scene Framing
- Sleek desktop window frame over dark gradient (`#090D16` → `#111827`)
- Spring-based camera zooms (scale 1.0 → 1.35)
- Glassmorphic lower-thirds with title + subtitle per scene

### Outro (Last 6s)
- DuesDesk logo
- *0% Fees • Mac & Windows Native • Multi-App Pay Links • AES-GCM Encrypted*
- **"Take Command of Your Chapter's Finances."**

## 🔧 Troubleshooting

**Spring Boot won't start:**
- Ensure you've built DuesDesk first: `cd .. && mvn clean package`
- Check the JAR path in `capture-scenes.mjs` matches your build output

**FFmpeg not found:**
- Install FFmpeg: `brew install ffmpeg` (macOS) or download from ffmpeg.org (Windows)
- Verify: `ffmpeg -version`

**Videos play as black screen in QuickTime:**
- This should not happen if you use `npm run encode`
- The encoding script adds a subtle ambient audio track specifically to fix this

**Playwright browser won't launch:**
- Run `npx playwright install chromium`

## 📦 File Structure

```
promo-video-studio/
├── package.json                 # Dependencies & scripts
├── seed-demo-data.mjs          # Generates demo-store.json
├── capture-scenes.mjs          # Playwright automation
├── remotion.config.ts          # Remotion settings
├── tsconfig.json               # TypeScript config
├── encode-finals.mjs           # FFmpeg encoding
├── src/
│   ├── Root.tsx                # Remotion entry point
│   └── PromoVideo.tsx          # Main composition
├── recordings/                 # Playwright .webm captures (gitignored)
├── demo-store.json            # Isolated demo data (gitignored)
├── duesdesk-launch-trailer.mp4  # Final 90s video
└── duesdesk-hero-loop.mp4      # Final 15s teaser
```

## ⚙️ Configuration

Edit `capture-scenes.mjs` to adjust:
- Scene durations
- Mouse movement speeds
- Viewport size (default: 1920x1080@2x)

Edit `src/PromoVideo.tsx` to adjust:
- Camera zoom intensity
- Lower-third animations
- Color scheme
- Typography

## 🎯 Next Steps

After generating videos:
1. Upload to hosting (Vimeo, YouTube, etc.)
2. Embed hero loop on landing page
3. Use trailer for social media campaigns
4. Create GIF variants for email signatures

---

**Made with ❤️ for chapters tired of paying GreekBill's $3,500/semester fees.**
