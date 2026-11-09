# WIP: live viewfinder while recording

Status: not working yet.

Goal: record video from the camera preview callbacks (so the viewfinder keeps
running) instead of handing the camera's only output to MediaRecorder.

Current failure: `MediaMuxer.writeSampleData()` rejects the audio samples right
after the muxer starts ("writeSampleData returned an error"), which puts the
muxer into an error state; the following `stop()` reports
`stop() is called in invalid state 3` and the file ends up without a moov box.

What was already fixed here:

* video and audio presentation times must be relative to the recording start;
* all encoder drains must happen on one thread and the shutdown order must be
  microphone -> audio EOS -> video EOS -> muxer.stop();
* every drain call is wrapped so a codec error cannot kill the app.

Next idea: mux the video with MediaMuxer and the audio with MediaRecorder into
separate files and remux at the end (the remux helper is already in
PreviewVideoRecorder), instead of writing both tracks into one muxer.
