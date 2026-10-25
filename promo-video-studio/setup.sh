#!/bin/bash
# Setup script for promo video production pipeline

set -e

echo "🎬 DuesDesk Promo Video Studio Setup"
echo "====================================="
echo ""

# Check prerequisites
echo "🔍 Checking prerequisites..."

# Check Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js not found. Please install Node.js 18+ from https://nodejs.org/"
    exit 1
fi
echo "✅ Node.js $(node --version)"

# Check npm
if ! command -v npm &> /dev/null; then
    echo "❌ npm not found. Please install npm"
    exit 1
fi
echo "✅ npm $(npm --version)"

# Check Java
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Please install Java 17+ from https://adoptium.net/"
    exit 1
fi
echo "✅ Java $(java -version 2>&1 | head -n 1)"

# Check FFmpeg
if ! command -v ffmpeg &> /dev/null; then
    echo "⚠️  FFmpeg not found. Install it for final encoding:"
    echo "   macOS: brew install ffmpeg"
    echo "   Linux: sudo apt-get install ffmpeg"
    echo "   Windows: https://ffmpeg.org/download.html"
    echo ""
fi

# Check Maven (optional, for building DuesDesk)
if ! command -v mvn &> /dev/null; then
    echo "⚠️  Maven not found. You'll need it to build DuesDesk first."
    echo "   Install from https://maven.apache.org/download.cgi"
    echo ""
fi

echo ""
echo "📦 Installing npm dependencies..."
npm install

echo ""
echo "✅ Setup complete!"
echo ""
echo "📋 Next steps:"
echo "   1. Build DuesDesk: cd .. && mvn clean package && cd promo-video-studio"
echo "   2. Generate demo data: npm run seed"
echo "   3. Capture scenes: npm run capture"
echo "   4. Render videos: npm run render:trailer && npm run render:teaser"
echo "   5. Encode finals: npm run encode"
echo ""
echo "   Or run everything at once: npm run build"
echo ""
echo "🎥 Happy video making!"
