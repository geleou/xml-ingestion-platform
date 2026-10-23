#!/usr/bin/env python3
"""Generate deterministic, synthetic XSD 1.0 fixtures using only Python's stdlib."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
XS = "http://www.w3.org/2001/XMLSchema"
COMMON = "urn:xml-ingestion:samples:common:v1"
CASES = {
    "invoices": ("Invoices", "Invoice", "negative UnitPrice"),
    "telemetry": ("Readings", "Reading", "unknown Classification enumeration value"),
    "registry": ("Registry", "Entry", "duplicate EntryId violating xs:key"),
}


def code(domain, index):
    return f"{domain.upper()}_{index:05d}_" + "SYNTHETIC_AUTHORITY_CLASSIFICATION_" + "X" * 32


def schemas(domain, root, record, vocabulary_size):
    directory = ROOT / domain
    directory.mkdir(exist_ok=True)
    ns = f"urn:xml-ingestion:samples:{domain}:v1"
    with (directory / "classifications.xsd").open("w", encoding="utf-8", newline="\n") as out:
        out.write(f'''<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="{XS}" targetNamespace="{ns}" elementFormDefault="qualified">
  <xs:annotation><xs:documentation>Synthetic large controlled vocabulary, not an authority schema.</xs:documentation></xs:annotation>
  <xs:simpleType name="Classification"><xs:restriction base="xs:string">
''')
        for i in range(vocabulary_size):
            out.write(f'    <xs:enumeration value="{code(domain, i)}"/>\n')
        out.write("  </xs:restriction></xs:simpleType>\n</xs:schema>\n")
    payloads = {
        "invoices": '''<xs:element name="Customer"><xs:complexType><xs:sequence>
          <xs:element name="Name" type="c:NonEmptyString"/><xs:element name="Country" type="c:CountryCode"/>
        </xs:sequence></xs:complexType></xs:element>
        <xs:element name="Lines"><xs:complexType><xs:sequence>
          <xs:element name="Line" maxOccurs="unbounded"><xs:complexType><xs:sequence>
            <xs:element name="Sku" type="c:NonEmptyString"/><xs:element name="Quantity" type="xs:positiveInteger"/>
            <xs:element name="UnitPrice" type="c:NonNegativeDecimal"/>
          </xs:sequence></xs:complexType></xs:element>
        </xs:sequence></xs:complexType></xs:element>''',
        "telemetry": '''<xs:element name="Device"><xs:complexType><xs:sequence>
          <xs:element name="Serial" type="c:NonEmptyString"/><xs:element name="Country" type="c:CountryCode"/>
        </xs:sequence></xs:complexType></xs:element>
        <xs:element name="Measurements"><xs:complexType><xs:sequence>
          <xs:element name="Measurement" maxOccurs="unbounded"><xs:complexType><xs:sequence>
            <xs:element name="Timestamp" type="xs:dateTime"/><xs:element name="Value" type="xs:decimal"/>
          </xs:sequence><xs:attribute name="unit" type="c:NonEmptyString" use="required"/></xs:complexType></xs:element>
        </xs:sequence></xs:complexType></xs:element>''',
        "registry": '''<xs:element name="Organisation"><xs:complexType><xs:sequence>
          <xs:element name="Name" type="c:NonEmptyString"/>
          <xs:element name="Address"><xs:complexType><xs:sequence>
            <xs:element name="City" type="c:NonEmptyString"/><xs:element name="Country" type="c:CountryCode"/>
          </xs:sequence></xs:complexType></xs:element>
        </xs:sequence></xs:complexType></xs:element>
        <xs:element name="Contacts"><xs:complexType><xs:sequence>
          <xs:element name="Contact" type="c:NonEmptyString" maxOccurs="unbounded"/>
        </xs:sequence></xs:complexType></xs:element>''',
    }
    key = (f'<xs:key name="uniqueEntryId"><xs:selector xpath="t:{record}"/>'
           '<xs:field xpath="t:EntryId"/></xs:key>') if domain == "registry" else ""
    (directory / "schema.xsd").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="{XS}" xmlns:t="{ns}" xmlns:c="{COMMON}" targetNamespace="{ns}" elementFormDefault="qualified">
  <xs:include schemaLocation="classifications.xsd"/>
  <xs:import namespace="{COMMON}" schemaLocation="../common/types.xsd"/>
  <xs:element name="{root}"><xs:complexType><xs:sequence>
    <xs:element name="{record}" maxOccurs="unbounded"><xs:complexType><xs:sequence>
      <xs:element name="{record}Id" type="xs:positiveInteger"/>
      <xs:element name="Classification" type="t:Classification"/>
      {payloads[domain]}
    </xs:sequence></xs:complexType></xs:element>
  </xs:sequence><xs:attribute name="version" type="xs:positiveInteger" use="required"/></xs:complexType>{key}</xs:element>
</xs:schema>
''', encoding="utf-8")


def row(domain, record, index, vocabulary_size, invalid=False):
    identifier = 1 if invalid and domain == "registry" else index
    classification = "UNKNOWN" if invalid and domain == "telemetry" else code(domain, index % vocabulary_size)
    if domain == "invoices":
        price = "-12.50" if invalid else "12.50"
        payload = (f"<Customer><Name>Synthetic Customer {index}</Name><Country>GR</Country></Customer>"
                   f"<Lines><Line><Sku>SKU-001</Sku><Quantity>2</Quantity><UnitPrice>{price}</UnitPrice></Line>"
                   "<Line><Sku>SKU-002</Sku><Quantity>1</Quantity><UnitPrice>8.75</UnitPrice></Line></Lines>")
    elif domain == "telemetry":
        payload = (f"<Device><Serial>SENSOR-{index:09d}</Serial><Country>GR</Country></Device>"
                   '<Measurements><Measurement unit="C"><Timestamp>2026-01-01T12:00:00Z</Timestamp><Value>23.50</Value></Measurement>'
                   '<Measurement unit="percent"><Timestamp>2026-01-01T12:00:01Z</Timestamp><Value>45.25</Value></Measurement></Measurements>')
    else:
        payload = (f"<Organisation><Name>Synthetic Organisation {index}</Name><Address><City>Athens</City><Country>GR</Country></Address></Organisation>"
                   f"<Contacts><Contact>contact-{index}@example.invalid</Contact><Contact>office-{index}@example.invalid</Contact></Contacts>")
    return (f"  <{record}><{record}Id>{identifier}</{record}Id><Classification>{classification}</Classification>"
            f"{payload}</{record}>\n").encode("utf-8")


def generate(size_mib, vocabulary_size):
    common = ROOT / "common"
    common.mkdir(exist_ok=True)
    (common / "types.xsd").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="{XS}" targetNamespace="{COMMON}" elementFormDefault="qualified">
  <xs:simpleType name="NonEmptyString"><xs:restriction base="xs:token"><xs:minLength value="1"/></xs:restriction></xs:simpleType>
  <xs:simpleType name="CountryCode"><xs:restriction base="xs:string"><xs:pattern value="[A-Z]{{2}}"/></xs:restriction></xs:simpleType>
  <xs:simpleType name="NonNegativeDecimal"><xs:restriction base="xs:decimal"><xs:minInclusive value="0"/></xs:restriction></xs:simpleType>
</xs:schema>
''', encoding="utf-8")
    manifest = {"format_version": 1, "xsd_version": "1.0", "target_xml_size_mib": size_mib,
                "vocabulary_size": vocabulary_size, "cases": []}
    for domain, (root, record, violation) in CASES.items():
        schemas(domain, root, record, vocabulary_size)
        paths = [ROOT / domain / f"{variant}.xml" for variant in ("valid", "schema-invalid", "malformed")]
        streams = [p.open("wb") for p in paths]
        hashes = [hashlib.sha256() for _ in paths]
        def emit(position, data):
            streams[position].write(data)
            hashes[position].update(data)
        try:
            header = (f'<?xml version="1.0" encoding="UTF-8"?>\n'
                      f'<{root} xmlns="urn:xml-ingestion:samples:{domain}:v1" version="1">\n').encode()
            for pos in range(3):
                emit(pos, header)
            size = len(header)
            index = 1
            while size < size_mib * 1024 * 1024:
                data = row(domain, record, index, vocabulary_size)
                for pos in range(3):
                    emit(pos, data)
                size += len(data)
                index += 1
            final = row(domain, record, index, vocabulary_size)
            emit(0, final)
            emit(1, row(domain, record, index, vocabulary_size, invalid=True))
            emit(2, final.replace(f"</{record}>".encode(), b"</BrokenRecord>", 1))
            for pos in range(3):
                emit(pos, f"</{root}>\n".encode())
        finally:
            for stream in streams:
                stream.close()
        for pos, path in enumerate(paths):
            manifest["cases"].append({"xml": str(path.relative_to(ROOT)), "schema": f"{domain}/schema.xsd",
                                      "expected": ("valid", "schema-invalid", "malformed")[pos],
                                      "reason": ("conforms to schema", violation, "mismatched final record closing tag")[pos],
                                      "records": index, "error_line": None if pos == 0 else index + 2,
                                      "bytes": path.stat().st_size, "sha256": hashes[pos].hexdigest()})
        print(f"Generated {domain}: {index:,} records per XML", flush=True)
    manifest["schemas"] = [{"path": str(p.relative_to(ROOT)), "bytes": p.stat().st_size,
                            "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(ROOT.rglob("*.xsd"))]
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size-mib", type=int, default=32, help="Approximate size of EACH XML (default: 32 MiB)")
    parser.add_argument("--vocabulary-size", type=int, default=12000, help="Enumeration entries in each large XSD")
    args = parser.parse_args()
    if args.size_mib < 1 or args.vocabulary_size < 1:
        parser.error("size and vocabulary size must be positive")
    generate(args.size_mib, args.vocabulary_size)
