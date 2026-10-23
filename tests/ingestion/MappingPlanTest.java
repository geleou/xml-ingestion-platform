import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class MappingPlanTest {
    @TempDir Path temporary;

    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml() throws Exception {
        return new Yaml().load(Files.readString(MappingPlan.DEFAULT));
    }

    static List<Map<String, Object>> fields(Map<String, Object> yaml) {
        @SuppressWarnings("unchecked") var fields = (List<Map<String, Object>>) yaml.get("fields");
        return fields;
    }

    Path save(Map<String, Object> yaml) throws Exception {
        Path path = temporary.resolve("mapping.yaml");
        Files.writeString(path, new Yaml().dump(yaml));
        return path;
    }

    static XsdFields.Inventory inventory() throws Exception {
        return XsdFields.inspect(Path.of("samples/invoices/schema.xsd"), Path.of("samples"));
    }

    @Test void completeInvoiceMappingCoversEightFieldsIncludingAttributeAndImports() throws Exception {
        var inventory = inventory();
        assertEquals(8, inventory.fields().size());
        assertTrue(inventory.fields().stream().anyMatch(f -> f.path().endsWith("/@{}version")));
        assertTrue(inventory.fields().stream().anyMatch(f -> f.path().endsWith("}Name") && f.type().endsWith("}token")));
        MappingPlan mapping = MappingPlan.load(MappingPlan.DEFAULT);
        mapping.coverage(inventory);
        mapping.invoiceAdapter();
    }

    @Test void missingFieldIsRejected() throws Exception {
        var yaml = yaml();
        String removed = (String) fields(yaml).remove(0).get("source");
        var mapping = MappingPlan.load(save(yaml));
        var error = assertThrows(IllegalArgumentException.class, () -> mapping.coverage(inventory()));
        assertTrue(error.getMessage().contains("Missing XSD field"));
        assertTrue(error.getMessage().contains(removed));
    }

    @Test void unresolvedFieldIsRejected() throws Exception {
        var yaml = yaml(); fields(yaml).get(0).put("targets", List.of());
        var mapping = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, () -> mapping.coverage(inventory()));
    }

    @Test void duplicateAndUnknownSourcesAreRejected() throws Exception {
        var yaml = yaml();
        var repeated = new java.util.LinkedHashMap<>(fields(yaml).get(0));
        repeated.put("targets", new ArrayList<>((List<?>) repeated.get("targets")));
        fields(yaml).add(repeated);
        var duplicate = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, () -> duplicate.coverage(inventory()));
        yaml = yaml(); fields(yaml).get(0).put("source", "/{}Unknown");
        var unknown = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, () -> unknown.coverage(inventory()));
    }

    @Test void duplicateTargetAndWrongTypeAreRejected() throws Exception {
        var yaml = yaml(); fields(yaml).get(1).put("targets", new ArrayList<>(fields(yaml).get(0).get("targets") instanceof List<?> list ? list : List.of()));
        var duplicate = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, () -> duplicate.coverage(inventory()));
        yaml = yaml(); fields(yaml).get(0).put("xsd_type", "wrong");
        var wrongType = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, () -> wrongType.coverage(inventory()));
    }

    @Test void duplicateYamlKeysAndUnsafeTagsAreRejected() throws Exception {
        Path path = temporary.resolve("bad.yaml");
        Files.writeString(path, "id: first\nid: second\n");
        assertThrows(Exception.class, () -> MappingPlan.load(path));
        Files.writeString(path, "!!java.net.URL [https://example.invalid]");
        assertThrows(Exception.class, () -> MappingPlan.load(path));
    }

    @Test void missingMappingFailsBeforeXmlOrWorkDirectoryAccess() throws Exception {
        Path work = temporary.resolve("runs");
        var error = assertThrows(IllegalArgumentException.class,
            () -> Ingestor.ingest(Path.of("missing.xml"), Path.of("missing.xsd"), Path.of("samples"), temporary.resolve("missing.yaml"), work));
        assertTrue(error.getMessage().contains("generate-mapping"));
        assertFalse(Files.exists(work));
    }

    @Test void omittedMappingFailsBeforeXmlOrDatabaseAccess() throws Exception {
        var yaml = yaml(); fields(yaml).remove(0);
        Path file = save(yaml), work = temporary.resolve("runs");
        var error = assertThrows(IllegalArgumentException.class,
            () -> Ingestor.ingest(Path.of("missing.xml"), Path.of("samples/invoices/schema.xsd"), Path.of("samples"), file, work));
        assertTrue(error.getMessage().contains("Missing XSD field"));
        assertFalse(Files.exists(work));
    }

    @Test void unsupportedBindingsCannotBeSilentlyIgnoredByInvoiceExecutor() throws Exception {
        var yaml = yaml(); fields(yaml).get(1).put("targets", List.of("ingestion.invoices.customer_name"));
        var mapping = MappingPlan.load(save(yaml));
        assertThrows(IllegalArgumentException.class, mapping::invoiceAdapter);
    }

    @Test void hashAndSnapshotIdentifyExactMappingBytes() throws Exception {
        var mapping = MappingPlan.load(MappingPlan.DEFAULT);
        Path copy = temporary.resolve("snapshot.yaml"); mapping.snapshot(copy);
        assertEquals(mapping.sha256, InvoiceMapping.sha256(copy));
        assertTrue(mapping.identity().endsWith(mapping.sha256));
        assertThrows(Exception.class, () -> mapping.snapshot(copy));
    }

    @Test void optionalFieldsAttributesAndElementReferencesAreInventoried() throws Exception {
        Path schema = temporary.resolve("schema.xsd");
        Files.writeString(schema, """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="shared" type="xs:string"/>
              <xs:element name="Root"><xs:complexType><xs:sequence>
                <xs:element ref="shared" minOccurs="0"/>
                <xs:element name="optional" type="xs:integer" minOccurs="0"/>
              </xs:sequence><xs:attribute name="optionalAttribute" type="xs:string"/></xs:complexType></xs:element>
            </xs:schema>
            """);
        var inventory = XsdFields.inspect(schema, temporary);
        assertEquals(4, inventory.fields().size());
        assertTrue(inventory.fields().stream().anyMatch(f -> f.path().equals("/{}Root/@{}optionalAttribute")));
    }

    @Test void wildcardsAndRecursiveTypesAndNetworkDependenciesFailClosed() throws Exception {
        Path schema = temporary.resolve("schema.xsd");
        String start = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>", end = "</xs:schema>";
        Files.writeString(schema, start + "<xs:element name='Root'><xs:complexType><xs:sequence><xs:any/></xs:sequence></xs:complexType></xs:element>" + end);
        assertThrows(IllegalArgumentException.class, () -> XsdFields.inspect(schema, temporary));
        Files.writeString(schema, start + "<xs:complexType name='Tree'><xs:sequence><xs:element name='Child' type='Tree'/></xs:sequence></xs:complexType><xs:element name='Root' type='Tree'/>" + end);
        assertThrows(IllegalArgumentException.class, () -> XsdFields.inspect(schema, temporary));
        Files.writeString(schema, start + "<xs:include schemaLocation='https://example.invalid/types.xsd'/>" + end);
        assertThrows(IllegalArgumentException.class, () -> XsdFields.inspect(schema, temporary));
    }

    @Test void schemaDependenciesCannotEscapeTrustedRoot() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path schema = root.resolve("schema.xsd");
        Files.writeString(temporary.resolve("outside.xsd"), "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'/>");
        Files.writeString(schema, "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:include schemaLocation='../outside.xsd'/></xs:schema>");
        assertThrows(IllegalArgumentException.class, () -> XsdFields.inspect(schema, root));
    }

    @Test void additionalGlobalFieldsFromIncludedSchemasAreNotOmitted() throws Exception {
        Path schema = temporary.resolve("schema.xsd");
        Files.writeString(schema, "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:include schemaLocation='included.xsd'/><xs:element name='Root' type='xs:string'/></xs:schema>");
        Files.writeString(temporary.resolve("included.xsd"), "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:element name='OtherRoot' type='xs:integer'/></xs:schema>");
        var inventory = XsdFields.inspect(schema, temporary);
        assertEquals(2, inventory.fields().size());
        assertTrue(inventory.fields().stream().anyMatch(f -> f.path().equals("/{}OtherRoot")));
    }
}
