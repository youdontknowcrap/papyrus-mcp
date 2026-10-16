import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.*;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Enumeration;
import org.eclipse.uml2.uml.resource.UMLResource;
import org.eclipse.uml2.uml.resources.util.UMLResourcesUtil;
/** Generates test/fixtures/McpTest.profile.uml: stereotype Entity (extends Class) with tags. */
public class MakeProfile {
  public static void main(String[] a) throws Exception {
    ResourceSet rs = new ResourceSetImpl(); UMLResourcesUtil.init(rs);
    Profile p = UMLFactory.eINSTANCE.createProfile(); p.setName("McpTest"); p.setURI("http://papyrus-mcp/test/McpTest");
    Model umlMM = (Model) rs.getResource(URI.createURI(UMLResource.UML_METAMODEL_URI), true).getContents().get(0);
    org.eclipse.uml2.uml.Class meta = (org.eclipse.uml2.uml.Class) umlMM.getOwnedType("Class");
    p.createMetaclassReference(meta);
    Package prims = (Package) rs.getResource(URI.createURI(UMLResource.UML_PRIMITIVE_TYPES_LIBRARY_URI), true).getContents().get(0);
    p.createPackageImport(prims);
    Enumeration kind = p.createOwnedEnumeration("Persistence"); kind.createOwnedLiteral("Persistent"); kind.createOwnedLiteral("Transient");
    Stereotype st = p.createOwnedStereotype("Entity", false);
    st.createExtension(meta, false);
    st.createOwnedAttribute("table", prims.getOwnedType("String"));
    st.createOwnedAttribute("version", prims.getOwnedType("Integer"));
    st.createOwnedAttribute("persistence", kind);
    p.define();
    Resource r = rs.createResource(URI.createFileURI(a[0])); r.getContents().add(p); r.save(null);
    System.out.println("wrote " + a[0]);
  }
}
