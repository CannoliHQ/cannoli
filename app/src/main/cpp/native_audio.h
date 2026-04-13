#pragma once

#include <cstdint>

void nativeAudioInit(int32_t sampleRate);
void nativeAudioWrite(const int16_t* data, int32_t frames);
void nativeAudioSetMuted(bool muted);
void nativeAudioPause();
void nativeAudioResume();
void nativeAudioStop();
const char* nativeAudioGetDiagnostics();
