#include "kws.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "config.h"
#include "model_data.h"
#include <tensorflow/lite/micro/micro_error_reporter.h>
#include <tensorflow/lite/micro/micro_interpreter.h>
#include <tensorflow/lite/micro/micro_mutable_op_resolver.h>
#include <tensorflow/lite/schema/schema_generated.h>
#include <tensorflow/lite/version.h>

namespace {
constexpr int FFT_SIZE = 512;
constexpr int FRAME_LENGTH = 480;
constexpr int FRAME_STEP = 320;
constexpr int FEATURE_FRAMES = 49;
constexpr int MEL_BINS = 40;
constexpr size_t TENSOR_ARENA_BYTES = 80 * 1024;

alignas(16) uint8_t tensorArena[TENSOR_ARENA_BYTES];
float features[FEATURE_FRAMES * MEL_BINS];
float realPart[FFT_SIZE];
float imaginaryPart[FFT_SIZE];
float powerSpectrum[FFT_SIZE / 2 + 1];
float hammingWindow[FRAME_LENGTH];
float dctCosines[MEL_BINS][MEL_BINS];
int melFirstBins[MEL_BINS];
int melLastBins[MEL_BINS];
tflite::MicroErrorReporter errorReporter;
tflite::MicroMutableOpResolver<9> resolver;
tflite::MicroInterpreter* interpreter = nullptr;
TfLiteTensor* inputTensor = nullptr;
TfLiteTensor* outputTensor = nullptr;
bool operatorsRegistered = false;

float hz_to_mel(float hz) {
  return 2595.0f * log10f(1.0f + hz / 700.0f);
}

float mel_to_hz(float mel) {
  return 700.0f * (powf(10.0f, mel / 2595.0f) - 1.0f);
}

void fft() {
  for (int i = 1, j = 0; i < FFT_SIZE; ++i) {
    int bit = FFT_SIZE >> 1;
    for (; j & bit; bit >>= 1) {
      j ^= bit;
    }
    j ^= bit;
    if (i < j) {
      std::swap(realPart[i], realPart[j]);
      std::swap(imaginaryPart[i], imaginaryPart[j]);
    }
  }

  for (int length = 2; length <= FFT_SIZE; length <<= 1) {
    const float angle = -2.0f * PI / length;
    const float stepReal = cosf(angle);
    const float stepImaginary = sinf(angle);
    for (int start = 0; start < FFT_SIZE; start += length) {
      float twiddleReal = 1.0f;
      float twiddleImaginary = 0.0f;
      for (int offset = 0; offset < length / 2; ++offset) {
        const int even = start + offset;
        const int odd = even + length / 2;
        const float oddReal =
            realPart[odd] * twiddleReal - imaginaryPart[odd] * twiddleImaginary;
        const float oddImaginary =
            realPart[odd] * twiddleImaginary + imaginaryPart[odd] * twiddleReal;
        realPart[odd] = realPart[even] - oddReal;
        imaginaryPart[odd] = imaginaryPart[even] - oddImaginary;
        realPart[even] += oddReal;
        imaginaryPart[even] += oddImaginary;
        const float nextReal =
            twiddleReal * stepReal - twiddleImaginary * stepImaginary;
        twiddleImaginary =
            twiddleReal * stepImaginary + twiddleImaginary * stepReal;
        twiddleReal = nextReal;
      }
    }
  }
}

void calculate_features(const int16_t* samples) {
  float melPoints[MEL_BINS + 2];
  const float minMel = hz_to_mel(0.0f);
  const float maxMel = hz_to_mel(AUDIO_SAMPLE_RATE / 2.0f);
  for (int i = 0; i < MEL_BINS + 2; ++i) {
    melPoints[i] = mel_to_hz(
        minMel + (maxMel - minMel) * i / (MEL_BINS + 1));
  }
  for (int mel = 0; mel < MEL_BINS; ++mel) {
    melFirstBins[mel] = std::max(
        0, static_cast<int>(ceilf(melPoints[mel] * FFT_SIZE / AUDIO_SAMPLE_RATE)));
    melLastBins[mel] = std::min(
        FFT_SIZE / 2,
        static_cast<int>(floorf(melPoints[mel + 2] * FFT_SIZE / AUDIO_SAMPLE_RATE)));
  }

  for (int frameIndex = 0; frameIndex < FEATURE_FRAMES; ++frameIndex) {
    const int frameStart = frameIndex * FRAME_STEP;
    memset(realPart, 0, sizeof(realPart));
    memset(imaginaryPart, 0, sizeof(imaginaryPart));
    for (int i = 0; i < FRAME_LENGTH; ++i) {
      realPart[i] =
          samples[frameStart + i] * (1.0f / 32768.0f) * hammingWindow[i];
    }
    fft();
    for (int bin = melFirstBins[mel]; bin <= melLastBins[mel]; ++bin) {
      powerSpectrum[bin] =
          realPart[bin] * realPart[bin] + imaginaryPart[bin] * imaginaryPart[bin];
    }

    float melEnergy[MEL_BINS];
    for (int mel = 0; mel < MEL_BINS; ++mel) {
      const float left = melPoints[mel];
      const float center = melPoints[mel + 1];
      const float right = melPoints[mel + 2];
      float energy = 0.0f;
      for (int bin = 0; bin <= FFT_SIZE / 2; ++bin) {
        const float hz = static_cast<float>(bin) * AUDIO_SAMPLE_RATE / FFT_SIZE;
        float weight = 0.0f;
        if (hz >= left && hz <= center && center > left) {
          weight = (hz - left) / (center - left);
        } else if (hz > center && hz <= right && right > center) {
          weight = (right - hz) / (right - center);
        }
        energy += powerSpectrum[bin] * weight;
      }
      melEnergy[mel] = logf(std::max(energy, 1.0e-6f));
    }

    for (int coefficient = 0; coefficient < MEL_BINS; ++coefficient) {
      float sum = 0.0f;
      for (int mel = 0; mel < MEL_BINS; ++mel) {
        sum += melEnergy[mel] * dctCosines[mel][coefficient];
      }
      const float norm = coefficient == 0
                             ? sqrtf(1.0f / MEL_BINS)
                             : sqrtf(2.0f / MEL_BINS);
      features[frameIndex * MEL_BINS + coefficient] = sum * norm;
    }
  }
}
}

bool kws_begin() {
  if (g_model_data_is_placeholder) {
    return false;
  }
  const tflite::Model* model = tflite::GetModel(g_model_data);
  if (model == nullptr || model->version() != TFLITE_SCHEMA_VERSION) {
    return false;
  }

  for (int i = 0; i < FRAME_LENGTH; ++i) {
    hammingWindow[i] =
        0.54f - 0.46f * cosf(2.0f * PI * i / (FRAME_LENGTH - 1));
  }
  for (int mel = 0; mel < MEL_BINS; ++mel) {
    for (int coefficient = 0; coefficient < MEL_BINS; ++coefficient) {
      dctCosines[mel][coefficient] =
          cosf(PI * (mel + 0.5f) * coefficient / MEL_BINS);
    }
  }

  if (!operatorsRegistered) {
    if (resolver.AddConv2D() != kTfLiteOk ||
        resolver.AddDepthwiseConv2D() != kTfLiteOk ||
        resolver.AddMean() != kTfLiteOk || resolver.AddReshape() != kTfLiteOk ||
        resolver.AddFullyConnected() != kTfLiteOk ||
        resolver.AddSoftmax() != kTfLiteOk || resolver.AddPad() != kTfLiteOk ||
        resolver.AddQuantize() != kTfLiteOk || resolver.AddRelu() != kTfLiteOk) {
      return false;
    }
    operatorsRegistered = true;
  }

  static tflite::MicroInterpreter instance(
      model, resolver, tensorArena, TENSOR_ARENA_BYTES, &errorReporter);
  interpreter = &instance;
  if (interpreter->AllocateTensors() != kTfLiteOk) {
    interpreter = nullptr;
    return false;
  }
  inputTensor = interpreter->input(0);
  outputTensor = interpreter->output(0);
  return inputTensor != nullptr && outputTensor != nullptr &&
         inputTensor->type == kTfLiteInt8 && outputTensor->type == kTfLiteInt8 &&
         inputTensor->bytes == FEATURE_FRAMES * MEL_BINS &&
         outputTensor->bytes >= 2;
}

bool kws_detect(const int16_t* window, float& iraScore) {
  if (interpreter == nullptr || window == nullptr) {
    return false;
  }
  calculate_features(window);
  const float scale = inputTensor->params.scale;
  if (scale <= 0.0f) {
    return false;
  }
  int8_t* input = inputTensor->data.int8;
  for (size_t i = 0; i < sizeof(features) / sizeof(features[0]); ++i) {
    const int32_t quantized =
        static_cast<int32_t>(roundf(features[i] / scale)) +
        inputTensor->params.zero_point;
    input[i] = static_cast<int8_t>(
        std::max(-128, std::min(127, static_cast<int>(quantized))));
  }
  if (interpreter->Invoke() != kTfLiteOk) {
    return false;
  }
  iraScore = (outputTensor->data.int8[0] - outputTensor->params.zero_point) *
             outputTensor->params.scale;
  return iraScore >= KWS_DETECTION_THRESHOLD;
}
