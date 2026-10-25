#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Swift's door to the C++ analyzer in native/analyzer, the same sources Android and the desktop build behind JNI.
/// Every call allocates and is O(n): worker threads only.
@interface LayaAnalysis : NSObject

/// The whole-track DSP features as the JSON analysis_jni.cpp produces, from mono float PCM at `rate` (resampled to the analyzer's 11,025 Hz here).
+ (nullable NSString *)featuresJSON:(const float *)samples count:(NSInteger)count rate:(double)rate duration:(double)duration;

/// The Beat This! log-mel spectrogram of mono 22,050 Hz PCM: `frames` x 128 floats, row-major.
+ (nullable NSData *)beatSpectrogram:(const float *)samples count:(NSInteger)count frames:(NSInteger *)frames;

@end

NS_ASSUME_NONNULL_END
