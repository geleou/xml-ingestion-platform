import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Conservative XSD 1.0 field inventory. Unsupported constructs fail closed. */
public final class XsdFields {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    public record Field(String path, String type) {}
    public record Inventory(List<Field> fields, String sha256) {}
    private final Path root;
    private final Map<Path, Element> documents = new LinkedHashMap<>();
    private final Map<String, Element> types = new LinkedHashMap<>();
    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final Map<String, Field> fields = new LinkedHashMap<>();

    private XsdFields(Path root) throws Exception { this.root = root.toRealPath(); }

    public static Inventory inspect(Path xsd, Path root) throws Exception {
        var scanner = new XsdFields(root);
        scanner.load(xsd, null);
        for (Element schema : scanner.documents.values()) {
            for (Element child : children(schema)) {
                switch (child.getLocalName()) {
                    case "simpleType", "complexType" -> scanner.register(scanner.types, child);
                    case "element" -> scanner.register(scanner.elements, child);
                    case "annotation", "include", "import" -> { }
                    default -> throw unsupported(child);
                }
            }
        }
        // Dependencies may declare additional document roots, not just referenced types.
        for (Element declaration : scanner.elements.values()) scanner.element(declaration, "", new HashSet<>());
        if (scanner.fields.isEmpty()) throw new IllegalArgumentException("XSD has no mappable fields");
        var digest = MessageDigest.getInstance("SHA-256");
        for (Path path : scanner.documents.keySet().stream().sorted().toList()) {
            digest.update((scanner.root.relativize(path).toString().replace('\\', '/') + "\n"
                + InvoiceMapping.sha256(path) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Inventory(List.copyOf(scanner.fields.values()), HexFormat.of().formatHex(digest.digest()));
    }

    private Element load(Path path, String expectedNamespace) throws Exception {
        Path real = path.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) throw new IllegalArgumentException("XSD escapes trusted root: " + path);
        if (documents.containsKey(real)) {
            Element existing = documents.get(real);
            checkNamespace(existing, expectedNamespace);
            return existing;
        }
        if (documents.size() >= 128) throw new IllegalArgumentException("Too many XSD dependencies");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element schema;
        try (var input = Files.newInputStream(real)) { schema = factory.newDocumentBuilder().parse(input).getDocumentElement(); }
        if (!XS.equals(schema.getNamespaceURI()) || !schema.getLocalName().equals("schema")) throw unsupported(schema);
        checkNamespace(schema, expectedNamespace);
        documents.put(real, schema);
        for (Element child : children(schema)) {
            if (Set.of("include", "import").contains(child.getLocalName())) {
                if (!child.hasAttribute("schemaLocation")) throw new IllegalArgumentException("Explicit local XSD schemaLocation required");
                URI uri = real.toUri().resolve(child.getAttribute("schemaLocation"));
                if (!"file".equals(uri.getScheme())) throw new IllegalArgumentException("Network XSD dependency forbidden: " + uri);
                String ns = child.getLocalName().equals("include") ? schema.getAttribute("targetNamespace") : child.getAttribute("namespace");
                load(Path.of(uri), ns);
            }
        }
        return schema;
    }

    private static void checkNamespace(Element schema, String expected) {
        if (expected != null && !schema.getAttribute("targetNamespace").equals(expected)) {
            throw new IllegalArgumentException("XSD dependency namespace mismatch (chameleon includes are unsupported)");
        }
    }

    private void register(Map<String, Element> declarations, Element element) {
        String key = expanded(schema(element).getAttribute("targetNamespace"), element.getAttribute("name"));
        if (declarations.putIfAbsent(key, element) != null) throw new IllegalArgumentException("Duplicate XSD declaration: " + key);
    }

    private void element(Element declaration, String parent, Set<Element> active) {
        Element value = declaration;
        if (declaration.hasAttribute("ref")) {
            value = elements.get(qname(declaration, declaration.getAttribute("ref")));
            if (value == null) throw new IllegalArgumentException("Unresolved XSD element reference");
        }
        if (value.hasAttribute("substitutionGroup") || value.getAttribute("abstract").equals("true")) throw unsupported(value);
        boolean global = value.getParentNode() == schema(value);
        boolean qualified = global || value.getAttribute("form").equals("qualified")
            || (!value.hasAttribute("form") && schema(value).getAttribute("elementFormDefault").equals("qualified"));
        String path = parent + "/" + expanded(qualified ? schema(value).getAttribute("targetNamespace") : "", value.getAttribute("name"));
        Element type = inlineType(value);
        String name = value.hasAttribute("type") ? qname(value, value.getAttribute("type")) : null;
        if (type == null && name != null) type = types.get(name);
        if (type == null) {
            if (name == null || !name.startsWith("{" + XS + "}") || name.endsWith("}anyType") || name.endsWith("}anySimpleType")) throw unsupported(value);
            add(path, name);
        } else if (type.getLocalName().equals("simpleType")) add(path, scalar(type, new HashSet<>()));
        else complex(type, path, active);
        for (Element child : children(value)) {
            if (!Set.of("annotation", "simpleType", "complexType", "key", "keyref", "unique").contains(child.getLocalName())) throw unsupported(child);
        }
    }

    private void complex(Element type, String path, Set<Element> active) {
        if (active.size() >= 64 || !active.add(type)) throw new IllegalArgumentException("Recursive/deep XSD types require a dedicated mapping adapter");
        if (type.getAttribute("mixed").equals("true")) throw unsupported(type);
        for (Element child : children(type)) {
            switch (child.getLocalName()) {
                case "annotation" -> { }
                case "sequence", "choice", "all" -> particle(child, path, active);
                case "attribute" -> attribute(child, path);
                default -> throw unsupported(child);
            }
        }
        active.remove(type);
    }

    private void particle(Element particle, String path, Set<Element> active) {
        for (Element child : children(particle)) {
            switch (child.getLocalName()) {
                case "element" -> element(child, path, active);
                case "sequence", "choice", "all" -> particle(child, path, active);
                case "annotation" -> { }
                default -> throw unsupported(child);
            }
        }
    }

    private void attribute(Element value, String parent) {
        if (value.hasAttribute("ref")) throw unsupported(value);
        if (value.getAttribute("use").equals("prohibited")) return;
        boolean qualified = value.getAttribute("form").equals("qualified")
            || (!value.hasAttribute("form") && schema(value).getAttribute("attributeFormDefault").equals("qualified"));
        String path = parent + "/@" + expanded(qualified ? schema(value).getAttribute("targetNamespace") : "", value.getAttribute("name"));
        Element inline = inlineType(value);
        String name = value.hasAttribute("type") ? qname(value, value.getAttribute("type")) : null;
        Element type = inline == null ? types.get(name) : inline;
        if (type != null) add(path, scalar(type, new HashSet<>()));
        else if (name != null && name.startsWith("{" + XS + "}") && !name.endsWith("}anySimpleType")) add(path, name);
        else throw unsupported(value);
    }

    private String scalar(Element type, Set<Element> active) {
        if (!active.add(type)) throw unsupported(type);
        for (Element child : children(type)) {
            if (child.getLocalName().equals("annotation")) continue;
            if (!child.getLocalName().equals("restriction") || !child.hasAttribute("base")) throw unsupported(child);
            String base = qname(child, child.getAttribute("base"));
            if (base.startsWith("{" + XS + "}")) return base;
            Element next = types.get(base);
            if (next == null) throw new IllegalArgumentException("Unresolved XSD scalar type: " + base);
            return scalar(next, active);
        }
        throw unsupported(type);
    }

    private void add(String path, String type) {
        Field previous = fields.putIfAbsent(path, new Field(path, type));
        if (previous != null) throw new IllegalArgumentException("Ambiguous/repeated XSD declaration path: " + path);
    }
    private static Element inlineType(Element value) {
        for (Element child : children(value)) if (Set.of("simpleType", "complexType").contains(child.getLocalName())) return child;
        return null;
    }
    private static Element schema(Element value) { return value.getOwnerDocument().getDocumentElement(); }
    private static String expanded(String namespace, String local) { return "{" + namespace + "}" + local; }
    private static String qname(Element value, String name) {
        String[] parts = name.split(":", -1);
        String namespace = value.lookupNamespaceURI(parts.length == 2 ? parts[0] : null);
        if (parts.length > 2 || (parts.length == 2 && namespace == null)) throw unsupported(value);
        return expanded(namespace == null ? "" : namespace, parts[parts.length - 1]);
    }
    private static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (!XS.equals(element.getNamespaceURI())) throw unsupported(element);
                children.add(element);
            }
        }
        return children;
    }
    private static IllegalArgumentException unsupported(Element value) {
        return new IllegalArgumentException("Unsupported XSD inventory construct: " + value.getTagName()
            + ". A dedicated inventory/adapter is required; fields cannot be silently skipped.");
    }
}
