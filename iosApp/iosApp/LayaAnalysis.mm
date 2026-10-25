#import "LayaAnalysis.h"

#include <string>
#include <vector>

#include "analyzer/audio_analysis.h"
#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

// The analyzer's own rate (see TrackFeatures.nativeSampleRate in analysis_jni.cpp).
static const double kAnalysisRate = 11025.0;

// The JSON writer below mirrors app/src/main/cpp/jni/analysis_jni.cpp field for field, so TrackFeatureJson parses both.
namespace {

void AppendString(std::string& out, const std::string& value) {
  out += '"';
  for (const char c : value) {
    if (c >= 32 && c < 127 && c != '"' && c != '\\') out += c;
  }
  out += '"';
}

void AppendNumber(std::string& out, double value) {
  if (!(value == value) || value > 1e308 || value < -1e308) { out += "null"; return; }
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%.6g", value);
  out += buffer;
}

void AppendDoubles(std::string& out, const std::vector<double>& values) {
  out += '[';
  for (size_t i = 0; i < values.size(); ++i) {
    if (i > 0) out += ',';
    AppendNumber(out, values[i]);
  }
  out += ']';
}

void AppendEnergyCurve(std::string& out, const std::vector<laya::smart::EnergyPoint>& points) {
  out += '[';
  for (size_t i = 0; i < points.size(); ++i) {
    if (i > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[i].time);
    out += ",\"e\":";
    AppendNumber(out, points[i].energy);
    out += '}';
  }
  out += ']';
}

void AppendCuePoints(std::string& out, const std::vector<laya::smart::MixCuePoint>& points) {
  out += '[';
  for (size_t i = 0; i < points.size(); ++i) {
    if (i > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[i].time);
    out += ",\"s\":";
    AppendNumber(out, points[i].score);
    out += ",\"y\":";
    AppendString(out, points[i].type);
    out += '}';
  }
  out += ']';
}

void AppendField(std::string& out, const char* name, double value, bool first = false) {
  if (!first) out += ',';
  out += '"';
  out += name;
  out += "\":";
  AppendNumber(out, value);
}

}  // namespace

@implementation LayaAnalysis

+ (nullable NSString *)featuresJSON:(const float *)samples count:(NSInteger)count rate:(double)rate duration:(double)duration {
  if (count <= 0) return nil;
  std::vector<float> input(samples, samples + count);
  if (rate != kAnalysisRate) {
    input = laya::smart::Resample(input, rate, kAnalysisRate);
    if (input.empty()) return nil;
  }
  const laya::smart::AnalysisResult r = laya::smart::AnalyzeAudio(input, kAnalysisRate, duration);

  std::string json;
  json.reserve(8192 + r.energy_curve.size() * 24);
  json += '{';
  AppendField(json, "duration", r.duration, true);
  AppendField(json, "bpm", r.bpm);
  AppendField(json, "beatInterval", r.beat_interval);
  AppendField(json, "firstBeat", r.first_beat);
  AppendField(json, "beatConfidence", r.beat_confidence);
  AppendField(json, "keyConfidence", r.key_confidence);
  AppendField(json, "audibleStartTime", r.audible_start_time);
  AppendField(json, "pickupTime", r.pickup_time);
  AppendField(json, "introEndTime", r.intro_end_time);
  AppendField(json, "outroStartTime", r.outro_start_time);
  AppendField(json, "contentEndTime", r.content_end_time);
  AppendField(json, "mixInTime", r.mix_in_time);
  AppendField(json, "mixOutTime", r.mix_out_time);
  AppendField(json, "vocalProbability", r.vocal_probability);
  json += ",\"key\":";
  AppendString(json, r.key);
  json += ",\"downbeats\":";
  AppendDoubles(json, r.downbeats);
  json += ",\"phraseBoundaries\":";
  AppendDoubles(json, r.phrase_boundaries);
  json += ",\"vocalActivityMask\":";
  AppendDoubles(json, r.vocal_activity_mask);
  json += ",\"energyCurve\":";
  AppendEnergyCurve(json, r.energy_curve);
  json += ",\"lowEnergyCurve\":";
  AppendEnergyCurve(json, r.low_energy_curve);
  json += ",\"mixInCandidates\":";
  AppendCuePoints(json, r.mix_in_candidates);
  json += ",\"mixOutCandidates\":";
  AppendCuePoints(json, r.mix_out_candidates);
  json += '}';
  return [NSString stringWithUTF8String:json.c_str()];
}

+ (nullable NSData *)beatSpectrogram:(const float *)samples count:(NSInteger)count frames:(NSInteger *)frames {
  if (count <= 0) return nil;
  const std::vector<float> input(samples, samples + count);
  const laya::smart::BeatSpectrogram s =
      laya::smart::ComputeBeatSpectrogram(input, laya::smart::kBeatSpectrogramSampleRate);
  if (s.values.empty()) return nil;
  *frames = (NSInteger)s.frames;
  return [NSData dataWithBytes:s.values.data() length:s.values.size() * sizeof(float)];
}

@end
