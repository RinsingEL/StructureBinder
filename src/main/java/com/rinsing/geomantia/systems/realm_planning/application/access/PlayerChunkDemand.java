package com.rinsing.geomantia.systems.realm_planning.application.access;

import java.util.*;
import java.util.function.LongPredicate;

/** Retains vanilla view demand while only emitting transitions accepted by the loading authority. */
public final class PlayerChunkDemand {
    public record Transition(long position, int distance, boolean before, boolean after) {}
    private final Map<Long,Integer> wanted = new HashMap<>();
    private final Set<Long> emitted = new HashSet<>();

    public Transition update(long position, int distance, boolean inView, LongPredicate allowed) {
        if(inView) wanted.put(position,distance); else wanted.remove(position);
        boolean before=emitted.contains(position), after=inView && allowed.test(position);
        if(after) emitted.add(position); else emitted.remove(position);
        return new Transition(position,distance,before,after);
    }
    public List<Transition> reconcile(LongPredicate allowed) {
        List<Transition> result=new ArrayList<>();
        for(var entry:List.copyOf(wanted.entrySet())) {
            var change=update(entry.getKey(),entry.getValue(),true,allowed);
            if(change.before()!=change.after()) result.add(change);
        }
        return result;
    }
}
