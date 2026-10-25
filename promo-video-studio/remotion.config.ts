import { Config } from '@remotion/cli/config';

Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
Config.setConcurrency(4);
Config.setCodec('h264');
// Scene recordings from capture-scenes.mjs are served to staticFile() from here.
Config.setPublicDir('./recordings');
