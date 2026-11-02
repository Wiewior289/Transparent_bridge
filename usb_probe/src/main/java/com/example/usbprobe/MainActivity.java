package com.example.usbprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String ACTION_PERMISSION = "com.example.usbprobe.USB_PERMISSION";
    private static final int VID = 0x1A86, PID = 0x5394;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private UsbManager manager;
    private TextView output;
    private UsbDevice selected;
    private UsbDeviceConnection connection;
    private UsbInterface control, data;
    private UsbEndpoint bulkIn, bulkOut;
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_PERMISSION.equals(i.getAction())) return;
            boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("USB permission=" + granted);
            if (granted && selected != null) io.execute(() -> openAndProbe(selected));
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        manager = (UsbManager)getSystemService(USB_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(12,12,12,12); root.setOrientation(LinearLayout.VERTICAL);
        addButton(root, "1. Scan + parse CDC descriptors", v -> scan());
        addButton(root, "2. Send isolated test Ethernet frame", v -> io.execute(this::sendTest));
        addButton(root, "3. Receive one bulk transfer (1500 ms)", v -> io.execute(this::receiveOne));
        addButton(root, "Release interfaces / close", v -> io.execute(this::closeDevice));
        output = new TextView(this); output.setTextSize(12); output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver,f);
        receiverRegistered = true;
        log("Generic CDC probe ready for 1A86:5394.");
        log("Claiming interfaces can detach cdc_ether and remove eth0.");
    }

    private void addButton(LinearLayout root, String label, android.view.View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(click); root.addView(b);
    }

    private void scan() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            boolean match = d.getVendorId()==VID && d.getProductId()==PID;
            log(String.format(Locale.US,"DEVICE %04X:%04X name=%s configurations=%d exposedInterfaces=%d",
                    d.getVendorId(),d.getProductId(),d.getDeviceName(),d.getConfigurationCount(),d.getInterfaceCount()));
            // Enumerate every configuration. A descriptor in a non-active configuration is not usable yet.
            for (int ci=0;ci<d.getConfigurationCount();ci++) {
                UsbConfiguration cfg=d.getConfiguration(ci);
                log(String.format(Locale.US," CONFIG index=%d value=%d name=%s interfaces=%d",
                        ci,cfg.getId(),cfg.getName(),cfg.getInterfaceCount()));
                for (int i=0;i<cfg.getInterfaceCount();i++) {
                    UsbInterface in=cfg.getInterface(i);
                    log(String.format(Locale.US,"  IF[%d:%d] id=%d alt=%d class=%02X sub=%02X proto=%02X endpoints=%d",
                            ci,i,in.getId(),in.getAlternateSetting(),in.getInterfaceClass(),in.getInterfaceSubclass(),
                            in.getInterfaceProtocol(),in.getEndpointCount()));
                    for(int e=0;e<in.getEndpointCount();e++) {
                        UsbEndpoint ep=in.getEndpoint(e);
                        log(String.format(Locale.US,"   EP %02X %s type=%d packet=%d",ep.getAddress(),
                                ep.getDirection()==UsbConstants.USB_DIR_IN?"IN":"OUT",ep.getType(),ep.getMaxPacketSize()));
                    }
                    if(in.getInterfaceClass()==2 && in.getInterfaceSubclass()==6 && in.getInterfaceProtocol()==0) {
                        log("GENERIC MATCH: class=COMM subclass=ETHERNET protocol=NONE configValue="+cfg.getId());
                        match=true;
                    }
                }
            }
            if(match) {
                selected=d;
                if(manager.hasPermission(d)) io.execute(()->openAndProbe(d));
                else {
                    int flags=PendingIntent.FLAG_UPDATE_CURRENT;
                    if(Build.VERSION.SDK_INT>=31) flags|=PendingIntent.FLAG_MUTABLE;
                    manager.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(ACTION_PERMISSION),flags));
                    log("USB permission requested.");
                }
                return;
            }
        }
        log("No target VID:PID or CDC Ethernet control interface found.");
    }

    private void openAndProbe(UsbDevice d) {
        closeDevice();
        UsbDeviceConnection c=manager.openDevice(d);
        if(c==null){log("OPEN FAILED");return;}
        connection=c; log("OPEN OK fd="+c.getFileDescriptor());
        byte[] activeValue=new byte[1];
        int activeResult=c.controlTransfer(0x80,0x08,0,0,activeValue,1,1000); // GET_CONFIGURATION
        int activeConfigurationValue=activeResult==1?(activeValue[0]&0xFF):-1;
        log("GET_CONFIGURATION result="+activeResult+" activeValue="+(activeConfigurationValue<0?"UNKNOWN":activeConfigurationValue));
        int master=-1,slave=-1,targetConfigurationValue=-1;
        boolean rawDescriptors=false, cdcHeader=false, cdcEthernet=false, cdcUnion=false;
        // wValue low byte is the configuration descriptor index, not bConfigurationValue.
        // Probe every configuration because this adapter exposes a vendor-specific config
        // and a separate CDC Ethernet config.
        for(int cfgIndex=0;cfgIndex<d.getConfigurationCount();cfgIndex++) {
            byte[] raw=new byte[4096];
            int n=c.controlTransfer(0x80,0x06,0x0200|cfgIndex,0,raw,raw.length,1500);
            log("GET_DESCRIPTOR(CONFIG index="+cfgIndex+")="+n);
            if(n<=0) continue;
            rawDescriptors=true;
            int rawConfigurationValue=n>=6?(raw[5]&0xFF):-1;
            log("CONFIG descriptor index="+cfgIndex+" returned="+n+" bConfigurationValue="+rawConfigurationValue+
                    (n>=4?" wTotalLength="+(((raw[3]&0xFF)<<8)|(raw[2]&0xFF)):""));
            int candidateMaster=-1,candidateSlave=-1;
            boolean hasCdcEtherControl=false, hasHeader=false, hasEthernet=false;
            for(int p=0;p+2<=n;) {
                int len=raw[p]&255,type=raw[p+1]&255;
                if(len<2||p+len>n){log("Malformed descriptor offset="+p);break;}
                if(type==4&&len>=9) {
                    int num=raw[p+2]&255,alt=raw[p+3]&255,cls=raw[p+5]&255,sub=raw[p+6]&255,proto=raw[p+7]&255;
                    log(String.format(Locale.US,"CFG%d IF num=%d alt=%d class=%02X sub=%02X proto=%02X",cfgIndex,num,alt,cls,sub,proto));
                    if(cls==2&&sub==6&&proto==0)hasCdcEtherControl=true;
                }
                if(type==0x24&&len>=3) {
                    int sub=raw[p+2]&255;
                    if(sub==6&&len>=5){candidateMaster=raw[p+3]&255;candidateSlave=raw[p+4]&255;log("CDC UNION master="+candidateMaster+" slave="+candidateSlave);}
                    else if(sub==0){hasHeader=true;log("CDC HEADER descriptor");}
                    else if(sub==0x0F){hasEthernet=true;log("CDC ETHERNET descriptor len="+len);}
                    else log(String.format(Locale.US,"CDC functional subtype=%02X len=%d",sub,len));
                }
                p+=len;
            }
            if(hasCdcEtherControl && candidateMaster>=0) {
                master=candidateMaster;slave=candidateSlave;
                targetConfigurationValue=rawConfigurationValue;
                cdcHeader=hasHeader;cdcEthernet=hasEthernet;cdcUnion=true;
                log("ECM candidate configValue="+targetConfigurationValue+" control="+master+" data="+slave);
            }
        }
        if(!rawDescriptors)log("Raw configuration descriptor unavailable; using exposed interface descriptors only.");
        if(rawDescriptors && !(cdcHeader && cdcEthernet && cdcUnion)) {
            log("KERNEL-EQUIVALENT CDC VALIDATION FAILED: required HEADER + UNION + ETHERNET descriptors not all present.");
            log("Stop before claiming interfaces; do not substitute a vendor protocol without evidence.");
            return;
        }
        if(rawDescriptors)log("KERNEL-EQUIVALENT CDC validation passed: HEADER + UNION + ETHERNET.");

        UsbConfiguration targetConfig=null;
        for(int ci=0;ci<d.getConfigurationCount();ci++) {
            UsbConfiguration cfg=d.getConfiguration(ci);
            boolean hasControl=false,hasData=false;
            for(int i=0;i<cfg.getInterfaceCount();i++) {
                UsbInterface in=cfg.getInterface(i);
                if(in.getInterfaceClass()==2&&in.getInterfaceSubclass()==6&&(master<0||in.getId()==master)) hasControl=true;
                if(in.getInterfaceClass()==10&&(slave<0||in.getId()==slave)&&hasBulkPair(in)) hasData=true;
            }
            if(hasControl&&hasData) {
                if(targetConfigurationValue<0) targetConfigurationValue=cfg.getId();
                if(cfg.getId()==targetConfigurationValue) targetConfig=cfg;
            }
        }
        if(activeConfigurationValue<0) {
            log("STOP: GET_CONFIGURATION did not return one byte. Active configuration is unverified; no interfaces claimed.");
            return;
        }
        if(targetConfigurationValue<0||targetConfig==null) {
            log("STOP: could not map CDC control/data interfaces to one configuration value. No interfaces claimed.");
            return;
        }
        log("CONFIG CHECK active="+activeConfigurationValue+" targetECM="+targetConfigurationValue);
        if(activeConfigurationValue!=targetConfigurationValue) {
            log("STOP: CDC-ECM is not the active USB configuration. Android USB Host API has no public setConfiguration(); this probe will not claim interfaces from another configuration.");
            return;
        }

        for(int i=0;i<targetConfig.getInterfaceCount();i++) {
            UsbInterface in=targetConfig.getInterface(i);
            if(in.getInterfaceClass()==2&&in.getInterfaceSubclass()==6&&(master<0||in.getId()==master)) control=in;
            if(in.getInterfaceClass()==10&&(slave<0||in.getId()==slave)&&in.getAlternateSetting()>0&&hasBulkPair(in)) data=in;
        }
        if(control==null){log("No CDC Ethernet control interface; no vendor-protocol guess made.");return;}
        if(data==null) for(int i=0;i<targetConfig.getInterfaceCount();i++){
            UsbInterface in=targetConfig.getInterface(i);
            if(in.getInterfaceClass()==10&&hasBulkPair(in)){data=in;log("Fallback DATA interface by class + bulk pair; verify Union mapping.");break;}
        }
        if(data==null){log("No CDC DATA alternate setting with bulk IN+OUT.");return;}
        for(int i=0;i<data.getEndpointCount();i++){
            UsbEndpoint ep=data.getEndpoint(i);
            if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
                if(ep.getDirection()==UsbConstants.USB_DIR_IN)bulkIn=ep;
                if(ep.getDirection()==UsbConstants.USB_DIR_OUT)bulkOut=ep;
            }
        }
        log("Chosen control id="+control.getId()+" alt="+control.getAlternateSetting());
        log("Chosen data id="+data.getId()+" alt="+data.getAlternateSetting());
        boolean cc=c.claimInterface(control,true);log("CLAIM control="+cc);
        if(!cc)return;
        boolean dc=c.claimInterface(data,true);log("CLAIM data="+dc);
        if(!dc){c.releaseInterface(control);return;}
        log("SET_INTERFACE data="+c.setInterface(data)+" alt="+data.getAlternateSetting());
        int filter=c.controlTransfer(0x21,0x43,0x000C,control.getId(),null,0,1500);
        log("CDC SET_ETHERNET_PACKET_FILTER directed+broadcast result="+filter+" (expected 0)");
        log("READY means setup succeeded, not that Ethernet RX/TX is proven.");
    }

    private boolean hasBulkPair(UsbInterface in){
        boolean rx=false,tx=false;
        for(int i=0;i<in.getEndpointCount();i++){
            UsbEndpoint ep=in.getEndpoint(i);
            if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
                if(ep.getDirection()==UsbConstants.USB_DIR_IN)rx=true;
                if(ep.getDirection()==UsbConstants.USB_DIR_OUT)tx=true;
            }
        }
        return rx&&tx;
    }

    private void sendTest(){
        UsbDeviceConnection c=connection;UsbEndpoint ep=bulkOut;
        if(c==null||ep==null){log("TX skipped: not configured");return;}
        byte[] f=new byte[60];Arrays.fill(f,0,6,(byte)0xFF);
        byte[] src={2,0x11,0x22,0x33,0x44,0x55};System.arraycopy(src,0,f,6,6);
        f[12]=(byte)0x88;f[13]=(byte)0xB5;
        byte[] marker="CDC_PROBE_1A865394".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(marker,0,f,14,marker.length);
        int r=c.bulkTransfer(ep,f,f.length,1500);
        log("TX EtherType=88B5 len=60 result="+r+"; positive means host USB accepted bytes, not peer delivery.");
    }

    private void receiveOne(){
        UsbDeviceConnection c=connection;UsbEndpoint ep=bulkIn;
        if(c==null||ep==null){log("RX skipped: not configured");return;}
        byte[] b=new byte[4096];int r=c.bulkTransfer(ep,b,b.length,1500);
        if(r<=0){log("RX result="+r+" (timeout/error; correlate with kernel logs)");return;}
        StringBuilder s=new StringBuilder();for(int i=0;i<Math.min(r,64);i++)s.append(String.format(Locale.US,"%02X ",b[i]&255));
        log("RX result="+r+" first bytes="+s);
    }

    private void closeDevice(){
        UsbDeviceConnection c=connection;
        if(c!=null){
            if(data!=null)try{log("RELEASE data="+c.releaseInterface(data));}catch(Exception e){log("release data: "+e);}
            if(control!=null&&control!=data)try{log("RELEASE control="+c.releaseInterface(control));}catch(Exception e){log("release control: "+e);}
            c.close();
        }
        connection=null;control=null;data=null;bulkIn=null;bulkOut=null;log("USB connection closed.");
    }

    private void log(String m){runOnUiThread(()->{output.append(m+"\n");android.util.Log.i("GenericCdcProbe",m);});}
    @Override protected void onDestroy(){
        io.execute(this::closeDevice);io.shutdown();
        if(receiverRegistered)unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }
}
