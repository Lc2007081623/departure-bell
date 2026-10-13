package cn.departurebell.control;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BellState {
    private static final Pattern FORMAT=Pattern.compile("v(\\d+),m([RT]),t(-?\\d+),p([IMWE]),d([01]),s([0-3])");
    public final int volume,track,selected;
    public final boolean fixed,dirty;
    public final char phase;
    private BellState(int v,boolean f,int t,char p,boolean d,int s){volume=v;fixed=f;track=t;phase=p;dirty=d;selected=s;}
    public static BellState parse(String raw){
        Matcher m=FORMAT.matcher(raw);
        if(!m.matches())throw new IllegalArgumentException("无法识别设备状态");
        int volume=Integer.parseInt(m.group(1)),track=Integer.parseInt(m.group(3));
        if(volume>100||track< -1||track>3)throw new IllegalArgumentException("设备状态超出范围");
        return new BellState(volume,m.group(2).equals("T"),track,m.group(4).charAt(0),m.group(5).equals("1"),Integer.parseInt(m.group(6)));
    }
    public static boolean validCommand(String c){return c!=null&&c.matches("(?:V(?:100|[1-9]?[0-9])|T[0-3]|R|S|\\?)");}
}
