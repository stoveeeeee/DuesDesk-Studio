#!/usr/bin/env node
/**
 * Encodes final MP4 videos with proper H.264 + AAC for universal playback
 * (QuickTime, Finder previews, web browsers). Includes subtle ambient audio
 * track to prevent black-screen issues.
 */
import { spawn } from 'child_process';
import { existsSync } from 'fs';

function ffmpeg(args) {
  return new Promise((resolve, reject) => {
    console.log(`🎬 Running: ffmpeg ${args.join(' ')}`);
    
    const proc = spawn('ffmpeg', args, { stdio: 'inherit' });
    
    proc.on('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg exited with code ${code}`));
    });
    
    proc.on('error', reject);
  });
}

async function renderFinalWithAudio(input, output) {
  if (!existsSync(input)) {
    throw new Error(`${input} not found; run the render:trailer and render:teaser scripts first`);
  }
  console.log(`🎵 Encoding final ${output} with a silent audio track...`);

  // A silent AAC track (some players show black without one). Remotion renders full-range yuvj420p from its JPEG
  // frames, which some players (QuickTime) show washed out, so re-encode to standard yuv420p.
  await ffmpeg([
    '-i', input,
    '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000',
    '-map', '0:v:0', '-map', '1:a:0',
    '-vf', 'scale=in_range=full:out_range=tv,format=yuv420p',
    '-color_range', 'tv',
    '-c:v', 'libx264',
    '-crf', '16',
    '-movflags', '+faststart',
    '-c:a', 'aac',
    '-b:a', '192k',
    '-shortest',
    '-y',
    output
  ]);

  console.log(`✅ ${output} encoded with audio\n`);
}

async function verifyOutput(file) {
  console.log(`🔍 Verifying ${file}...`);
  
  return new Promise((resolve, reject) => {
    const proc = spawn('ffprobe', [
      '-v', 'error',
      '-select_streams', 'v:0',
      '-show_entries', 'stream=codec_name,pix_fmt,width,height',
      '-of', 'default=noprint_wrappers=1',
      file
    ]);
    
    let output = '';
    proc.stdout.on('data', (data) => { output += data; });
    proc.stderr.on('data', (data) => { console.error(data.toString()); });
    
    proc.on('close', (code) => {
      if (code === 0) {
        console.log(output);
        
        if (output.includes('codec_name=h264') && 
            output.includes('pix_fmt=yuv420p') &&
            output.includes('width=1920') &&
            output.includes('height=1080')) {
          console.log(`✅ ${file} verified: h264, yuv420p, 1920x1080\n`);
          resolve();
        } else {
          reject(new Error(`${file} does not meet requirements`));
        }
      } else {
        reject(new Error(`ffprobe failed with code ${code}`));
      }
    });
    
    proc.on('error', reject);
  });
}

async function openVideo(file) {
  const command = process.platform === 'win32' ? 'start' : 'open';
  
  return new Promise((resolve) => {
    spawn(command, [file], { 
      detached: true,
      stdio: 'ignore',
      shell: true
    }).unref();
    
    console.log(`🎥 Opened ${file}\n`);
    resolve();
  });
}

async function main() {
  try {
    console.log('🚀 Starting final encoding pipeline...\n');
    
    // Step 1: Add audio to the Remotion renders (already H.264 MP4)
    console.log('🎵 Step 1: Encoding finals with audio\n');
    await renderFinalWithAudio('duesdesk-launch-trailer-raw.mp4', './duesdesk-launch-trailer.mp4');
    await renderFinalWithAudio('duesdesk-hero-loop-raw.mp4', './duesdesk-hero-loop.mp4');

    // Step 2: Verify outputs
    console.log('🔍 Step 2: Verifying outputs\n');
    await verifyOutput('./duesdesk-launch-trailer.mp4');
    await verifyOutput('./duesdesk-hero-loop.mp4');

    // Step 3: Open in default player
    console.log('🎥 Step 3: Opening videos\n');
    await openVideo('./duesdesk-launch-trailer.mp4');
    
    console.log('🎉 Complete! Videos ready for distribution.');
    
  } catch (error) {
    console.error('❌ Error:', error);
    process.exit(1);
  }
}

main();
