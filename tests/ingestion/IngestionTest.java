import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IngestionTest {
    @TempDir Path temporary;

    static String invoice(int id, String customer) {
        String classification = "INVOICES_00001_SYNTHETIC_AUTHORITY_CLASSIFICATION_" + "X".repeat(32);
        return "<Invoice><InvoiceId>" + id + "</InvoiceId><Classification>" + classification + "</Classification>"
            + "<Customer><Name>" + customer + "</Name><Country>GR</Country></Customer>"
            + "<Lines><Line><Sku>SKU-1</Sku><Quantity>2</Quantity><UnitPrice>12.50</UnitPrice></Line>"
            + "<Line><Sku>SKU-2</Sku><Quantity>3</Quantity><UnitPrice>0.125</UnitPrice></Line></Lines></Invoice>";
    }

    static String xml(String body) {
        return "<Invoices xmlns=\"" + InvoiceMapping.NAMESPACE + "\" version=\"1\">" + body + "</Invoices>";
    }

    @Test void validatorRegression() throws Exception { XmlValidatorTest.main(new String[0]); }

    @Test void approvedBundleAndSnapshot() throws Exception {
        var bundle = InvoiceMapping.eligible(Path.of("samples/invoices/schema.xsd"), Path.of("samples"));
        var copied = InvoiceMapping.snapshot(bundle, temporary.resolve("schemas"));
        assertEquals(bundle.sha256(), copied.sha256());
        Files.writeString(copied.root().resolve("common/types.xsd"), "<not-a-schema/>");
        assertThrows(IllegalArgumentException.class, () -> InvoiceMapping.eligible(copied.xsd(), copied.root()));
    }

    @Test void rejectsUnmappedSchemas() {
        assertThrows(IllegalArgumentException.class,
            () -> InvoiceMapping.eligible(Path.of("samples/telemetry/schema.xsd"), Path.of("samples")));
    }

    @Test void extractionPreservesRelationsDecimalsAndCsvEscaping() throws Exception {
        Path xml = temporary.resolve("source.xml");
        Files.writeString(xml, xml(invoice(17, "  Acme &quot;North&quot;, &#10; Branch  ")));
        assertTrue(XmlValidator.validate(xml, Path.of("samples/invoices/schema.xsd"), Path.of("samples")).valid());
        UUID batch = UUID.randomUUID();
        var extracted = InvoiceExtractor.extract(xml, batch, temporary);
        assertEquals(1, extracted.invoiceCount());
        assertEquals(2, extracted.lineCount());
        assertEquals("1", extracted.documentVersion());
        assertTrue(Files.readString(extracted.invoices()).contains("\"Acme \"\"North\"\", Branch\""));
        String lines = Files.readString(extracted.lines());
        assertTrue(lines.contains("\"" + batch + "\",\"17\",\"1\",\"SKU-1\",\"2\",\"12.50\""));
        assertTrue(lines.contains("\"17\",\"2\",\"SKU-2\",\"3\",\"0.125\""));
        assertEquals(InvoiceMapping.sha256(extracted.lines()), extracted.lineHash());
    }

    @Test void extractorRejectsDoctype() throws Exception {
        Path xml = temporary.resolve("source.xml");
        Files.writeString(xml, "<!DOCTYPE Invoices [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>" + xml(invoice(1, "&external;")));
        assertThrows(Exception.class, () -> InvoiceExtractor.extract(xml, UUID.randomUUID(), temporary));
    }

    @Test void extractorBoundsFieldSize() throws Exception {
        Path xml = temporary.resolve("source.xml");
        Files.writeString(xml, xml(invoice(1, "x".repeat(1_048_577))));
        assertThrows(Exception.class, () -> InvoiceExtractor.extract(xml, UUID.randomUUID(), temporary));
    }
}
