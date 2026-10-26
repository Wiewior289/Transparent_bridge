package main

/*
#cgo LDFLAGS: -llog
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

static JavaVM* g_vm = NULL;
static jobject g_jniListener = NULL;
static jmethodID g_onPacketMethod = NULL;
static jmethodID g_getInterfacesMethod = NULL;
static void bridgeAndroidLog(const char* msg) {
    __android_log_write(ANDROID_LOG_INFO, "TSBridgeGo", msg ? msg : "(null)");
}
static char* bridgeGetInterfacesJSON(void) {
    if (g_vm == NULL || g_jniListener == NULL || g_getInterfacesMethod == NULL) return NULL;
    JNIEnv* env = NULL; int attached = 0;
    jint status = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        attached = 1;
    } else if (status != JNI_OK) return NULL;
    jstring value = (jstring)(*env)->CallObjectMethod(env, g_jniListener, g_getInterfacesMethod);
    char* result = NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env);
    } else if (value != NULL) {
        const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
        if (chars != NULL) { result = strdup(chars); (*env)->ReleaseStringUTFChars(env, value, chars); }
        (*env)->DeleteLocalRef(env, value);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return result;
}

static jint initJniBridge(JNIEnv* env, jobject listener) {
    (*env)->GetJavaVM(env, &g_vm);
    if (g_jniListener != NULL) {
        (*env)->DeleteGlobalRef(env, g_jniListener);
    }
    g_jniListener = (*env)->NewGlobalRef(env, listener);
    jclass clazz = (*env)->GetObjectClass(env, g_jniListener);
    g_onPacketMethod = (*env)->GetMethodID(env, clazz, "onTailscalePacketReceived", "([BI)V");
    if (g_onPacketMethod == NULL) return -1;
    g_getInterfacesMethod = (*env)->GetMethodID(env, clazz, "getTailscaleInterfacesJson", "()Ljava/lang/String;");
    if (g_getInterfacesMethod == NULL) return -2;
    return 0;
}

// Zoptymalizowane wywołanie JNI z mechanizmem trwalego podpinania watku krypto WireGuarda
static void callJavaPacketCallback(const uint8_t* data, int len) {
    if (g_vm == NULL || g_jniListener == NULL || g_onPacketMethod == NULL) return;
    
    JNIEnv* env;
    int getEnvStat = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    int attached = 0;
    
    if (getEnvStat == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return;
        attached = 1;
    }
    
    jbyteArray jArr = (*env)->NewByteArray(env, len);
    if (jArr != NULL) {
        (*env)->SetByteArrayRegion(env, jArr, 0, len, (const jbyte*)data);
        (*env)->CallVoidMethod(env, g_jniListener, g_onPacketMethod, jArr, len);
        (*env)->DeleteLocalRef(env, jArr);
    }
    
    if (attached) {
        // Pozostawiamy podpiety dla petli krypto w celu redukcji narzutu CPU
    }
}

// Bezpieczne proxy dla kompilatora Cgo chroniace natywne typy JNI
static jstring cgoNewStringUTF(JNIEnv* env, const char* str) {
    return (*env)->NewStringUTF(env, str);
}

static const char* jniGetString(JNIEnv* env, jstring str) { return (*env)->GetStringUTFChars(env, str, NULL); }
static void jniReleaseString(JNIEnv* env, jstring str, const char* cStr) { (*env)->ReleaseStringUTFChars(env, str, cStr); }
static jbyte* jniGetByteArrayElements(JNIEnv* env, jbyteArray array) { return (*env)->GetByteArrayElements(env, array, NULL); }
static void jniReleaseByteArrayElements(JNIEnv* env, jbyteArray array, jbyte* elems) { (*env)->ReleaseByteArrayElements(env, array, elems, JNI_ABORT); }
static jsize jniGetByteArrayLength(JNIEnv* env, jbyteArray array) { return (*env)->GetArrayLength(env, array); }
*/
import "C"

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"unsafe"

	"github.com/tailscale/wireguard-go/tun" // Nagłówki tun.Device (Vector Packet Processing)
	"tailscale.com/control/controlclient"  // Definicja flag logowania i stanów kontrolnych
	"tailscale.com/ipn"                     // Obiekt preferencji ipn.NewPrefs / AdvertiseRoutes
	"tailscale.com/ipn/ipnlocal"            // Serce demona: menedżer ipnlocal.NewLocalBackend
	"tailscale.com/ipn/store/mem"           // Inicjalizacja bazy stanów w pamięci RAM
	"tailscale.com/net/netmon"              // Android interface provider for SDK 30+
	"tailscale.com/net/tsdial"              // Dialer wychodzący do połączeń sieciowych
	"tailscale.com/tsd"                     // Struktura systemowa tsd.System (kontener silnika)
	"tailscale.com/types/logid"             // Identyfikatory logowania logid.PublicID{}
	"tailscale.com/wgengine"                // Silnik przestrzeni użytkownika NewUserspaceEngine
	"tailscale.com/wgengine/router"         // Fałszywy router systemowy router.NewFake
)

type androidAddrJSON struct {
	IP string `json:"ip"`
	PrefixLen int `json:"prefixLen"`
}
type androidIfaceJSON struct {
	Name string `json:"name"`
	Index int `json:"index"`
	MTU int `json:"mtu"`
	Up bool `json:"up"`
	Broadcast bool `json:"broadcast"`
	Loopback bool `json:"loopback"`
	PointToPoint bool `json:"pointToPoint"`
	Multicast bool `json:"multicast"`
	Addrs []androidAddrJSON `json:"addrs"`
}
func bridgeLogf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	cmsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cmsg))
	C.bridgeAndroidLog(cmsg)
}
// androidInterfaceGetter avoids Go's default net.Interfaces() path, documented
// by Tailscale as unavailable on Android SDK 30+ under platform policy.
func androidInterfaceGetter() ([]netmon.Interface, error) {
	cJSON := C.bridgeGetInterfacesJSON()
	if cJSON == nil { return nil, fmt.Errorf("Android interface provider returned null JSON (JNI callback missing or failed)") }
	defer C.free(unsafe.Pointer(cJSON))
	var raw []androidIfaceJSON
	if err := json.Unmarshal([]byte(C.GoString(cJSON)), &raw); err != nil {
		return nil, fmt.Errorf("parse Android interface JSON: %w", err)
	}
	out := make([]netmon.Interface, 0, len(raw))
	for _, it := range raw {
		if it.Name == "" { continue }
		iface := &net.Interface{Name: it.Name, Index: it.Index, MTU: it.MTU}
		if it.Up { iface.Flags |= net.FlagUp }
		if it.Broadcast { iface.Flags |= net.FlagBroadcast }
		if it.Loopback { iface.Flags |= net.FlagLoopback }
		if it.PointToPoint { iface.Flags |= net.FlagPointToPoint }
		if it.Multicast { iface.Flags |= net.FlagMulticast }
		ni := netmon.Interface{Interface: iface, AltAddrs: []net.Addr{}}
		for _, addr := range it.Addrs {
			ip, ipNet, err := net.ParseCIDR(fmt.Sprintf("%s/%d", addr.IP, addr.PrefixLen))
			if err != nil { bridgeLogf("netmon: skip invalid address %q/%d on %s: %v", addr.IP, addr.PrefixLen, it.Name, err); continue }
			ipNet.IP = ip
			ni.AltAddrs = append(ni.AltAddrs, ipNet)
		}
		out = append(out, ni)
	}
	bridgeLogf("Android interface provider returned %d interfaces", len(out))
	return out, nil
}
type packetRequest struct {
	data []byte
}

type UserlandTun struct {
	events    chan tun.Event
	closed    chan struct{}
	ctx       context.Context
	incoming  chan packetRequest
	closeOnce sync.Once
}

func NewUserlandTun(ctx context.Context) *UserlandTun {
	t := &UserlandTun{
		events:   make(chan tun.Event, 1),
		closed:   make(chan struct{}),
		ctx:      ctx,
		incoming: make(chan packetRequest, 2048),
	}
	t.events <- tun.EventUp
	return t
}

func (t *UserlandTun) File() *os.File           { return nil }
func (t *UserlandTun) Flush() error             { return nil }
func (t *UserlandTun) MTU() (int, error)        { return 1360, nil }
func (t *UserlandTun) Name() (string, error)    { return "tailscale0", nil }
func (t *UserlandTun) Events() <-chan tun.Event { return t.events }
func (t *UserlandTun) BatchSize() int           { return 64 }

func (t *UserlandTun) Close() error {
	t.closeOnce.Do(func() {
		close(t.closed)
		close(t.events)
	})
	return nil
}

func (t *UserlandTun) Write(buffs [][]byte, offset int) (int, error) {
	for _, buf := range buffs {
		packetData := buf[offset:]
		if len(packetData) > 0 {
			C.callJavaPacketCallback((*C.uint8_t)(unsafe.Pointer(unsafe.SliceData(packetData))), C.int(len(packetData)))
		}
	}
	return len(buffs), nil
}

func (t *UserlandTun) Read(buffs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case <-t.closed:
		return 0, io.EOF
	case <-t.ctx.Done():
		return 0, io.EOF
	case req := <-t.incoming:
		n := copy(buffs[0][offset:], req.data)
		sizes[0] = n

		packetsRead := 1
		maxBatch := len(buffs)
		if maxBatch > 64 {
			maxBatch = 64
		}

		for packetsRead < maxBatch {
			select {
			case nextReq := <-t.incoming:
				nNext := copy(buffs[packetsRead][offset:], nextReq.data)
				sizes[packetsRead] = nNext
				packetsRead++
			default:
				return packetsRead, nil
			}
		}
		return packetsRead, nil
	}
}

type TailscaleL3Bridge struct {
	backend *ipnlocal.LocalBackend
	dialer  *tsdial.Dialer
	tun     *UserlandTun
	running bool
	ctx     context.Context
	cancel  context.CancelFunc
}

var (
	globalMu      sync.RWMutex
	globalBridge  *TailscaleL3Bridge
	globalStopping bool
)

//export Java_com_example_core_vpn_TailscaleBridgeJni_registerPacketListener
func Java_com_example_core_vpn_TailscaleBridgeJni_registerPacketListener(env *C.JNIEnv, thiz C.jobject, listener C.jobject) C.jint {
	return C.initJniBridge(env, listener)
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_startTsnet
func Java_com_example_core_vpn_TailscaleBridgeJni_startTsnet(env *C.JNIEnv, thiz C.jobject, jAuthKey, jNodeName, jRoutes, jDataDir C.jstring) C.jint {
	globalMu.Lock()
	defer globalMu.Unlock()

	// Do not create a second runtime while the previous one is still tearing down.
	if globalStopping {
		return -4
	}
	if globalBridge != nil && globalBridge.running {
		return 0
	}

	cAuthKey := C.jniGetString(env, jAuthKey)
	cNodeName := C.jniGetString(env, jNodeName)
	cRoutes := C.jniGetString(env, jRoutes)

	authKey := C.GoString(cAuthKey)
	nodeName := C.GoString(cNodeName)
	routesStr := C.GoString(cRoutes)

	C.jniReleaseString(env, jAuthKey, cAuthKey)
	C.jniReleaseString(env, jNodeName, cNodeName)
	C.jniReleaseString(env, jRoutes, cRoutes)
	cDataDir := C.jniGetString(env, jDataDir)
	dataDir := C.GoString(cDataDir)
	C.jniReleaseString(env, jDataDir, cDataDir)

	// Tailscale logpolicy needs a writable, stable directory for log identity
	// state. Android may not provide a usable default temp/cache directory to
	// this embedded process; otherwise NewLocalBackend can panic in LogsDir().
	if dataDir == "" {
		bridgeLogf("startup: app dataDir is empty; cannot safely initialize Tailscale log state")
		return -5
	}
	logsDir := filepath.Join(dataDir, "tailscale-logs")
	if err := os.MkdirAll(logsDir, 0700); err != nil {
		bridgeLogf("startup: create Tailscale log state directory %q failed: %v", logsDir, err)
		return -5
	}
	if err := os.Setenv("TS_LOGS_DIR", logsDir); err != nil {
		bridgeLogf("startup: set TS_LOGS_DIR failed: %v", err)
		return -5
	}
	bridgeLogf("startup: TS_LOGS_DIR configured to app-private directory %q", logsDir)

	ctx, cancel := context.WithCancel(context.Background())
	uTun := NewUserlandTun(ctx)

	sys := new(tsd.System)
	stateStore := new(mem.Store)
	sys.StateStore.Set(stateStore)
	bridgeLogf("startup: runtime allocated; registering Android netmon interface getter")
	netmon.RegisterInterfaceGetter(androidInterfaceGetter)
	dialer := &tsdial.Dialer{Logf: bridgeLogf}

	bridgeLogf("startup: NewUserspaceEngine begin (Android polling monitor + Android interface getter)")
	eng, err := wgengine.NewUserspaceEngine(
		bridgeLogf,
		wgengine.Config{
			Tun:           uTun,
			Router:        router.NewFake(bridgeLogf),
			Dialer:        dialer,
			SetSubsystem:  sys.Set,
			ControlKnobs:  sys.ControlKnobs(),
			HealthTracker: sys.HealthTracker(),
		},
	)
	if err != nil {
		bridgeLogf("startup: NewUserspaceEngine failed: %v", err)
		uTun.Close()
		dialer.Close()
		cancel()
		return -1
	}

	bridgeLogf("startup: NewUserspaceEngine success")
	sys.Set(eng)

	bridgeLogf("startup: NewLocalBackend begin")
	lb, err := ipnlocal.NewLocalBackend(
		bridgeLogf,
		logid.PublicID{},
		sys,
		controlclient.LoginFlags(0),
	)
	if err != nil {
		bridgeLogf("startup: NewLocalBackend failed: %v", err)
		eng.Close()
		cancel()
		return -2
}

	prefs := ipn.NewPrefs()
	prefs.WantRunning = true
	prefs.Hostname = nodeName

	if prefix, err := netip.ParsePrefix(routesStr); err == nil {
		prefs.AdvertiseRoutes = []netip.Prefix{prefix}
	}

	bridgeLogf("startup: LocalBackend.Start begin; auth key present=%t, routes=%s", authKey != "", routesStr)
	if err := lb.Start(ipn.Options{AuthKey: authKey, UpdatePrefs: prefs}); err != nil {
		bridgeLogf("startup: LocalBackend.Start failed: %v", err)
		lb.Shutdown()
		dialer.Close()
		cancel()
		return -3
	}

	bridgeLogf("startup: LocalBackend.Start returned successfully")
	globalBridge = &TailscaleL3Bridge{
		backend: lb,
		dialer:  dialer,
		tun:     uTun,
		running: true,
		ctx:     ctx,
		cancel:  cancel,
	}

	return 1
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_injectPacket
func Java_com_example_core_vpn_TailscaleBridgeJni_injectPacket(env *C.JNIEnv, thiz C.jobject, jPacket C.jbyteArray, jLen C.jint) C.jint {
	length := int(jLen)
	arrayLen := int(C.jniGetByteArrayLength(env, jPacket))

	if length <= 0 || length > arrayLen {
		return -2
	}

	goBytes := make([]byte, length)

	cBytes := C.jniGetByteArrayElements(env, jPacket)
	if cBytes == nil {
		return -3
	}
	C.memcpy(unsafe.Pointer(&goBytes[0]), unsafe.Pointer(cBytes), C.size_t(length))
	C.jniReleaseByteArrayElements(env, jPacket, cBytes)

	// Keep the bridge protected until the packet has been queued. stopTsnet
	// cannot tear down the TUN while this operation is in flight.
	globalMu.RLock()
	defer globalMu.RUnlock()

	bridge := globalBridge
	if bridge == nil || !bridge.running {
		return -1
	}

	select {
	case bridge.tun.incoming <- packetRequest{data: goBytes}:
		return C.jint(length)
	default:
		return 0
	}
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_stopTsnet
func Java_com_example_core_vpn_TailscaleBridgeJni_stopTsnet(env *C.JNIEnv, thiz C.jobject) C.jint {
	globalMu.Lock()
	bridge := globalBridge
	if bridge == nil || !bridge.running {
		globalMu.Unlock()
		return 0
	}

	// Detach the runtime from the global entry point before teardown so new
	// JNI calls cannot acquire a bridge that is shutting down.
	bridge.running = false
	globalBridge = nil
	globalStopping = true
	globalMu.Unlock()

	bridge.cancel()
	bridge.backend.Shutdown()
	bridge.dialer.Close()
	_ = bridge.tun.Close()

	globalMu.Lock()
	globalStopping = false
	globalMu.Unlock()
	return 1
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_getAssignedTailnetIp
func Java_com_example_core_vpn_TailscaleBridgeJni_getAssignedTailnetIp(env *C.JNIEnv, thiz C.jobject) C.jstring {
	globalMu.RLock()
	defer globalMu.RUnlock()

	bridge := globalBridge
	if bridge == nil || !bridge.running {
		cZero := C.CString("0.0.0.0")
		defer C.free(unsafe.Pointer(cZero))
		return C.jstring(C.cgoNewStringUTF(env, cZero))
	}

	status := bridge.backend.Status()
	if status != nil && len(status.TailscaleIPs) > 0 {
		cIp := C.CString(status.TailscaleIPs[0].String())
		defer C.free(unsafe.Pointer(cIp))
		return C.jstring(C.cgoNewStringUTF(env, cIp))
	}

	cZero := C.CString("0.0.0.0")
	defer C.free(unsafe.Pointer(cZero))
	return C.jstring(C.cgoNewStringUTF(env, cZero))
}

func main() {}
