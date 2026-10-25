# DuesDesk Video Production Pipeline Architecture

This document explains how the automated video production pipeline works.

## 🎯 Pipeline Overview

```
┌─────────────────┐
│  1. Seed Data   │  seed-demo-data.mjs
│  demo-store.json│  ─── Generates 72 members, realistic stats
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  2. Boot Server │  Spring Boot + isolated store file
│  localhost:8080 │  ─── STORE_FILE_PATH=./demo-store.json
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 3. Capture      │  capture-scenes.mjs (Playwright)
│ recordings/*.webm│ ─── 8 scenes at 1920x1080@2x + visual cursor
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 4. Compose      │  Remotion (React + spring animations)
│ PromoVideo.tsx  │  ─── Intro + 8 scenes + Outro + lower-thirds
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 5. Render       │  Remotion CLI
│ *-raw.webm      │  ─── Exports to WebM (high quality)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 6. Transcode    │  FFmpeg (encode-finals.mjs)
│ *-intermediate.mp4│─── WebM → MP4 (H.264, yuv420p, 60fps)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 7. Final Encode │  FFmpeg + ambient audio
│ duesdesk-*.mp4  │  ─── Adds AAC audio for QuickTime compatibility
└─────────────────┘
```

## 🔒 Data Safety Architecture

### Isolation Layers

1. **Environment Variable Override**
   ```bash
   STORE_FILE_PATH=./promo-video-studio/demo-store.json
   ```
   Forces Spring Boot to use isolated demo data instead of default locations

2. **Default Locations (NEVER TOUCHED)**
   - ❌ `~/Library/Application Support/DuesDesk/store_data.json` (macOS)
   - ❌ `%LOCALAPPDATA%\DuesDesk\store_data.json` (Windows)
   - ❌ `~/.fraternity-dues/store_data.json` (legacy)

3. **Git Ignore Protection**
   - `/promo-video-studio/` added to main `.gitignore`
   - `demo-store.json` added to studio `.gitignore`
   - All video outputs gitignored

### Demo Data Generation

[`seed-demo-data.mjs`](seed-demo-data.mjs) generates:
- 72 members across 4 status types (Paid in Full, Payment Plan, Delinquent, Manual Vouch)
- Realistic balances, payments, and charges
- Special "Conner Stover" member for Scene 2 reconciliation demo
- 18 residents with room numbers and rent rates
- 4 color-coded chair budgets
- Pending flow entries (GreekBill, Venmo, Bank CSV)
- IHQ semester bill configuration

**Total stats:**
- ~$48,250 collected
- ~$8,750 outstanding  
- ~$1,850 past due
- 91.4% collection rate

## 🎬 Scene Capture (Playwright)

### Visual Cursor Implementation

[`capture-scenes.mjs`](capture-scenes.mjs) injects a custom cursor overlay:

```javascript
await page.addStyleTag({
  content: `
    #demo-cursor {
      position: fixed;
      width: 24px; height: 24px;
      border: 2px solid #3b82f6;
      border-radius: 50%;
      background: rgba(59, 130, 246, 0.2);
      pointer-events: none;
      z-index: 999999;
      transition: all 0.12s cubic-bezier(0.4, 0.0, 0.2, 1);
    }
  `
});
```

**Features:**
- Smooth easing (`cubic-bezier(0.4, 0.0, 0.2, 1)`)
- Click ripple animations
- Blue accent color matching brand
- Non-interactive (pointer-events: none)
- Always on top (z-index: 999999)

### Scene Breakdown

Each scene captures specific user flows:

| Scene | Duration | Focus | Key Elements |
|-------|----------|-------|--------------|
| 1. Dashboard | 10s | Stats + Ledger | Full-screen review, sticky headers |
| 2. Reconcile | 8s | Bank matching | "Remember this payer name" |
| 3. Reminders | 9s | SMS + pay links | Zero-Twilio options |
| 4. Installments | 9s | Plans + clearance | Privacy-first door lists |
| 5. Housing | 9s | Rent + cash flow | Spending calculator |
| 6. Chair Budgets | 9s | Color cards + receipts | Dues credits |
| 7. Reports | 9s | Handoff + rollover | Encrypted ZIPs |
| 8. Security | 9s | Cloud backups | AES-GCM encryption |

## 🎨 Remotion Composition

### Component Architecture

```
PromoVideo.tsx
├── IntroSequence (0–6s)
│   └── 3 kinetic text messages
├── Scene1WithLowerThird (6–16s)
│   ├── Desktop window frame
│   ├── Video player (scene1-dashboard.webm)
│   └── Glassmorphic lower-third
├── Scene2WithLowerThird (16–24s)
│   └── ...
├── ...
├── Scene8WithLowerThird (78–87s)
│   └── ...
└── OutroSequence (87–93s)
    ├── DuesDesk logo
    ├── Feature bullets
    └── Tagline
```

### Animation System

**Spring-based camera zooms:**
```typescript
const scale = interpolate(
  localFrame,
  [0, durationInFrames / 3, durationInFrames],
  [1.0, 1.35, 1.0],
  { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
);
```

- Smooth ease-in/ease-out
- Zooms to 135% mid-scene
- Returns to 100% for seamless transitions

**Glassmorphic lower-thirds:**
```typescript
background: 'rgba(255, 255, 255, 0.08)',
backdropFilter: 'blur(20px)',
border: '1px solid rgba(255, 255, 255, 0.18)',
```

- Frosted glass effect
- Fade in/out with spring animation
- Slide up from bottom

### Color Palette

- **Background:** `linear-gradient(135deg, #090D16 0%, #111827 100%)`
- **Primary accent:** `#3B82F6` (blue)
- **Text primary:** `#FFFFFF` (white)
- **Text secondary:** `rgba(255, 255, 255, 0.8)`

## 🎞️ Encoding Pipeline

### Stage 1: Remotion Render

```bash
npx remotion render PromoVideo trailer duesdesk-launch-trailer-raw.webm
```

- Codec: VP9 (WebM)
- Resolution: 1920x1080
- Frame rate: 60fps
- Quality: High (CRF ~23)

### Stage 2: Transcode to MP4

```bash
ffmpeg -i input.webm \
  -c:v libx264 \
  -crf 14 \
  -pix_fmt yuv420p \
  -r 60 \
  output-intermediate.mp4
```

**Why transcode?**
- Universal compatibility (H.264 > VP9)
- Pixel format `yuv420p` required for some players
- Fixed 60fps for smooth playback

### Stage 3: Final Encode with Audio

```bash
ffmpeg \
  -f lavfi -i "sine=frequency=1000:duration=90:sample_rate=48000" \
  -i input-intermediate.mp4 \
  -filter:a "volume=0.05" \
  -c:v libx264 \
  -pix_fmt yuv420p \
  -crf 16 \
  -movflags +faststart \
  -c:a aac \
  -b:a 192k \
  -shortest \
  output.mp4
```

**Why add audio?**
- QuickTime and Finder previews show black screen for video-only files
- Subtle 1kHz sine wave at 5% volume (barely audible)
- AAC codec for broad compatibility
- 192kbps bitrate (high quality)

**Fast start flag:**
- Moves moov atom to beginning of file
- Enables instant web playback (no buffering)

## 📊 Output Specifications

### Launch Trailer (90s)

```
Codec:         H.264 (libx264)
Resolution:    1920x1080
Frame Rate:    60fps
Pixel Format:  yuv420p
Video Bitrate: ~8 Mbps (CRF 16)
Audio Codec:   AAC
Audio Bitrate: 192 kbps
File Size:     ~85 MB
```

### Hero Loop (15s)

```
Codec:         H.264 (libx264)
Resolution:    1920x1080
Frame Rate:    60fps
Pixel Format:  yuv420p
Video Bitrate: ~8 Mbps (CRF 16)
Audio Codec:   AAC
Audio Bitrate: 192 kbps
File Size:     ~15 MB
```

## 🧪 Quality Verification

[`encode-finals.mjs`](encode-finals.mjs) runs ffprobe to verify:

```bash
ffprobe -v error \
  -select_streams v:0 \
  -show_entries stream=codec_name,pix_fmt,width,height \
  duesdesk-launch-trailer.mp4
```

**Expected output:**
```
codec_name=h264
pix_fmt=yuv420p
width=1920
height=1080
```

## 🚀 Performance Optimization

### Playwright Recording
- `deviceScaleFactor: 2` for crisp UI capture
- `headless: false` for accurate rendering
- Mouse move steps: `durationMs / 16` (smooth 60fps)

### Remotion Rendering
- Concurrency: 4 (parallel frame rendering)
- Video format: JPEG (smaller intermediates)
- Output overwrite enabled

### FFmpeg Encoding
- CRF 14 for transcode (visually lossless)
- CRF 16 for finals (excellent quality, reasonable size)
- Hardware acceleration available (not enabled by default)

## 🛠️ Troubleshooting

### Issue: Spring Boot won't start with custom store path

**Solution:** Check application.properties:
```properties
store.file.path=${STORE_FILE_PATH:}
```

Pass via environment or command-line arg:
```bash
java -jar target/DuesDesk.jar --store.file.path=./demo-store.json
```

### Issue: Playwright scenes capture but show wrong data

**Problem:** Server using wrong store file

**Solution:** Verify STORE_FILE_PATH in capture script:
```javascript
const demoStorePath = join(process.cwd(), 'demo-store.json');
serverProcess = spawn('java', [
  '-jar', 'target/DuesDesk.jar',
  `--store.file.path=${demoStorePath}`
]);
```

### Issue: Videos play black in QuickTime

**Problem:** No audio track

**Solution:** Re-run `npm run encode` (adds ambient audio)

### Issue: Remotion can't find recordings

**Problem:** Video files not in expected location

**Solution:** Check recordings/ directory contains all 8 .webm files

## 📚 Further Reading

- [Remotion Documentation](https://www.remotion.dev/docs)
- [Playwright Video Recording](https://playwright.dev/docs/videos)
- [FFmpeg Encoding Guide](https://trac.ffmpeg.org/wiki/Encode/H.264)
- [Spring Boot Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)

---

**Built with care to showcase DuesDesk's powerful features while protecting real user data.**
