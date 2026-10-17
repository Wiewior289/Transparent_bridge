package main

/*
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>

// JNI callback definition for packet delivery
static JavaVM* g_vm = NULL;
static jobject g_jniListener = NULL;
static jmethodID g_onPacketMethod = NULL;

static void callJavaPacketCallback(const uint8_t* data, int len) {
    if (!g_vm || !g_jniListener || !g_onPacketMethod) return;
    JNIEnv* env = NULL;
    (*g_vm)->AttachCurrentThread(g_vm, (void**)&env, NULL);
    if (!env) return;

    jbyteArray jArr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, jArr, 0, len, (const jbyte*)data);
    (*env)->CallVoidMethod(env, g_jniListener, g_onPacketMethod, jArr, len);
    (*env)->DeleteLocalRef(env, jArr);
}
*/
import "C"
import (
	"context"
	"fmt"
	"net/netip"
	"sync"
	"unsafe"

	"tailscale.com/net/netstack"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

// TsnetServerBridge holds the running tsnet server and gVisor netstack instance in RAM.
type TsnetServerBridge struct {
	server    *tsnet.Server
	netstack  *netstack.Impl
	mu        sync.Mutex
	running   bool
	tailnetIp string
	ctx       context.Context
	cancel    context.CancelFunc
}

var globalBridge *TsnetServerBridge

//export Java_com_example_core_vpn_TailscaleBridgeJni_startTsnet
func Java_com_example_core_vpn_TailscaleBridgeJni_startTsnet(env *C.JNIEnv, thiz C.jobject, jAuthKey, jNodeName, jRoutes, jDataDir C.jstring) C.jint {
	cAuthKey := (*env)->GetStringUTFChars(env, jAuthKey, nil)
	cNodeName := (*env)->GetStringUTFChars(env, jNodeName, nil)
	cRoutes := (*env)->GetStringUTFChars(env, jRoutes, nil)
	cDataDir := (*env)->GetStringUTFChars(env, jDataDir, nil)

	authKey := C.GoString(cAuthKey)
	nodeName := C.GoString(cNodeName)
	routesStr := C.GoString(cRoutes)
	dataDir := C.GoString(cDataDir)

	(*env)->ReleaseStringUTFChars(env, jAuthKey, cAuthKey)
	(*env)->ReleaseStringUTFChars(env, jNodeName, cNodeName)
	(*env)->ReleaseStringUTFChars(env, jRoutes, cRoutes)
	(*env)->ReleaseStringUTFChars(env, jDataDir, cDataDir)

	if globalBridge != nil && globalBridge.running {
		return 0 // already running
	}

	ctx, cancel := context.WithCancel(context.Background())

	// 1. Initialize tsnet.Server in RAM with user-specified parameters
	s := &tsnet.Server{
		Hostname:  nodeName,
		AuthKey:   authKey,
		Dir:       dataDir,
		Ephemeral: true,
		Logf:      logger.Discard, // Low RAM footprint, prevents GC pressure
	}

	if err := s.Start(); err != nil {
		cancel()
		return -1
	}

	// 2. Activate gVisor (netstack) in RAM inside tsnet for raw L3 IP packet streaming
	ns, err := netstack.Create(logger.Discard, s.SysManager())
	if err != nil {
		s.Close()
		cancel()
		return -2
	}
	s.SetNetstack(ns)

	// 3. Advertise Subnet Route (e.g. 192.168.1.0/24) to Tailnet cloud
	prefix, err := netip.ParsePrefix(routesStr)
	if err == nil {
		s.UpdateEndpoints([]netip.Prefix{prefix})
	}

	bridge := &TsnetServerBridge{
		server:   s,
		netstack: ns,
		running:  true,
		ctx:      ctx,
		cancel:   cancel,
	}

	// Retrieve assigned Tailnet IP
	lc, err := s.LocalClient()
	if err == nil {
		status, err := lc.Status(ctx)
		if err == nil && len(status.TailscaleIPs) > 0 {
			bridge.tailnetIp = status.TailscaleIPs[0].String()
		}
	}

	globalBridge = bridge
	return 1
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_stopTsnet
func Java_com_example_core_vpn_TailscaleBridgeJni_stopTsnet(env *C.JNIEnv, thiz C.jobject) C.jint {
	if globalBridge == nil || !globalBridge.running {
		return 0
	}
	globalBridge.mu.Lock()
	defer globalBridge.mu.Unlock()

	if globalBridge.cancel != nil {
		globalBridge.cancel()
	}
	if globalBridge.server != nil {
		globalBridge.server.Close()
	}
	globalBridge.running = false
	globalBridge = nil
	return 1
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_injectPacket
func Java_com_example_core_vpn_TailscaleBridgeJni_injectPacket(env *C.JNIEnv, thiz C.jobject, jPacket C.jbyteArray, jLen C.jint) C.jint {
	if globalBridge == nil || !globalBridge.running || globalBridge.netstack == nil {
		return -1
	}
	length := int(jLen)
	cBytes := (*env)->GetByteArrayElements(env, jPacket, nil)
	goBytes := C.GoBytes(unsafe.Pointer(cBytes), C.int(length))
	(*env)->ReleaseByteArrayElements(env, jPacket, cBytes, C.JNI_ABORT)

	// Feed raw packet into gVisor netstack
	globalBridge.netstack.ReceiveIPv4(goBytes)
	return C.jint(length)
}

//export Java_com_example_core_vpn_TailscaleBridgeJni_getAssignedTailnetIp
func Java_com_example_core_vpn_TailscaleBridgeJni_getAssignedTailnetIp(env *C.JNIEnv, thiz C.jobject) C.jstring {
	if globalBridge == nil || !globalBridge.running || globalBridge.tailnetIp == "" {
		return (*env)->NewStringUTF(env, C.CString("100.64.0.1"))
	}
	cStr := C.CString(globalBridge.tailnetIp)
	defer C.free(unsafe.Pointer(cStr))
	return (*env)->NewStringUTF(env, cStr)
}

func main() {}
