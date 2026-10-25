import React from 'react';
import { AbsoluteFill, spring, useCurrentFrame, useVideoConfig, interpolate, Video, Sequence, staticFile } from 'remotion';

interface PromoVideoProps {
  variant: 'trailer' | 'teaser';
}

const IntroSequence: React.FC<{ durationInFrames: number }> = ({ durationInFrames }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  
  const messages = [
    { text: 'Stop losing $4,000/yr to Greek billing fees.', start: 0, end: 120 },
    { text: 'Stop spending 6 hours every Sunday in broken spreadsheets.', start: 120, end: 240 },
    { text: 'Meet DuesDesk.', start: 240, end: 360 }
  ];
  
  return (
    <AbsoluteFill style={{ background: 'linear-gradient(135deg, #090D16 0%, #111827 100%)' }}>
      {messages.map((msg, i) => {
        const isActive = frame >= msg.start && frame < msg.end;
        const localFrame = frame - msg.start;
        
        const opacity = interpolate(
          localFrame,
          [0, 20, msg.end - msg.start - 20, msg.end - msg.start],
          [0, 1, 1, 0],
          { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
        );
        
        const scale = spring({
          frame: localFrame,
          fps,
          config: { damping: 200, stiffness: 100, mass: 0.5 }
        });
        
        return isActive ? (
          <AbsoluteFill key={i} style={{ justifyContent: 'center', alignItems: 'center' }}>
            <div style={{
              fontSize: msg.text.includes('Meet') ? 96 : 56,
              fontWeight: msg.text.includes('Meet') ? 700 : 600,
              color: 'white',
              textAlign: 'center',
              maxWidth: '80%',
              opacity,
              transform: `scale(${scale})`,
              fontFamily: 'Inter, system-ui, sans-serif'
            }}>
              {msg.text}
            </div>
          </AbsoluteFill>
        ) : null;
      })}
    </AbsoluteFill>
  );
};

const SceneWithLowerThird: React.FC<{
  videoSrc: string;
  title: string;
  subtitle: string;
  startFrame: number;
  durationInFrames: number;
}> = ({ videoSrc, title, subtitle, startFrame, durationInFrames }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  // Inside a <Sequence>, useCurrentFrame() already counts from the sequence's start.
  const localFrame = frame;
  
  // Camera zoom effect using spring
  const scale = interpolate(
    localFrame,
    [0, durationInFrames / 3, durationInFrames],
    [1.0, 1.35, 1.0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );
  
  // Lower-third glassmorphic card
  const lowerThirdOpacity = interpolate(
    localFrame,
    [30, 50, durationInFrames - 50, durationInFrames - 30],
    [0, 1, 1, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );
  
  const lowerThirdY = spring({
    frame: Math.max(0, localFrame - 30),
    fps,
    from: 100,
    to: 0,
    config: { damping: 200, stiffness: 100 }
  });
  
  return (
    <AbsoluteFill style={{ background: 'linear-gradient(135deg, #090D16 0%, #111827 100%)' }}>
      {/* Desktop window frame */}
      <div style={{
        position: 'absolute',
        top: '50%',
        left: '50%',
        transform: `translate(-50%, -50%) scale(${scale})`,
        width: '85%',
        height: '85%',
        borderRadius: 16,
        overflow: 'hidden',
        boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.5)',
        border: '1px solid rgba(255, 255, 255, 0.1)'
      }}>
        <Video
          src={staticFile(videoSrc)}
          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          startFrom={0}
          endAt={durationInFrames}
        />
      </div>
      
      {/* Glassmorphic lower-third */}
      <div style={{
        position: 'absolute',
        bottom: 80,
        left: '50%',
        transform: `translateX(-50%) translateY(${lowerThirdY}px)`,
        opacity: lowerThirdOpacity,
        // Dark glass: the app UI behind it is light, so white text needs a dark backing to stay readable.
        background: 'rgba(9, 13, 22, 0.78)',
        backdropFilter: 'blur(20px)',
        border: '1px solid rgba(255, 255, 255, 0.18)',
        borderRadius: 16,
        padding: '24px 40px',
        maxWidth: '80%',
        boxShadow: '0 8px 32px 0 rgba(0, 0, 0, 0.37)'
      }}>
        <div style={{
          fontSize: 32,
          fontWeight: 700,
          color: 'white',
          marginBottom: 8,
          fontFamily: 'Inter, system-ui, sans-serif'
        }}>
          {title}
        </div>
        <div style={{
          fontSize: 18,
          fontWeight: 400,
          color: 'rgba(255, 255, 255, 0.8)',
          fontFamily: 'Inter, system-ui, sans-serif'
        }}>
          {subtitle}
        </div>
      </div>
    </AbsoluteFill>
  );
};

const OutroSequence: React.FC<{ startFrame: number; durationInFrames: number }> = ({ startFrame, durationInFrames }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  // Inside a <Sequence>, useCurrentFrame() already counts from the sequence's start.
  const localFrame = frame;
  
  const opacity = interpolate(
    localFrame,
    [0, 30, durationInFrames - 30, durationInFrames],
    [0, 1, 1, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
  );
  
  const logoScale = spring({
    frame: localFrame,
    fps,
    config: { damping: 200, stiffness: 100, mass: 0.5 }
  });
  
  return (
    <AbsoluteFill style={{ 
      background: 'linear-gradient(135deg, #090D16 0%, #111827 100%)',
      justifyContent: 'center',
      alignItems: 'center',
      opacity
    }}>
      <div style={{ textAlign: 'center', transform: `scale(${logoScale})` }}>
        {/* Logo */}
        <div style={{
          fontSize: 96,
          fontWeight: 800,
          color: 'white',
          marginBottom: 32,
          fontFamily: 'Inter, system-ui, sans-serif',
          letterSpacing: '-0.02em'
        }}>
          DuesDesk
        </div>
        
        {/* Features */}
        <div style={{
          fontSize: 24,
          fontWeight: 500,
          color: 'rgba(255, 255, 255, 0.8)',
          marginBottom: 16,
          fontFamily: 'Inter, system-ui, sans-serif'
        }}>
          0% Fees • Mac & Windows Native • Multi-App Pay Links • AES-GCM Encrypted
        </div>
        
        {/* Tagline */}
        <div style={{
          fontSize: 32,
          fontWeight: 600,
          color: '#3B82F6',
          marginTop: 32,
          fontFamily: 'Inter, system-ui, sans-serif'
        }}>
          Take Command of Your Chapter's Finances.
        </div>
      </div>
    </AbsoluteFill>
  );
};

export const PromoVideo: React.FC<PromoVideoProps> = ({ variant }) => {
  const { fps, durationInFrames } = useVideoConfig();
  
  if (variant === 'teaser') {
    // 15-second hero loop: just intro + scene 1 + outro
    return (
      <>
        <Sequence durationInFrames={180}>
          <IntroSequence durationInFrames={180} />
        </Sequence>
        <Sequence from={180} durationInFrames={540}>
          <SceneWithLowerThird
            videoSrc="scene1-dashboard.webm"
            title="Instant Ledger Focus"
            subtitle="Full-screen review, sticky headers & scroll retention."
            startFrame={180}
            durationInFrames={540}
          />
        </Sequence>
        <Sequence from={720} durationInFrames={180}>
          <OutroSequence startFrame={720} durationInFrames={180} />
        </Sequence>
      </>
    );
  }
  
  // 90-second full trailer with all 8 scenes
  const scenes = [
    { video: 'scene1-dashboard.webm', title: 'Instant Ledger Focus', subtitle: 'Full-screen review, sticky headers & scroll retention.', duration: 600 },
    { video: 'scene2-reconcile.webm', title: 'GreekBill + Venmo + Bank CSV Sync', subtitle: "Deposit matching with 'Remember Who Pays for Whom'.", duration: 480 },
    { video: 'scene3-reminders.webm', title: 'Two-Tap Pay Links & Zero-Twilio Reminders', subtitle: 'Venmo, Cash App, PayPal & Zelle via Messages or Email.', duration: 540 },
    { video: 'scene4-installments-clearance.webm', title: 'Enforceable Plans & Privacy-First Clearance', subtitle: 'Printable contracts, vouches & balance-free door lists.', duration: 540 },
    { video: 'scene5-housing-cashflow.webm', title: 'House Rent, Cash Flow & IHQ Protection', subtitle: 'Linked resident rent and real-time event spending checks.', duration: 540 },
    { video: 'scene6-chair-budgets.webm', title: 'Visual Chair Budgets & Dues-Credit Receipts', subtitle: 'Color-coded cards, local receipts & direct dues credits.', duration: 540 },
    { video: 'scene7-reports-rollover.webm', title: '1-Click Semester Rollover & Officer Handoffs', subtitle: 'Anonymous packets, encrypted ZIPs & instant transitions.', duration: 540 },
    { video: 'scene8-security-snapshots.webm', title: 'Keychain & DPAPI Cloud Snapshots', subtitle: 'AES-GCM backups to iCloud, OneDrive, Dropbox, or Google Drive.', duration: 540 }
  ];
  
  let currentFrame = 360; // Start after 6s intro
  
  return (
    <>
      {/* Intro */}
      <Sequence durationInFrames={360}>
        <IntroSequence durationInFrames={360} />
      </Sequence>
      
      {/* Scenes */}
      {scenes.map((scene, i) => {
        const sceneStart = currentFrame;
        const sequence = (
          <Sequence key={i} from={sceneStart} durationInFrames={scene.duration}>
            <SceneWithLowerThird
              videoSrc={scene.video}
              title={scene.title}
              subtitle={scene.subtitle}
              startFrame={sceneStart}
              durationInFrames={scene.duration}
            />
          </Sequence>
        );
        currentFrame += scene.duration;
        return sequence;
      })}
      
      {/* Outro */}
      <Sequence from={currentFrame} durationInFrames={360}>
        <OutroSequence startFrame={currentFrame} durationInFrames={360} />
      </Sequence>
    </>
  );
};
