package org.papyrus.mcp.util;

import java.util.List;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.common.core.command.ICommand;
import org.eclipse.gmf.runtime.emf.type.core.ElementTypeRegistry;
import org.eclipse.gmf.runtime.emf.type.core.IElementType;
import org.eclipse.gmf.runtime.emf.type.core.requests.*;
import org.eclipse.papyrus.infra.services.edit.service.ElementEditServiceUtils;
import org.eclipse.papyrus.infra.services.edit.service.IElementEditService;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;

/**
 * Bridge to the Papyrus Element Edit Service (the same machinery the palette / Model Explorer
 * use, so Papyrus advice runs: default names, stereotype rules, and -- crucially for delete --
 * removal of the diagram views that show the element). Commands obtained here are executed
 * directly because we are already inside the McpCommand write transaction; their changes are
 * recorded into our single undo step. Each method falls back to the plain UML2/EMF API when the
 * edit service has no command for the request.
 */
public final class EditService {
    private EditService() {}

    private static boolean run(ICommand c) throws Exception {
        if (c == null || !c.canExecute()) return false;
        var st = c.execute(new org.eclipse.core.runtime.NullProgressMonitor(), null);
        if (st != null && !st.isOK()) throw new IllegalStateException(st.getMessage());
        return true;
    }

    /** Papyrus UML element type for a metaclass, e.g. org.eclipse.papyrus.uml.Class. */
    static IElementType type(EClass ec) {
        return ElementTypeRegistry.getInstance().getType("org.eclipse.papyrus.uml." + ec.getName());
    }

    public static EObject create(ModelLocator.Active m, Element container, EClass ec) throws Exception {
        IElementType t = type(ec);
        IElementEditService svc = ElementEditServiceUtils.getCommandProvider(container);
        if (t != null && svc != null) {
            CreateElementRequest req = new CreateElementRequest(m.domain(), container, t);
            ICommand c = svc.getEditCommand(req);
            if (run(c)) {
                Object r = c.getCommandResult() == null ? null : c.getCommandResult().getReturnValue();
                if (r instanceof EObject eo) return eo;
                if (req.getNewElement() != null) return req.getNewElement();
            }
        }
        // Fallback: plain EMF containment.
        EObject o = UMLFactory.eINSTANCE.create(ec);
        if (container instanceof Package p && o instanceof PackageableElement pe) { p.getPackagedElements().add(pe); return o; }
        for (EReference r : container.eClass().getEAllContainments()) {
            if (r.isDerived() || !r.isChangeable() || !r.getEReferenceType().isSuperTypeOf(ec)) continue;
            if (r.isMany()) { @SuppressWarnings("unchecked") List<Object> l = (List<Object>) container.eGet(r); l.add(o); }
            else container.eSet(r, o);
            return o;
        }
        throw new IllegalArgumentException(container.eClass().getName() + " cannot own a " + ec.getName());
    }

    public static void move(ModelLocator.Active m, Element e, Element target) throws Exception {
        IElementEditService svc = ElementEditServiceUtils.getCommandProvider(target);
        if (svc != null && run(svc.getEditCommand(new MoveRequest(m.domain(), target, e)))) return;
        if (target instanceof Package p && e instanceof PackageableElement pe) { p.getPackagedElements().add(pe); return; }
        throw new IllegalArgumentException("cannot move " + e.eClass().getName() + " into " + target.eClass().getName());
    }

    public static void destroy(ModelLocator.Active m, EObject e) throws Exception {
        if (e.eResource() == null) return; // already removed as part of another deletion
        IElementEditService svc = ElementEditServiceUtils.getCommandProvider(e);
        if (svc != null && run(svc.getEditCommand(new DestroyElementRequest(m.domain(), e, false)))) return;
        EcoreUtil.delete(e, true); // removes cross references too (but not GMF views' element refs cleanup)
    }
}
