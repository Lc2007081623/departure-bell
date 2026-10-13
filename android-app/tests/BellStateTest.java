import cn.departurebell.control.BellState;

public class BellStateTest {
    private static void check(boolean result){if(!result)throw new AssertionError();}
    public static void main(String[] args){
        BellState s=BellState.parse("v75,mR,t-1,pI,d0,s0");check(s.volume==75&&!s.fixed&&s.track==-1&&!s.dirty);
        s=BellState.parse("v100,mT,t1,pW,d1,s3");check(s.fixed&&s.phase=='W'&&s.track==1&&s.selected==3&&s.dirty);
        for(String c:new String[]{"V0","V75","V100","T0","T3","R","S","?"})check(BellState.validCommand(c));
        for(String c:new String[]{"V101","V-1","T4","v75","V75x","",null})check(!BellState.validCommand(c));
        for(String raw:new String[]{"v101,mR,t0,pM,d0,s0","v75,mT,t4,pM,d0,s0","garbage"}){
            boolean rejected=false;try{BellState.parse(raw);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        }
        System.out.println("Bell state, pending warning selection and command validation PASS");
    }
}
