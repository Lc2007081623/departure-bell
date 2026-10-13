package cn.departurebell.control;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All GATT requests and callback handling run on the main looper. */
public final class BellBleClient {
    public interface Listener {
        void onStatus(String message);
        void onReady(boolean ready);
        void onState(BellState state);
        void onAck(String message);
    }
    public static final UUID SERVICE=UUID.fromString("c3b10000-7a6b-4c90-8d12-123456789abc");
    private static UUID id(int n){return UUID.fromString(String.format(Locale.US,"c3b1000%d-7a6b-4c90-8d12-123456789abc",n));}
    private static final UUID COMMAND=id(1),STATE=id(2),TRACKS=id(3),ACK=id(4);
    private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattService service;
    private boolean scanning,ready,initializing;
    private final ArrayDeque<Operation> queue=new ArrayDeque<>();
    private Operation active;
    private final Runnable scanTimeout=()->{if(scanning)fail("没找到发车铃，请保持开关ON或按RESET后重试");};
    private final Runnable connectionTimeout=()->fail("连接超时，请关闭其他蓝牙控制应用后重试");
    private final Runnable operationTimeout=()->fail("蓝牙响应超时，请重新连接");
    private static class Operation {
        final int kind; final UUID characteristic; final byte[] data;
        Operation(int k,UUID c,byte[] d){kind=k;characteristic=c;data=d;}
    }
    public BellBleClient(Context context,Listener listener){
        this.context=context;this.listener=listener;
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
    }
    public boolean supported(){return adapter!=null;}
    public boolean enabled(){return adapter!=null&&adapter.isEnabled();}
    public void connect(){
        disconnect();
        try{
            if(!enabled()){listener.onStatus("请先打开手机蓝牙");return;}
            scanner=adapter.getBluetoothLeScanner();
            if(scanner==null){listener.onStatus("手机蓝牙尚未就绪，请稍后重试");return;}
            scanning=true;listener.onStatus("正在寻找发车铃…");
            ScanFilter filter=new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
            scanner.startScan(Collections.singletonList(filter),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            handler.postDelayed(scanTimeout,12000);
        }catch(SecurityException e){fail("蓝牙权限未授权，请允许附近设备权限");}
        catch(RuntimeException e){fail("无法开始扫描，请重新打开蓝牙后重试");}
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{
            if(!scanning)return;
            stopScan();listener.onStatus("找到发车铃，正在连接…");
            try{
                gatt=result.getDevice().connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
                if(gatt==null){fail("无法创建蓝牙连接");return;}
                handler.postDelayed(connectionTimeout,18000);
            }catch(SecurityException e){fail("连接权限未授权");}
        });}
        @Override public void onScanFailed(int code){handler.post(()->{if(scanning)fail("扫描失败（"+code+"），请稍后重试");});}
    };
    private void stopScan(){
        handler.removeCallbacks(scanTimeout);
        if(scanning&&scanner!=null){try{scanner.stopScan(scanCallback);}catch(RuntimeException ignored){}}
        scanning=false;
    }
    public void disconnect(){
        stopScan();handler.removeCallbacks(connectionTimeout);handler.removeCallbacks(operationTimeout);
        BluetoothGatt previous=gatt;gatt=null;service=null;ready=false;initializing=false;active=null;queue.clear();
        if(previous!=null){try{previous.disconnect();previous.close();}catch(RuntimeException ignored){}}
        listener.onReady(false);
    }
    private void fail(String message){disconnect();listener.onStatus(message);}
    public void send(String command){
        if(!ready){listener.onStatus("请先连接发车铃");return;}
        if(!BellState.validCommand(command)){listener.onStatus("无效命令");return;}
        if(queue.size()>=8){listener.onStatus("操作较快，请稍等再试");return;}
        queue.add(new Operation(2,COMMAND,command.getBytes(StandardCharsets.UTF_8)));pump();
    }
    private void pump(){
        if(gatt==null||active!=null)return;
        if(queue.isEmpty()){
            if(initializing){initializing=false;ready=true;handler.removeCallbacks(connectionTimeout);listener.onReady(true);listener.onStatus("已连接 DepartureBell");}
            return;
        }
        active=queue.remove();
        BluetoothGattCharacteristic c=service.getCharacteristic(active.characteristic);
        boolean started=false;
        try{
            if(active.kind==0){
                BluetoothGattDescriptor descriptor=c.getDescriptor(CCC);
                if(descriptor==null||!gatt.setCharacteristicNotification(c,true)){fail("设备不支持状态通知");return;}
                if(Build.VERSION.SDK_INT>=33)started=gatt.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;
                else{descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);started=gatt.writeDescriptor(descriptor);}
            }else if(active.kind==1)started=gatt.readCharacteristic(c);
            else{
                if(Build.VERSION.SDK_INT>=33)started=gatt.writeCharacteristic(c,active.data,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;
                else{c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);c.setValue(active.data);started=gatt.writeCharacteristic(c);}
            }
        }catch(RuntimeException e){fail("蓝牙操作失败，请重新连接");return;}
        if(!started){fail("蓝牙请求未成功发出，请重新连接");return;}
        handler.postDelayed(operationTimeout,8000);
    }
    private void completed(int status){
        handler.removeCallbacks(operationTimeout);
        if(status!=BluetoothGatt.GATT_SUCCESS){fail("蓝牙操作失败（"+status+"），请重新连接");return;}
        active=null;pump();
    }
    private void received(UUID uuid,byte[] value){
        String text=new String(value,StandardCharsets.UTF_8);
        if(uuid.equals(STATE)){
            try{listener.onState(BellState.parse(text));}catch(IllegalArgumentException e){listener.onStatus(e.getMessage());}
        }else if(uuid.equals(ACK))listener.onAck(text);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state){handler.post(()->{
            if(source!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){fail("连接已断开，设备可能已休眠；按下开关后重新连接");return;}
            if(state==BluetoothProfile.STATE_CONNECTED){listener.onStatus("正在读取控制功能…");if(!source.discoverServices())fail("无法读取设备服务");}
        });}
        @Override public void onServicesDiscovered(BluetoothGatt source,int status){handler.post(()->{
            if(source!=gatt)return;
            service=source.getService(SERVICE);
            if(status!=BluetoothGatt.GATT_SUCCESS||service==null){fail("设备没有发车铃控制服务");return;}
            for(UUID uuid:Arrays.asList(COMMAND,STATE,TRACKS,ACK))if(service.getCharacteristic(uuid)==null){fail("设备固件缺少控制功能");return;}
            initializing=true;
            queue.add(new Operation(0,STATE,null));queue.add(new Operation(0,ACK,null));
            queue.add(new Operation(1,STATE,null));queue.add(new Operation(1,TRACKS,null));pump();
        });}
        @Override public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor descriptor,int status){handler.post(()->{
            if(source==gatt&&active!=null&&active.kind==0&&descriptor.getCharacteristic().getUuid().equals(active.characteristic))completed(status);
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt source,BluetoothGattCharacteristic c,int status){handler.post(()->{
            if(source==gatt&&active!=null&&active.kind==2&&c.getUuid().equals(active.characteristic))completed(status);
        });}
        private void read(BluetoothGatt source,UUID uuid,byte[] value,int status){byte[] copy=value==null?new byte[0]:value.clone();handler.post(()->{
            if(source!=gatt||active==null||active.kind!=1||!uuid.equals(active.characteristic))return;
            if(status==BluetoothGatt.GATT_SUCCESS)received(uuid,copy);completed(status);
        });}
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33)read(source,c.getUuid(),c.getValue(),status);}
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value,int status){read(source,c.getUuid(),value,status);}
        private void changed(BluetoothGatt source,UUID uuid,byte[] value){byte[] copy=value==null?new byte[0]:value.clone();handler.post(()->{if(source==gatt)received(uuid,copy);});}
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)changed(source,c.getUuid(),c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value){changed(source,c.getUuid(),value);}
    };
}
