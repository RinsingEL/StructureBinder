package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.function.BooleanSupplier;

/** External feature/structure writes are guarded; explicit City scopes and ordinary player edits are allowed. */
public final class CityFeatureWriteGuard {
    private static final class Frame {final java.util.Map<Object,Runnable> undo=new java.util.LinkedHashMap<>();boolean rejected;}
    private static final ThreadLocal<java.util.Deque<Frame>> FEATURES=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> CITY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private CityFeatureWriteGuard() {}
    public static boolean active() { return DEPTH.get() > 0 && CITY_DEPTH.get() == 0; }
    public static boolean cityOwned() { return CITY_DEPTH.get() > 0; }
    public static void record(Object key,java.util.function.Supplier<Runnable> restore){
        if(cityOwned())return;for(var frame:FEATURES.get())frame.undo.computeIfAbsent(key,k->restore.get());
    }
    public static void rejectFeature(){for(var frame:FEATURES.get())frame.rejected=true;}
    public static boolean atomicFeature(BooleanSupplier placement){
        var frame=new Frame();var stack=FEATURES.get();stack.push(frame);
        try{boolean placed=run(placement);if(!placed||frame.rejected){rollback(frame);return false;}return true;}
        catch(RuntimeException|Error ex){rollback(frame);throw ex;}
        finally{stack.pop();if(stack.isEmpty())FEATURES.remove();}
    }
    private static void rollback(Frame frame){city(()->{var reverse=new java.util.ArrayList<>(frame.undo.values());java.util.Collections.reverse(reverse);reverse.forEach(Runnable::run);return null;});}
    public static <T> T city(java.util.function.Supplier<T> placement) {
        int previous = CITY_DEPTH.get(); CITY_DEPTH.set(previous + 1);
        try { return placement.get(); }
        finally { if (previous == 0) CITY_DEPTH.remove(); else CITY_DEPTH.set(previous); }
    }
    public static boolean run(BooleanSupplier placement) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try { return placement.getAsBoolean(); }
        finally { if (previous == 0) DEPTH.remove(); else DEPTH.set(previous); }
    }
}
