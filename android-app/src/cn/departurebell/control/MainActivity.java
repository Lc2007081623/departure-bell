package cn.departurebell.control;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity implements BellBleClient.Listener {
    private static final String[] TRACK_NAMES=TrackCatalog.NAMES;
    private static final int GREEN=Color.rgb(36,107,80),INK=Color.rgb(28,45,37),MUTED=Color.rgb(95,113,103),BG=Color.rgb(243,246,243);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BellBleClient client;
    private TextView connection,phase,mode,volumeText,saveState,feedback;
    private Button connectButton,disconnectButton,saveButton,randomButton;
    private SeekBar volume;
    private final List<View> controls=new ArrayList<>();
    private final Button[] trackButtons=new Button[TRACK_NAMES.length];
    private boolean ready,dragging,saving;
    private final Runnable saveTimeout=()->{
        saving=false;saveButton.setEnabled(ready);saveState.setText("未收到保存确认");
        feedback.setText("请重新连接后读取设置，确认是否已保存");
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);buildInterface();client=new BellBleClient(this,this);onReady(false);
        if(!client.supported()){connectButton.setEnabled(false);connection.setText("这台手机不支持蓝牙");}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(16));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(dp(3),1);return t;
    }
    private void add(LinearLayout parent,View view,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);parent.addView(view,p);
    }
    private LinearLayout card(LinearLayout parent){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(18),dp(18),dp(18));
        c.setBackground(background(Color.WHITE,0));add(parent,c,14);return c;
    }
    private Button button(String label,boolean primary){
        Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setMinHeight(dp(48));
        b.setPadding(dp(10),dp(6),dp(10),dp(6));b.setTextColor(primary?Color.WHITE:GREEN);
        b.setBackground(background(primary?GREEN:Color.rgb(233,241,236),0));
        b.setOnTouchListener((v,e)->{if(b.isEnabled()){if(e.getAction()==MotionEvent.ACTION_DOWN)b.setAlpha(.65f);else if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL)b.setAlpha(1f);}return false;});return b;
    }
    private void row(LinearLayout parent,Button a,Button b){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(48),1);left.rightMargin=dp(8);r.addView(a,left);
        r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));add(parent,r,10);
    }
    private void buildInterface(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(20));
        scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();
        });scroll.requestApplyInsets();
        add(root,text("发车铃",30,INK,true),4);add(root,text("音乐在车上，控制在手上。",14,MUTED,false),22);
        LinearLayout device=card(root);
        add(device,text("DepartureBell",18,INK,true),6);
        connection=text("尚未连接",14,MUTED,false);connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(device,connection,14);
        connectButton=button("连接发车铃",true);disconnectButton=button("断开",false);row(device,connectButton,disconnectButton);
        connectButton.setOnClickListener(v->startConnect());disconnectButton.setOnClickListener(v->{client.disconnect();connection.setText("已主动断开，点击连接可重新连接");});
        phase=text("等待连接",16,INK,true);add(device,phase,2);
        LinearLayout sound=card(root);add(sound,text("音量",18,INK,true),10);
        volumeText=text("—",28,GREEN,true);add(sound,volumeText,2);
        volume=new SeekBar(this);volume.setMax(100);volume.setProgress(75);volume.setContentDescription("音量，0到100百分比");add(sound,volume,12);controls.add(volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){volumeText.setText(progress+"%");}
            @Override public void onStartTrackingTouch(SeekBar bar){dragging=true;}
            @Override public void onStopTrackingTouch(SeekBar bar){dragging=false;command("V"+bar.getProgress(),"正在调整音量…");}
        });
        Button normal=button("恢复75%",false),mute=button("静音",false);row(sound,normal,mute);controls.add(normal);controls.add(mute);
        normal.setOnClickListener(v->command("V75","正在恢复75%音量…"));mute.setOnClickListener(v->command("V0","正在静音…"));
        add(sound,text("松开滑块后生效。0%为静音，100%为最大音量。",12,MUTED,false),0);
        LinearLayout songs=card(root);add(songs,text("播放方式",18,INK,true),6);
        mode=text("连接后读取设备设置",14,MUTED,false);add(songs,mode,14);
        for(int i=0;i<TRACK_NAMES.length;i++){
            final int track=i;trackButtons[i]=button(TRACK_NAMES[i],false);trackButtons[i].setTextSize(13);controls.add(trackButtons[i]);
            trackButtons[i].setOnClickListener(v->command("T"+track,"正在选择 "+TRACK_NAMES[track]+"…"));
        }
        for(int i=0;i<TRACK_NAMES.length;i+=2){
            if(i+1<TRACK_NAMES.length)row(songs,trackButtons[i],trackButtons[i+1]);
            else add(songs,trackButtons[i],10);
        }
        randomButton=button("随机选曲",false);add(songs,randomButton,12);controls.add(randomButton);
        randomButton.setOnClickListener(v->command("R","正在恢复随机模式…"));
        add(songs,text("警示期间选曲会在警示播完后生效。实体开关仍控制开始和结束。",12,MUTED,false),0);
        LinearLayout settings=card(root);saveButton=button("保存当前设置",true);add(settings,saveButton,10);controls.add(saveButton);
        saveState=text("保存后，下次启动继续使用",13,MUTED,false);add(settings,saveState,10);
        feedback=text("连接后即可调音量和选曲",13,INK,false);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(settings,feedback,0);
        saveButton.setOnClickListener(v->{saving=true;saveButton.setEnabled(false);saveState.setText("正在请求保存…");command("S","等待设备保存…");handler.removeCallbacks(saveTimeout);handler.postDelayed(saveTimeout,30000);});
        add(root,text("警示结束后蓝牙保留1分钟；之后休眠断开。再次按下开关后，点击连接即可。\n本应用不联网，仅通过蓝牙控制发车铃。",12,MUTED,false),12);
        add(root,text("发车铃控制 · 1.1.0",11,MUTED,false),0);
    }
    private void command(String value,String message){if(!ready)return;feedback.setText(message);client.send(value);}
    private String[] requiredPermissions(){return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};}
    private void startConnect(){
        List<String> missing=new ArrayList<>();for(String p:requiredPermissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),1);return;}
        if(!client.enabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),2);return;}
        if(Build.VERSION.SDK_INT<31){
            LocationManager location=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(location!=null&&!location.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                new AlertDialog.Builder(this).setTitle("打开定位开关才能扫描").setMessage("此版本安卓要求打开定位开关才能扫描蓝牙。应用不会读取或保存位置。打开后返回，再点连接。")
                    .setPositiveButton("去开启",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("取消",null).show();return;
            }
        }
        saving=false;handler.removeCallbacks(saveTimeout);connectButton.setEnabled(false);client.connect();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==1){for(String p:requiredPermissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){
            connection.setText("需要蓝牙扫描和连接权限。请在系统设置中允许。");
            new AlertDialog.Builder(this).setTitle("权限未授权").setMessage("请允许所需权限后连接；如果已选不再询问，可进入应用设置开启。")
                .setPositiveButton("应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("取消",null).show();return;
        }startConnect();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==2&&result==RESULT_OK)startConnect();}
    @Override public void onStatus(String message){connection.setText(message);if(!ready)connectButton.setEnabled(!message.contains("正在"));}
    @Override public void onReady(boolean value){
        ready=value;connectButton.setEnabled(!value);disconnectButton.setEnabled(value);
        for(View control:controls){control.setEnabled(value);control.setAlpha(value?1:.45f);}
        if(!value){handler.removeCallbacks(saveTimeout);if(saving){saving=false;saveState.setText("连接中断，保存结果未确认");}phase.setText("等待连接");}
        else saveButton.setEnabled(!saving);
    }
    @Override public void onState(BellState s){
        if(!dragging){volume.setProgress(s.volume);volumeText.setText(s.volume+"%");}
        String current=s.track>=0?TRACK_NAMES[s.track]:"";
        phase.setText(s.phase=='M'?"正在播放 · "+current:s.phase=='W'?"警示音 · "+current:s.phase=='E'?"设备播放异常":"待机 · 蓝牙仍可控制");
        mode.setText(s.fixed?"固定曲目 · "+TRACK_NAMES[s.selected]:"随机模式 · 每次启动选择一首");
        for(int i=0;i<TRACK_NAMES.length;i++){boolean selected=s.fixed&&s.selected==i;trackButtons[i].setBackground(background(selected?GREEN:Color.rgb(233,241,236),0));trackButtons[i].setTextColor(selected?Color.WHITE:GREEN);}
        randomButton.setBackground(background(s.fixed?Color.rgb(233,241,236):GREEN,0));randomButton.setTextColor(s.fixed?GREEN:Color.WHITE);
        if(!saving)saveState.setText(s.dirty?"设置已更改，点击保存可在下次启动保留":"当前设置已保存");
    }
    @Override public void onAck(String value){
        if(value.equals("WAIT:S")){saving=true;saveButton.setEnabled(false);saveState.setText("等待当前音乐段结束后保存，最长约24秒…");feedback.setText("请等待保存成功后再断电");}
        else if(value.equals("OK:S")){saving=false;handler.removeCallbacks(saveTimeout);saveButton.setEnabled(ready);saveState.setText("保存成功，下次启动继续使用");feedback.setText("设置已保存");}
        else if(value.startsWith("ERR:")){saving=false;handler.removeCallbacks(saveTimeout);saveButton.setEnabled(ready);feedback.setText(value.equals("ERR:save")?"保存失败，请重试":"设备未接受操作，请重试（"+value+"）");}
        else if(value.startsWith("OK:V"))feedback.setText("音量已设为 "+value.substring(4)+"%");
        else if(value.startsWith("OK:T")){int n=Integer.parseInt(value.substring(4));if(n>=0&&n<TRACK_NAMES.length)feedback.setText("已选择 "+TRACK_NAMES[n]);}
        else if(value.equals("OK:R"))feedback.setText("已恢复随机模式");
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(client!=null)client.disconnect();super.onDestroy();}
}
