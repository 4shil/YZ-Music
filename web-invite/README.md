# YZ Music — Listen Together Invite Web Portal

This directory contains the standalone web portal for **YZ Music Listen Together** party invites.

When users share a Listen Together party invite link or scan the QR code from the Android or Linux Desktop app, it opens `https://yz-music.vercel.app/invite/{CODE}` (or `https://yz-music.vercel.com/invite/{CODE}`).

---

## 🚀 How to Deploy to Vercel

You can deploy this directory to Vercel in less than 2 minutes using either the **Vercel CLI** or the **Vercel Web Dashboard**.

---

### Option 1: Quick Deployment via Vercel CLI (Recommended)

1. Open your terminal in this directory:
   ```bash
   cd /home/ash/Bitchord/web-invite
   ```

2. Run Vercel CLI:
   ```bash
   npx vercel
   ```

3. Follow the CLI prompts:
   - **Set up and deploy?**: `y`
   - **Which scope do you want to deploy to?**: Select your Vercel account
   - **Link to existing project?**: `n`
   - **What's your project's name?**: `yz-music` (or your preferred name)
   - **In which directory is your code located?**: `./`
   - **Want to modify these settings?**: `n`

4. Deploy to production:
   ```bash
   npx vercel --prod
   ```

5. Go to your **Vercel Dashboard** -> **Project Settings** -> **Domains**:
   - Ensure `yz-music.vercel.app` is assigned.
   - If you own `yz-music.vercel.com` or custom domains, add them under Domains.

---

### Option 2: Deploy via Vercel Web Dashboard (GitHub Import)

1. Push your branch `1.8-update` to GitHub:
   ```bash
   git push origin 1.8-update
   ```
2. Go to [vercel.com/new](https://vercel.com/new).
3. Import your repository: `4shil/YZ-Music`.
4. In the project configuration:
   - **Project Name**: `yz-music`
   - **Framework Preset**: `Other`
   - **Root Directory**: Click *Edit* and select `web-invite`.
5. Click **Deploy**.
6. Under **Settings > Domains**, verify your domain is set to `yz-music.vercel.app` (and/or add your custom domain `yz-music.vercel.com`).

---

## 📱 Features

- **App Deep-Linking**: Tapping "Open & Join in App" immediately attempts to open `yzmusic://party/{CODE}`.
- **Auto-Detect Invite Code**: Extracts 6-character codes from `/invite/:code` or `?code=:code`.
- **Live QR Code**: Generates a high-contrast QR code so friends sitting nearby can scan directly off your screen.
- **Copy Code & Feedback**: One-tap copy with toast notification.
- **Direct Downloads**: Quick links to download the Android APK and Linux Desktop version.
- **SPA Rewrites**: Powered by `vercel.json` rewrite rules so any invite link (`/invite/XYZ123`) resolves smoothly.
