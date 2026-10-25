package main

/*
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

static JavaVM* g_vm = NULL;
static jobject g_jniListener = NULL;
static jmethodID g_onPacketMethod = NULL;

static jint initJniBridge(JNIEnv* env, jobject listener) {
    (*env)->GetJavaVM(env, &g_vm);
    if (g_jniListener != NULL) {
        (*env)->DeleteGlobalRef(env, g_jniListener);
    }
    g_jniListener = (*env)->NewGlobalRef(env, listener);
    jclass clazz = (*env)->GetObjectClass(env, g_jniListener);
    g_onPacketMethod = (*env)->GetMethodID(env, clazz, "onTailscalePacketReceived", "([BI)V");
    if (g_onPacketMethod == NULL) return -1;
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
	"fmt"
	"io"
	"net/netip"
	"os"
	"sync"
	"unsafe"

	"github.com/tailscale/wireguard-go/tun" // Nagłówki tun.Device (Vector Packet Processing)
	"tailscale.com/control/controlclient"  // Definicja flag logowania i stanów kontrolnych
	"tailscale.com/ipn"                     // Obiekt preferencji ipn.NewPrefs / AdvertiseRoutes
	"tailscale.com/ipn/ipnlocal"            // Serce demona: menedżer ipnlocal.NewLocalBackend
	"tailscale.com/ipn/store/mem"           // Inicjalizacja bazy stanów w pamięci RAM
	"tailscale.com/net/tsdial"              // Dialer wychodzący do połączeń sieciowych
	"tailscale.com/tsd"                     // Struktura systemowa tsd.System (kontener silnika)
	"tailscale.com/types/logger"            // Wyłączenie logowania przez logger.Discard
	"tailscale.com/types/logid"             // Identyfikatory logowania logid.PublicID{}
	"tailscale.com/wgengine"                // Silnik przestrzeni użytkownika NewUserspaceEngine
	"tailscale.com/wgengine/router"         // Fałszywy router systemowy router.NewFake
)

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
func Java_com_example_core_vpn_TailscaleBridgeJni_startTsnet(env *C.JNIEnv, thiz C.jobject, jAuthKey, jNodeName, jRoutes C.jstring) C.jint {
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

	ctx, cancel := context.WithCancel(context.Background())
	uTun := NewUserlandTun(ctx)

	sys := new(tsd.System)
	stateStore := new(mem.Store)
	sys.StateStore.Set(stateStore)
	dialer := &tsdial.Dialer{Logf: logger.Discard}

	eng, err := wgengine.NewUserspaceEngine(
		logger.Discard,
		wgengine.Config{
			Tun:           uTun,
			Router:        router.NewFake(logger.Discard),
			Dialer:        dialer,
			SetSubsystem:  sys.Set,
			ControlKnobs:  sys.ControlKnobs(),
			HealthTracker: sys.HealthTracker(),
		},
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "NewUserspaceEngine failed: %v\n", err)
		uTun.Close()
		dialer.Close()
		cancel()
		return -1
	}

	sys.Set(eng)

	lb, err := ipnlocal.NewLocalBackend(
		logger.Discard,
		logid.PublicID{},
		sys,
		controlclient.LoginFlags(0),
	)
	if err != nil {
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

	if err := lb.Start(ipn.Options{AuthKey: authKey, UpdatePrefs: prefs}); err != nil {
		lb.Shutdown()
		dialer.Close()
		cancel()
		return -3
	}

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
