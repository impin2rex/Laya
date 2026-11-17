// Music recognition's result as a native Windows notification, silent, with a
// Try again button: what AWT's tray balloon can't be. The same C API as
// LayaNotify.swift (macOS), called from the JVM through JNA
// (DesktopRecognitionNotifier.kt). Built by native/window's CMake project with
// MinGW, against the Windows Runtime's ABI headers.
//
// An unpackaged app may only raise notifications under an AppUserModelID that a
// Start-menu shortcut carries. The installer's shortcut has none, so
// laya_notify_available() writes ours onto it; a copy with no shortcut (the
// portable zip) reports false and keeps the tray message.

#include <windows.h>
#include <initguid.h>
#include <propkey.h>
#include <propvarutil.h>
#include <roapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <winstring.h>
// MinGW's windows.foundation.h defines IReference<boolean> and IReference<UINT8> — both unsigned char, so one
// type twice. Neither is used here; skipping the first lets the header compile.
#define ____FIReference_1_boolean_INTERFACE_DEFINED__
#include <windows.data.xml.dom.h>
#include <windows.ui.notifications.h>
#include <wrl/client.h>
#include <wrl/wrappers/corewrappers.h>

#include <atomic>

#include <mutex>
#include <string>
#include <thread>

using namespace ABI::Windows::Data::Xml::Dom;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::UI::Notifications;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Wrappers::HStringReference;

// action: 0 = the notification was clicked, 1 = Try again.
typedef void (*laya_notify_callback)(int32_t action);

namespace {

const wchar_t* const kAumid = L"Laya.Desktop";

std::mutex lock;
laya_notify_callback callback = nullptr;
// Kept alive: Windows raises Activated on them only while they exist.
ComPtr<IToastNotifier> notifier;
ComPtr<IToastNotification> shown;

std::wstring wide(const char* utf8) {
    if (!utf8) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    std::wstring out(n > 0 ? n - 1 : 0, L'\0');
    if (n > 1) MultiByteToWideChar(CP_UTF8, 0, utf8, -1, &out[0], n);
    return out;
}

std::wstring escaped(const std::wstring& text) {
    std::wstring out;
    for (wchar_t c : text) {
        switch (c) {
            case L'&': out += L"&amp;"; break;
            case L'<': out += L"&lt;"; break;
            case L'>': out += L"&gt;"; break;
            case L'"': out += L"&quot;"; break;
            default: out += c;
        }
    }
    return out;
}

/** A click on the notification or its button, handed to [callback]. By hand: MinGW's WRL has no Callback(). */
using ActivatedEvent = ITypedEventHandler<ToastNotification*, IInspectable*>;

struct ActivatedHandler : ActivatedEvent {
    std::atomic<ULONG> refs{1};
    virtual ~ActivatedHandler() = default;

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (iid == IID_IUnknown || iid == IID_IAgileObject ||
            iid == __uuidof(ActivatedEvent)) {
            *out = this;
            AddRef();
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG left = --refs;
        if (left == 0) delete this;
        return left;
    }

    HRESULT STDMETHODCALLTYPE Invoke(IToastNotification*, IInspectable* args) override {
        int32_t action = 0;
        ComPtr<IToastActivatedEventArgs> activated;
        if (args && SUCCEEDED(args->QueryInterface(IID_PPV_ARGS(&activated)))) {
            HSTRING arguments = nullptr;
            if (SUCCEEDED(activated->get_Arguments(&arguments))) {
                action = wcscmp(WindowsGetStringRawBuffer(arguments, nullptr), L"retry") == 0 ? 1 : 0;
                WindowsDeleteString(arguments);
            }
        }
        laya_notify_callback cb;
        {
            std::lock_guard<std::mutex> guard(lock);
            cb = callback;
        }
        if (cb) cb(action);
        return S_OK;
    }
};

/** The installer's Start-menu shortcut, given our AppUserModelID if it lacks it. */
bool stampShortcut() {
    wchar_t programs[MAX_PATH];
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_PROGRAMS, nullptr, 0, programs))) return false;
    std::wstring path = std::wstring(programs) + L"\\Laya\\Laya.lnk";
    if (GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES) return false;

    ComPtr<IShellLinkW> link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) return false;
    ComPtr<IPersistFile> file;
    if (FAILED(link.As(&file)) || FAILED(file->Load(path.c_str(), STGM_READWRITE))) return false;
    ComPtr<IPropertyStore> store;
    if (FAILED(link.As(&store))) return false;

    PROPVARIANT current;
    PropVariantInit(&current);
    bool stamped = SUCCEEDED(store->GetValue(PKEY_AppUserModel_ID, &current)) &&
        current.vt == VT_LPWSTR && wcscmp(current.pwszVal, kAumid) == 0;
    PropVariantClear(&current);
    if (stamped) return true;

    PROPVARIANT id;
    if (FAILED(InitPropVariantFromString(kAumid, &id))) return false;
    bool ok = SUCCEEDED(store->SetValue(PKEY_AppUserModel_ID, id)) && SUCCEEDED(store->Commit()) &&
        SUCCEEDED(file->Save(path.c_str(), TRUE));
    PropVariantClear(&id);
    return ok;
}

void show(std::wstring title, std::wstring body, std::wstring retry) {
    RoInitialize(RO_INIT_MULTITHREADED);
    std::wstring xml = L"<toast launch=\"open\"><visual><binding template=\"ToastGeneric\"><text>" + escaped(title) +
        L"</text><text>" + escaped(body) + L"</text></binding></visual><audio silent=\"true\"/>";
    if (!retry.empty()) {
        xml += L"<actions><action content=\"" + escaped(retry) +
            L"\" arguments=\"retry\" activationType=\"foreground\"/></actions>";
    }
    xml += L"</toast>";

    ComPtr<IInspectable> inspectable;
    ComPtr<IXmlDocument> doc;
    ComPtr<IXmlDocumentIO> io;
    if (FAILED(RoActivateInstance(HStringReference(RuntimeClass_Windows_Data_Xml_Dom_XmlDocument).Get(), &inspectable)) ||
        FAILED(inspectable.As(&doc)) || FAILED(doc.As(&io)) ||
        FAILED(io->LoadXml(HStringReference(xml.c_str()).Get()))) {
        return;
    }

    ComPtr<IToastNotificationManagerStatics> manager;
    ComPtr<IToastNotificationFactory> factory;
    ComPtr<IToastNotifier> toaster;
    ComPtr<IToastNotification> toast;
    if (FAILED(RoGetActivationFactory(
            HStringReference(RuntimeClass_Windows_UI_Notifications_ToastNotificationManager).Get(), IID_PPV_ARGS(&manager))) ||
        FAILED(manager->CreateToastNotifierWithId(HStringReference(kAumid).Get(), &toaster)) ||
        FAILED(RoGetActivationFactory(
            HStringReference(RuntimeClass_Windows_UI_Notifications_ToastNotification).Get(), IID_PPV_ARGS(&factory))) ||
        FAILED(factory->CreateToastNotification(doc.Get(), &toast))) {
        return;
    }

    // One at a time: a newer result replaces the older one in the Action Center too.
    ComPtr<IToastNotification2> tagged;
    if (SUCCEEDED(toast.As(&tagged))) tagged->put_Tag(HStringReference(L"recognition").Get());

    EventRegistrationToken token;
    ComPtr<ActivatedHandler> handler;
    handler.Attach(new ActivatedHandler());
    toast->add_Activated(handler.Get(), &token);

    {
        std::lock_guard<std::mutex> guard(lock);
        notifier = toaster;
        shown = toast;
    }
    toaster->Show(toast.Get());
}

}  // namespace

extern "C" __declspec(dllexport) bool laya_notify_available() {
    // On a thread of our own, out of whatever COM apartment the JVM's caller is in.
    bool ok = false;
    std::thread([&ok] {
        CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        ok = stampShortcut();
        CoUninitialize();
    }).join();
    return ok;
}

extern "C" __declspec(dllexport) void laya_notify_post(
    const char* title, const char* body, const char* retryLabel, laya_notify_callback cb) {
    {
        std::lock_guard<std::mutex> guard(lock);
        callback = cb;
    }
    std::thread(show, wide(title), wide(body), wide(retryLabel)).detach();
}
