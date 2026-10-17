import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.*;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.resource.UMLResource;
import org.eclipse.uml2.uml.resources.util.UMLResourcesUtil;
/**
 * Generates org.papyrus.mcp/profiles/ICONIX.profile.uml, the small ICONIX robustness profile
 * shipped inside the plugin (a bot applies it with apply_profile profile="ICONIX").
 * Three stereotypes, Boundary, Control and Entity, all extend the UML metaclass Class.
 * Build/run: same classpath as MakeProfile.java (emf + uml2 jars of the Papyrus install).
 */
public class MakeIconixProfile {
  public static void main(String[] a) throws Exception {
    ResourceSet rs = new ResourceSetImpl(); UMLResourcesUtil.init(rs);
    Profile p = UMLFactory.eINSTANCE.createProfile(); p.setName("ICONIX"); p.setURI("http://papyrus-mcp/profiles/ICONIX");
    p.createOwnedComment().setBody("ICONIX robustness analysis stereotypes. Boundary = screen/API the actor touches; "
        + "Control = a piece of behavior (a verb); Entity = a domain class. Rules: actors talk only to boundaries; "
        + "boundaries talk to controls; entities talk only to controls (no noun-to-noun links).");
    // The UML metamodel's "Class" metaclass is what our stereotypes extend.
    Model umlMM = (Model) rs.getResource(URI.createURI(UMLResource.UML_METAMODEL_URI), true).getContents().get(0);
    org.eclipse.uml2.uml.Class meta = (org.eclipse.uml2.uml.Class) umlMM.getOwnedType("Class");
    p.createMetaclassReference(meta);
    for (String n : new String[]{"Boundary", "Control", "Entity"}) p.createOwnedStereotype(n, false).createExtension(meta, false);
    p.define(); // generates the Ecore definition so the profile can be applied
    Resource r = rs.createResource(URI.createFileURI(a[0])); r.getContents().add(p); r.save(null);
    System.out.println("wrote " + a[0]);
  }
}
