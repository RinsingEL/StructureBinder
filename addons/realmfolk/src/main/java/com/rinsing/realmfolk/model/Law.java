package com.rinsing.realmfolk.model;

import java.util.List;
import com.rinsing.realmfolk.design.Design.LawPolicy;
import static com.rinsing.realmfolk.model.Society.*;

/** This layer is called only after the runtime has identified a living eyewitness. */
public final class Law {
    private Law() {}
    public static Offense record(Journal journal, String realm, String property, String witness, Crime crime, long tick, int value) {
        return record(journal,realm,property,witness,crime,tick,value,new LawPolicy());
    }
    public static Offense record(Journal journal,String realm,String property,String witness,Crime crime,long tick,int value,LawPolicy policy) {
        Offense offense = new Offense();
        offense.realm=realm; offense.property=property; offense.witness=witness; offense.crime=crime; offense.tick=tick;
        offense.fine = switch(crime) {
            case TRESPASS -> policy.trespassFine;
            case THEFT -> Math.min(64,Math.max(1,value*policy.theftUnit));
            case DAMAGE -> policy.damageFine;
            case ASSAULT -> policy.assaultFine;
            case MURDER -> policy.murderFine;
        };
        journal.offenses.add(offense);
        journal.like(witness, crime==Crime.MURDER ? -30 : crime==Crime.ASSAULT ? -10 : -1);
        long outstanding = pending(journal,realm).size();
        if (crime==Crime.ASSAULT || crime==Crime.MURDER || outstanding>=policy.repeatedOffenses) report(journal,offense);
        return offense;
    }
    public static void report(Journal journal, Offense offense) {
        if (offense.reported || offense.settled) return;
        offense.reported=true;
        journal.reputation(offense.realm, -Math.max(1,offense.fine/2));
    }
    public static List<Offense> pending(Journal journal, String realm) {
        return journal.offenses.stream().filter(o->!o.settled && o.realm.equals(realm)).toList();
    }
    public static int fine(Journal journal, String realm) { return pending(journal,realm).stream().mapToInt(o->o.fine).sum(); }
    public static boolean wanted(Journal journal, String realm) { return pending(journal,realm).stream().anyMatch(o->o.reported); }
    public static boolean dangerous(Journal journal,String realm) {
        return dangerous(journal,realm,new LawPolicy());
    }
    public static boolean dangerous(Journal journal,String realm,LawPolicy policy) {
        List<Offense> pending=pending(journal,realm);
        return wanted(journal,realm) && (pending.size()>=policy.repeatedOffenses || pending.stream().anyMatch(o->o.crime==Crime.ASSAULT||o.crime==Crime.MURDER));
    }
    public static void settle(Journal journal, String realm) { pending(journal,realm).forEach(o->o.settled=true); }
    public static boolean returnGoods(Offense offense) {
        if (offense.settled || offense.crime!=Crime.THEFT || offense.stolenStack.isBlank()) return false;
        offense.settled=true;
        return true;
    }
}
