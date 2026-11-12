// Voice search on Windows through the system's own recognizer
// (Windows.Media.SpeechRecognition), the one Windows' online dictation uses.
// The same plain C API as LayaSpeech.swift on macOS, loaded through JNA:
// start with languages and a callback, stop. Nothing is bundled beyond this
// code: the recognition itself is the system's.
//
// Windows' recognizer hears one language per recognizer, so to tell languages
// apart it runs one per candidate language on the same microphone, and keeps
// the one most confident in what it heard. Whatever goes wrong — no speech
// pack for the language, online speech recognition turned off, no microphone
// access — comes back as "unavailable", and the app falls back to Win+H.

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Globalization.h>
#include <winrt/Windows.Media.SpeechRecognition.h>

#include <cctype>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Globalization;
using namespace winrt::Windows::Media::SpeechRecognition;
using namespace std::chrono_literals;

/// kind: 0 = words heard so far, 1 = final transcript, 2 = recognizer
/// unavailable (the app falls back to Win+H).
typedef void(__cdecl* LayaSpeechCallback)(int kind, const char* text);

namespace {

/// How long a pause ends the query, how long to wait for a first word, and
/// how long the other languages get once one has finished with words.
constexpr auto kEndSilence = 1500ms;
constexpr auto kFirstWord = 6s;
constexpr auto kStragglers = 1500ms;

/// One language's recognition of the shared microphone.
struct Lane {
    SpeechRecognizer recognizer{nullptr};
    IAsyncOperation<SpeechRecognitionResult> operation{nullptr};
    std::string text;
    double confidence = 0;
    bool done = false;
};

struct Session {
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<std::shared_ptr<Lane>> lanes;
    LayaSpeechCallback callback = nullptr;
    bool stopping = false;
};

std::mutex g_mutex;
std::shared_ptr<Session> g_session;

std::string lower(std::string s) {
    for (auto& c : s) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string language_of(std::string const& tag) { return lower(tag.substr(0, tag.find('-'))); }

/// The tags this PC's recognizer has a speech pack for (en-US, en-IN, …).
std::vector<std::string> supported_tags() {
    std::vector<std::string> tags;
    for (auto const& language : SpeechRecognizer::SupportedTopicLanguages()) tags.push_back(to_string(language.LanguageTag()));
    return tags;
}

/// One installed language per requested tag, one per language: the exact
/// variant if installed, else any variant of the language.
std::vector<std::string> resolve(std::vector<std::string> const& wanted) {
    auto installed = supported_tags();
    std::vector<std::string> picked;
    std::set<std::string> languages;
    for (auto const& tag : wanted) {
        auto language = language_of(tag);
        if (languages.count(language)) continue;
        std::string match;
        for (auto const& id : installed) if (lower(id) == lower(tag)) { match = id; break; }
        if (match.empty()) for (auto const& id : installed) if (language_of(id) == language) { match = id; break; }
        if (match.empty()) continue;
        languages.insert(language);
        picked.push_back(match);
    }
    return picked;
}

std::vector<std::string> split(char const* csv) {
    std::vector<std::string> out;
    std::stringstream stream(csv ? csv : "");
    for (std::string item; std::getline(stream, item, ',');) if (!item.empty()) out.push_back(item);
    return out;
}

void emit(LayaSpeechCallback callback, int kind, std::string const& text) {
    if (callback) callback(kind, text.c_str());
}

/// A recognizer for [tag], tuned for short web-search style queries; null when
/// Windows won't give one (no speech pack, online recognition off, …).
std::shared_ptr<Lane> lane_for(std::string const& tag) {
    try {
        auto lane = std::make_shared<Lane>();
        lane->recognizer = SpeechRecognizer(Language(to_hstring(tag)));
        lane->recognizer.Constraints().Append(
            SpeechRecognitionTopicConstraint(SpeechRecognitionScenario::WebSearch, L"search"));
        if (lane->recognizer.CompileConstraintsAsync().get().Status() != SpeechRecognitionResultStatus::Success) return nullptr;
        lane->recognizer.Timeouts().InitialSilenceTimeout(kFirstWord);
        lane->recognizer.Timeouts().EndSilenceTimeout(kEndSilence);
        return lane;
    } catch (...) {
        return nullptr;
    }
}

void run(std::shared_ptr<Session> session, std::vector<std::string> wanted) {
    init_apartment(apartment_type::multi_threaded);
    std::vector<std::shared_ptr<Lane>> lanes;
    try {
        for (auto const& tag : resolve(wanted)) if (auto lane = lane_for(tag)) lanes.push_back(lane);
    } catch (...) {}
    if (lanes.empty()) return emit(session->callback, 2, "");

    {
        std::lock_guard lock(session->mutex);
        session->lanes = lanes;
    }
    int started = 0;
    for (auto& lane : lanes) {
        lane->recognizer.HypothesisGenerated([session, lane](auto const&, SpeechRecognitionHypothesisGeneratedEventArgs const& args) {
            std::string heard = to_string(args.Hypothesis().Text());
            std::lock_guard lock(session->mutex);
            if (session->stopping || heard.empty()) return;
            lane->text = heard;
            // Live words from the first language, or from whichever has any.
            for (auto& other : session->lanes) {
                if (other->text.empty()) continue;
                if (other == lane) emit(session->callback, 0, heard);
                break;
            }
        });
        try {
            lane->operation = lane->recognizer.RecognizeAsync();
            lane->operation.Completed([session, lane](IAsyncOperation<SpeechRecognitionResult> const& operation, AsyncStatus status) {
                std::lock_guard lock(session->mutex);
                if (status == AsyncStatus::Completed) {
                    try {
                        auto result = operation.GetResults();
                        if (result.Status() == SpeechRecognitionResultStatus::Success && !result.Text().empty()) {
                            lane->text = to_string(result.Text());
                            lane->confidence = result.RawConfidence();
                        }
                    } catch (...) {}
                }
                lane->done = true;
                session->changed.notify_all();
            });
            started++;
        } catch (...) {
            std::lock_guard lock(session->mutex);
            lane->done = true;
        }
    }

    std::string text;
    {
        std::unique_lock lock(session->mutex);
        if (started == 0) {
            lock.unlock();
            for (auto& lane : lanes) {
                try { lane->recognizer.Close(); } catch (...) {}
                lane->recognizer = nullptr;
            }
            return emit(session->callback, 2, "");
        }
        auto all_done = [&] { for (auto& l : lanes) if (!l->done) return false; return true; };
        auto one_heard = [&] { for (auto& l : lanes) if (l->done && !l->text.empty()) return true; return false; };
        // One language finishing with words ends the query, after a moment for
        // the others; one giving up empty-handed leaves the others listening.
        session->changed.wait(lock, [&] { return session->stopping || all_done() || one_heard(); });
        if (!session->stopping && !all_done()) session->changed.wait_for(lock, kStragglers, [&] { return session->stopping || all_done(); });
        bool cancelled = !all_done();
        lock.unlock();
        if (cancelled) {
            for (auto& lane : lanes) if (lane->operation) try { lane->operation.Cancel(); } catch (...) {}
            lock.lock();
            session->changed.wait_for(lock, 1s, all_done);
            lock.unlock();
        }
        lock.lock();
        // The most confident language wins; one that never finished counts as
        // unsure, but its words so far still count when nothing else heard any.
        std::shared_ptr<Lane> best;
        for (auto& lane : lanes) {
            if (lane->text.empty()) continue;
            double score = lane->done ? lane->confidence : 0;
            if (!best || score > (best->done ? best->confidence : 0)) best = lane;
        }
        if (best) text = best->text;
        session->lanes.clear();
    }
    for (auto& lane : lanes) {
        try { lane->recognizer.Close(); } catch (...) {}
        // The handlers hold the lane; letting go of these breaks the cycle.
        lane->operation = nullptr;
        lane->recognizer = nullptr;
    }
    // An empty final still goes out, so the app stops showing it's listening.
    emit(session->callback, 1, text);
}

}  // namespace

/// [tags]: comma-separated BCP-47 tags, the one to show live first.
extern "C" __declspec(dllexport) void laya_speech_start(char const* tags, LayaSpeechCallback callback) {
    auto session = std::make_shared<Session>();
    session->callback = callback;
    {
        std::lock_guard lock(g_mutex);
        if (g_session) {
            std::lock_guard old(g_session->mutex);
            g_session->stopping = true;
            g_session->changed.notify_all();
        }
        g_session = session;
    }
    std::thread(run, session, split(tags)).detach();
}

extern "C" __declspec(dllexport) void laya_speech_stop() {
    std::lock_guard lock(g_mutex);
    if (!g_session) return;
    std::lock_guard inner(g_session->mutex);
    g_session->stopping = true;
    g_session->changed.notify_all();
}

/// The languages (en, hi, …) this PC's recognizer has, comma-separated.
/// Freed by nobody: called once per run. Asked on a thread of its own, so the
/// caller's COM apartment, whatever it is, doesn't matter.
extern "C" __declspec(dllexport) char* laya_speech_languages() {
    std::string joined;
    std::thread([&] {
        try {
            init_apartment(apartment_type::multi_threaded);
            std::set<std::string> languages;
            for (auto const& tag : supported_tags()) languages.insert(language_of(tag));
            for (auto const& language : languages) joined += (joined.empty() ? "" : ",") + language;
        } catch (...) {}
    }).join();
    return _strdup(joined.c_str());
}
