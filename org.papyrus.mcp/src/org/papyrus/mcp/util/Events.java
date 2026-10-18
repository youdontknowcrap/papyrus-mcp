package org.papyrus.mcp.util;

import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;

/**
 * Triggers need events, and events need a home. We keep generated signals and signal events in
 * one package "<behavior> Events" next to the behavior, so models stay valid and tidy.
 */
final class Events {
    private Events() {}

    static Package home(Behavior b) {
        Package p = b.getNearestPackage();
        String n = b.getName() + " Events";
        for (PackageableElement e : p.getPackagedElements()) if (e instanceof Package x && n.equals(x.getName())) return x;
        return p.createNestedPackage(n);
    }

    static Signal signal(Behavior b, String name) {
        Package h = home(b);
        for (PackageableElement e : h.getPackagedElements()) if (e instanceof Signal s && name.equals(s.getName())) return s;
        return (Signal) h.createPackagedElement(name, UMLPackage.Literals.SIGNAL);
    }

    static SignalEvent signalEvent(Behavior b, String name) {
        Package h = home(b);
        for (PackageableElement e : h.getPackagedElements()) if (e instanceof SignalEvent s && name.equals(s.getName())) return s;
        SignalEvent ev = (SignalEvent) h.createPackagedElement(name, UMLPackage.Literals.SIGNAL_EVENT);
        ev.setSignal(signal(b, name));
        return ev;
    }
}
