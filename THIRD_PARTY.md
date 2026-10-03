# Attribution and licenses

The Android UI, model management and Rust/JNI integration in this repository are licensed under MIT; see [LICENSE](LICENSE).

The speech model was created by the GigaAM authors, not by this application. The app downloads ONNX exports by Ivan Stupakov after installation; model weights are not included in the APK or this repository.

| Component | Source | License |
|---|---|---|
| GigaAM model and original code | [salute-developers/GigaAM](https://github.com/salute-developers/GigaAM) | MIT; original notice in `third_party/GigaAM-LICENSE.txt` |
| GigaAM v3 ONNX conversion | [istupakov/gigaam-v3-onnx](https://huggingface.co/istupakov/gigaam-v3-onnx) | Model card declares MIT |
| ONNX Runtime | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT; notice in `third_party/ONNX-Runtime-LICENSE.txt` |
| AndroidX and Material components | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Material](https://github.com/material-components/material-components-android) | Apache 2.0 |

Rust and JVM dependencies retain their own licenses. Dependency versions are specified in `app/build.gradle.kts` and `native/gigaam_core/Cargo.lock`; this project license does not replace their notices.
