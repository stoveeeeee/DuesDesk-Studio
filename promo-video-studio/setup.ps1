# Setup script for promo video production pipeline (Windows PowerShell)

Write-Host "🎬 DuesDesk Promo Video Studio Setup" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

# Check prerequisites
Write-Host "🔍 Checking prerequisites..." -ForegroundColor Yellow

# Check Node.js
try {
    $nodeVersion = node --version
    Write-Host "✅ Node.js $nodeVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Node.js not found. Please install Node.js 18+ from https://nodejs.org/" -ForegroundColor Red
    exit 1
}

# Check npm
try {
    $npmVersion = npm --version
    Write-Host "✅ npm $npmVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ npm not found. Please install npm" -ForegroundColor Red
    exit 1
}

# Check Java
try {
    $javaVersion = java -version 2>&1 | Select-Object -First 1
    Write-Host "✅ Java $javaVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Java not found. Please install Java 17+ from https://adoptium.net/" -ForegroundColor Red
    exit 1
}

# Check FFmpeg
try {
    ffmpeg -version | Out-Null
    Write-Host "✅ FFmpeg found" -ForegroundColor Green
} catch {
    Write-Host "⚠️  FFmpeg not found. Install it for final encoding:" -ForegroundColor Yellow
    Write-Host "   Download from https://ffmpeg.org/download.html"
    Write-Host "   Add to PATH or place in promo-video-studio directory"
    Write-Host ""
}

# Check Maven (optional)
try {
    mvn --version | Out-Null
    Write-Host "✅ Maven found" -ForegroundColor Green
} catch {
    Write-Host "⚠️  Maven not found. You'll need it to build DuesDesk first." -ForegroundColor Yellow
    Write-Host "   Install from https://maven.apache.org/download.cgi"
    Write-Host ""
}

Write-Host ""
Write-Host "📦 Installing npm dependencies..." -ForegroundColor Yellow
npm install

Write-Host ""
Write-Host "✅ Setup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "📋 Next steps:" -ForegroundColor Cyan
Write-Host "   1. Build DuesDesk: cd .. && mvn clean package && cd promo-video-studio"
Write-Host "   2. Generate demo data: npm run seed"
Write-Host "   3. Capture scenes: npm run capture"
Write-Host "   4. Render videos: npm run render:trailer && npm run render:teaser"
Write-Host "   5. Encode finals: npm run encode"
Write-Host ""
Write-Host "   Or run everything at once: npm run build"
Write-Host ""
Write-Host "🎥 Happy video making!" -ForegroundColor Magenta
