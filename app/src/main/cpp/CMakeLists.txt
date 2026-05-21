# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.
