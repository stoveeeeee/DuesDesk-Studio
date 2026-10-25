import React from 'react';
import { Composition } from 'remotion';
import { PromoVideo } from './PromoVideo';

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="trailer"
        component={PromoVideo}
        durationInFrames={90 * 60} // 90 seconds at 60fps
        fps={60}
        width={1920}
        height={1080}
        defaultProps={{
          variant: 'trailer'
        }}
      />
      <Composition
        id="teaser"
        component={PromoVideo}
        durationInFrames={15 * 60} // 15 seconds at 60fps
        fps={60}
        width={1920}
        height={1080}
        defaultProps={{
          variant: 'teaser'
        }}
      />
    </>
  );
};
