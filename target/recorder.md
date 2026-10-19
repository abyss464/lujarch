# Screen record

The owner records the screen exactly as wanted: every setting of the video, the sound, the file and
the recording itself can be chosen.

## Starting a recording

### REC-1 Recording starts from the screen record dialog

- Home: Quick Settings > Screen record
- Switch: the dialog's Record audio and its source (Device audio, Microphone, Device audio and microphone), Show touches on screen, Profile, and Settings
- Default: as the settings page says (REC-2 to REC-6); no profile chosen
- Verify: vm

The dialog shows what is recorded, the audio choice, Show touches on screen and the profile in use.
It shows the size a minute of recording takes with the current settings, or that it depends on the
picture when the size follows the picture's quality. Choosing a profile sets every setting it holds.
Settings opens Settings > System > Screen record. The audio choice is the one on the settings page.
After Start, the countdown of REC-6 runs before the recording starts.

### REC-2 The video has the settings the owner chooses

- Home: Settings > System > Screen record > Video
- Switch: Codec; Encoder; Profile and level; Bit depth: 8-bit, 10-bit; Resolution: Same as the screen, 3/4, 1/2, Custom (a width and a height); Frame rate: Same as the screen, 24, 30, 60, 90, 120, Custom; Frames: Only when the screen changes, Every frame; Bitrate: Automatic or a number of Mbps; Bitrate mode: Variable, Constant, Constant quality (with a quality); Key frame interval: a number of seconds, Every frame, Only the first; B-frames: a number; Orientation: As the screen is at the start, Portrait, Landscape
- Default: H.265 when the phone has a hardware H.265 encoder, H.264 otherwise; the codec's hardware encoder; automatic profile and level; 8-bit; Same as the screen; Same as the screen; Only when the screen changes; Automatic; Variable; 1 second; 0; As the screen is at the start
- Verify: vm

Codec lists H.264, H.265, AV1, VP8 and VP9 as far as the phone has an encoder for them, each marked
Hardware or Software. Encoder lists the phone's encoders of the codec. Profile and level, the sizes,
the frame rates, the bitrate range, the bitrate modes and the quality range offered are those the
chosen encoder supports; a choice it does not support cannot be made and says why. A resolution
the encoder does not take is brought to the nearest one it takes, and the page says so. Automatic
bitrate follows the size and the frame rate. 10-bit records in the codec's 10-bit profile. Same as
the screen follows the screen's refresh rate up to what the encoder takes at that size.

### REC-3 The sound has the settings the owner chooses

- Home: Settings > System > Screen record > Audio
- Switch: Record audio and its source; Both sources: Mixed into one track, Separate tracks; Device audio volume and Microphone volume: 0 to 200 %; Codec: AAC-LC, HE-AAC, Opus; Sample rate: 44.1 kHz, 48 kHz; Channels: Mono, Stereo; Bitrate: a number of kbps; Noise suppression; Echo cancellation
- Default: off, Device audio; Mixed into one track; 100 %; AAC-LC; 44.1 kHz; Stereo; 192 kbps; off; off
- Verify: vm

With Separate tracks the file has the device audio and the microphone each as its own track. The
volumes scale each source before it is recorded. Noise suppression and Echo cancellation apply to
the microphone, and are offered when the phone has them.

### REC-4 The file is saved as the owner chooses

- Home: Settings > System > Screen record > File
- Switch: Format: MP4, WebM; Folder: a folder under DCIM, Movies or Pictures; File name: a pattern of date and time; Longest recording: a number of minutes or No limit; Largest file: a number of MB or No limit; At the limit: Stop, Go on in a new file
- Default: MP4; Movies; screen-yyyyMMdd-HHmmss; 60 minutes; 5000 MB; Stop
- Verify: vm

MP4 takes H.264, H.265 and AV1 with AAC-LC, HE-AAC or Opus; WebM takes VP8 and VP9 with Opus. A
codec the format does not take cannot be chosen with it. The file name shows the name the next
recording gets. With Go on in a new file, a recording that reaches a limit goes on in a new file
named with -2, -3 and so on, each file saved as soon as it ends.

### REC-5 Profiles hold sets of settings

- Home: Settings > System > Screen record > Profiles
- Switch: High quality, Small file, and each saved profile: Use, Rename, Delete; Save current settings as a profile
- Default: High quality and Small file
- Verify: vm

Use sets every setting of the video, the sound and the file to the profile's. High quality records
the screen's size and refresh rate at a high bitrate; Small file records half the size at 30 frames
per second at a low bitrate.

## While recording

### REC-6 The recording runs as the owner chooses

- Home: Settings > System > Screen record > Recording
- Switch: Countdown: None, 3, 5, 10 seconds; Keep the screen on; Do not disturb while recording
- Default: 3 seconds; off; off
- Verify: vm

Keep the screen on keeps the screen from turning off until the recording ends. Do not disturb while
recording turns on Do not disturb for priority only when the recording starts and puts back the
earlier state when it ends.

### REC-7 A recording can be paused

- Home: the screen recording notification
- Switch: Pause, Resume
- Default: none
- Verify: vm

Pause stops recording the picture and the sound until Resume; the file plays on from the moment of
the pause to the moment of the resume. While paused the notification says Paused.

### REC-8 A recording that fails says why

- Home: a notification
- Switch: none
- Default: none
- Verify: vm

When the settings cannot be used together or the encoder fails, the recording does not start or it
ends, and a notification says which setting or what failed. What was recorded before a failure is
saved.
