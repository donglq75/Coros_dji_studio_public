# VietNote ASR standalone test app

This is a separate Android application, not an update to COROS DJI Studio. It uses sherpa-onnx 2.0.3 with the static-ONNX-Runtime Android AAR so its native runtime cannot collide with Studio's VietASR / ONNX Runtime 1.20.0.

## Test flow

1. Install the APK named `COROS-DJI-VietNote-ASR-Test-0.1.0.apk`.
2. Open **VietNote ASR Test** and download the model once (about 83 MB; Internet required only for this download).
3. Select up to 20 DJI MP4 clips from the phone.
4. Run recognition. Video audio is decoded locally and resampled to 16 kHz mono; recognition itself is offline.
5. The app shows the transcript and token timestamps, and saves `vietnote-comparison.txt` under the app's external files directory.

The report is intended for side-by-side comparison with VietASR, especially English terms spoken inside Vietnamese sentences. Token timestamps are retained for evaluation; they are not yet wired into VoiceGuard masking.

## Model attribution and license

Model: [VietNote-Zipformer-78M-Streaming](https://huggingface.co/Kaisye/VietNote-Zipformer-78M-Streaming), by Kaisye. The app downloads model weights at runtime and does not bundle them in the APK. The model card currently specifies CC BY-NC-SA 4.0 for weights; use is for non-commercial evaluation unless the model author grants other rights. See the model card and accompanying notice/license before redistribution or commercial use.

Runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0.
