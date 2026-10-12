package dev.jdal.gml;

import dev.jdal.core.JdalException;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.feature.GeomFieldDefn;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Feature types from a GML application schema (an XSD, as returned by WFS {@code
 * DescribeFeatureType}): top-level elements whose type derives from {@code gml:AbstractFeatureType}
 * become feature types; simple elements become fields and GML property types become geometry
 * fields. Nested complex content is not flattened.
 */
public final class XsdSchema {

  private XsdSchema() {}

  /** An XML element reduced to its local name, attributes and children. */
  private record Node(String name, Map<String, String> attrs, List<Node> children, String text) {
    String attr(String n) {
      return attrs.get(n);
    }

    Node child(String n) {
      for (Node c : children) if (c.name.equals(n)) return c;
      return null;
    }

    List<Node> all(String n) {
      return children.stream().filter(c -> c.name.equals(n)).toList();
    }
  }

  /**
   * Parses a schema. {@code includes} opens the schemas named by {@code xs:include} and {@code
   * xs:import} elements (it may return null for locations it cannot open); it may be null.
   */
  public static Map<String, FeatureType> parse(Reader xsd, Function<String, Reader> includes) {
    return parse(xsd, includes, false);
  }

  /**
   * As {@link #parse(Reader, Function)}; with {@code keepGeometryNames} a single geometry property
   * keeps its name instead of being called "" (WFS needs the name to build spatial filters).
   */
  public static Map<String, FeatureType> parse(
      Reader xsd, Function<String, Reader> includes, boolean keepGeometryNames) {
    Map<String, Node> complexTypes = new LinkedHashMap<>();
    Map<String, Node> simpleTypes = new LinkedHashMap<>();
    List<Node> elements = new ArrayList<>();
    load(xsd, includes, complexTypes, simpleTypes, elements, 0);
    Map<String, FeatureType> out = new LinkedHashMap<>();
    for (Node e : elements) {
      String name = e.attr("name");
      if (name == null) continue;
      Node type = typeOf(e, complexTypes);
      if (type == null || !derivesFromFeature(type, complexTypes, 0)) continue;
      List<FieldDefn> fields = new ArrayList<>();
      List<GeomFieldDefn> geoms = new ArrayList<>();
      collect(type, complexTypes, simpleTypes, fields, geoms, 0);
      if (geoms.size() == 1 && !keepGeometryNames)
        geoms =
            List.of(
                new GeomFieldDefn(
                    "", geoms.getFirst().type(), geoms.getFirst().dimension(), null, true));
      out.put(name, new FeatureType(name, fields, geoms));
    }
    return out;
  }

  public static Map<String, FeatureType> parse(String xsd) {
    return parse(new StringReader(xsd), null);
  }

  // ------------------------------------------------------------------ reading the XML

  private static void load(
      Reader xsd,
      Function<String, Reader> includes,
      Map<String, Node> complexTypes,
      Map<String, Node> simpleTypes,
      List<Node> elements,
      int depth) {
    Node root = tree(xsd);
    if (root == null) return;
    for (Node c : root.children) {
      switch (c.name) {
        case "complexType" -> {
          if (c.attr("name") != null) complexTypes.put(c.attr("name"), c);
        }
        case "simpleType" -> {
          if (c.attr("name") != null) simpleTypes.put(c.attr("name"), c);
        }
        case "element" -> elements.add(c);
        case "include", "import" -> {
          String loc = c.attr("schemaLocation");
          if (includes != null && loc != null && depth < 8) {
            Reader r = includes.apply(loc);
            if (r != null) load(r, includes, complexTypes, simpleTypes, elements, depth + 1);
          }
        }
        default -> {}
      }
    }
  }

  private static Node tree(Reader in) {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader r = f.createXMLStreamReader(in);
      while (r.hasNext()) {
        if (r.next() == XMLStreamConstants.START_ELEMENT) return node(r);
      }
      return null;
    } catch (XMLStreamException e) {
      throw new JdalException("Invalid XML schema: " + e.getMessage(), e);
    }
  }

  private static Node node(XMLStreamReader r) throws XMLStreamException {
    Map<String, String> attrs = new LinkedHashMap<>();
    for (int i = 0; i < r.getAttributeCount(); i++)
      attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
    List<Node> children = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    String name = r.getLocalName();
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.START_ELEMENT) children.add(node(r));
      else if (e == XMLStreamConstants.CHARACTERS) text.append(r.getText());
      else if (e == XMLStreamConstants.END_ELEMENT) break;
    }
    return new Node(name, attrs, children, text.toString());
  }

  // ------------------------------------------------------------------ interpreting it

  private static String local(String qname) {
    if (qname == null) return null;
    int c = qname.indexOf(':');
    return c < 0 ? qname : qname.substring(c + 1);
  }

  private static Node typeOf(Node element, Map<String, Node> complexTypes) {
    Node inline = element.child("complexType");
    if (inline != null) return inline;
    String t = local(element.attr("type"));
    return t == null ? null : complexTypes.get(t);
  }

  private static boolean derivesFromFeature(Node type, Map<String, Node> complexTypes, int depth) {
    if (depth > 20) return false;
    Node ext = extension(type);
    if (ext == null) return false;
    String base = local(ext.attr("base"));
    if (base == null) return false;
    if (base.equals("AbstractFeatureType")) return true;
    Node parent = complexTypes.get(base);
    return parent != null && derivesFromFeature(parent, complexTypes, depth + 1);
  }

  private static Node extension(Node type) {
    Node cc = type.child("complexContent");
    return cc == null ? null : cc.child("extension");
  }

  private static void collect(
      Node type,
      Map<String, Node> complexTypes,
      Map<String, Node> simpleTypes,
      List<FieldDefn> fields,
      List<GeomFieldDefn> geoms,
      int depth) {
    if (depth > 20) return;
    Node ext = extension(type);
    Node body = type;
    if (ext != null) {
      Node parent = complexTypes.get(local(ext.attr("base")));
      if (parent != null) collect(parent, complexTypes, simpleTypes, fields, geoms, depth + 1);
      body = ext;
    }
    for (String group : List.of("sequence", "all", "choice")) {
      Node g = body.child(group);
      if (g != null) properties(g, simpleTypes, fields, geoms);
    }
  }

  private static void properties(
      Node group,
      Map<String, Node> simpleTypes,
      List<FieldDefn> fields,
      List<GeomFieldDefn> geoms) {
    for (Node c : group.children) {
      if (c.name.equals("choice")) {
        List<FieldDefn> f = new ArrayList<>();
        List<GeomFieldDefn> g = new ArrayList<>();
        properties(c, simpleTypes, f, g);
        if (f.isEmpty() && g.size() > 1) {
          // a choice between geometry kinds is one geometry field, promoted to the multi type
          geoms.add(
              new GeomFieldDefn(g.getFirst().name(), commonType(g), Dimension.XY, null, true));
        } else {
          fields.addAll(f);
          geoms.addAll(g);
        }
        continue;
      }
      if (c.name.equals("sequence")) {
        properties(c, simpleTypes, fields, geoms);
        continue;
      }
      if (!c.name.equals("element")) continue;
      String name = c.attr("name") != null ? c.attr("name") : local(c.attr("ref"));
      if (name == null) continue;
      String typeName = local(c.attr("type"));
      GeometryType gt =
          typeName != null ? geometryType(typeName) : refGeometryType(local(c.attr("ref")));
      if (gt != null) {
        geoms.add(new GeomFieldDefn(name, gt, Dimension.XY, null, true));
        continue;
      }
      if (c.attr("ref") != null) continue; // gml:name, gml:boundedBy, ...
      boolean list = "unbounded".equals(c.attr("maxOccurs")) || number(c.attr("maxOccurs"), 1) > 1;
      boolean nullable = "0".equals(c.attr("minOccurs")) || "true".equals(c.attr("nillable"));
      FieldDefn f = field(name, c, typeName, simpleTypes, list);
      if (f != null) fields.add(f.withNullable(nullable));
    }
  }

  private static GeometryType commonType(List<GeomFieldDefn> geoms) {
    GeometryType t = geoms.getFirst().type();
    for (GeomFieldDefn g : geoms) {
      GeometryType u = g.type();
      if (u == t) continue;
      if (promote(u) == promote(t)) t = promote(t);
      else return GeometryType.UNKNOWN;
    }
    return t;
  }

  /** The multi type of a simple one. */
  static GeometryType promote(GeometryType t) {
    return switch (t) {
      case POINT -> GeometryType.MULTIPOINT;
      case LINESTRING -> GeometryType.MULTILINESTRING;
      case POLYGON -> GeometryType.MULTIPOLYGON;
      default -> t;
    };
  }

  private static int number(String s, int fallback) {
    try {
      return s == null ? fallback : Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static GeometryType geometryType(String type) {
    return switch (type) {
      case "PointPropertyType" -> GeometryType.POINT;
      case "LineStringPropertyType", "CurvePropertyType" -> GeometryType.LINESTRING;
      case "PolygonPropertyType", "SurfacePropertyType" -> GeometryType.POLYGON;
      case "MultiPointPropertyType" -> GeometryType.MULTIPOINT;
      case "MultiLineStringPropertyType", "MultiCurvePropertyType" -> GeometryType.MULTILINESTRING;
      case "MultiPolygonPropertyType", "MultiSurfacePropertyType" -> GeometryType.MULTIPOLYGON;
      case "MultiGeometryPropertyType", "GeometryCollectionPropertyType" ->
          GeometryType.GEOMETRYCOLLECTION;
      case "GeometryPropertyType", "GeometryAssociationType" -> GeometryType.UNKNOWN;
      default -> null;
    };
  }

  private static GeometryType refGeometryType(String ref) {
    if (ref == null) return null;
    return switch (ref) {
      case "pointProperty" -> GeometryType.POINT;
      case "lineStringProperty", "curveProperty" -> GeometryType.LINESTRING;
      case "polygonProperty", "surfaceProperty" -> GeometryType.POLYGON;
      case "multiPointProperty" -> GeometryType.MULTIPOINT;
      case "multiLineStringProperty", "multiCurveProperty" -> GeometryType.MULTILINESTRING;
      case "multiPolygonProperty", "multiSurfaceProperty" -> GeometryType.MULTIPOLYGON;
      case "multiGeometryProperty" -> GeometryType.GEOMETRYCOLLECTION;
      case "geometryProperty", "_geometryProperty" -> GeometryType.UNKNOWN;
      default -> null;
    };
  }

  private static FieldDefn field(
      String name, Node element, String typeName, Map<String, Node> simpleTypes, boolean list) {
    Node restriction = null;
    String base = typeName;
    Node inline = element.child("simpleType");
    if (inline != null) restriction = inline.child("restriction");
    else if (typeName != null && simpleTypes.containsKey(typeName))
      restriction = simpleTypes.get(typeName).child("restriction");
    int width = 0;
    int precision = 0;
    int depth = 0;
    while (restriction != null && depth++ < 10) {
      base = local(restriction.attr("base"));
      for (Node facet : restriction.children) {
        switch (facet.name) {
          case "maxLength", "length" -> width = Math.max(width, number(facet.attr("value"), 0));
          case "totalDigits" -> width = Math.max(width, number(facet.attr("value"), 0));
          case "fractionDigits" -> precision = number(facet.attr("value"), 0);
          default -> {}
        }
      }
      Node next = base != null ? simpleTypes.get(base) : null;
      restriction = next == null ? null : next.child("restriction");
    }
    if (base == null) return null; // complex content
    FieldType t;
    FieldSubType sub = FieldSubType.NONE;
    switch (base) {
      case "int",
          "integer",
          "short",
          "byte",
          "unsignedByte",
          "unsignedShort",
          "nonPositiveInteger",
          "negativeInteger",
          "nonNegativeInteger",
          "positiveInteger" ->
          t = list ? FieldType.INTEGER_LIST : FieldType.INTEGER;
      case "long", "unsignedInt", "unsignedLong" ->
          t = list ? FieldType.INTEGER64_LIST : FieldType.INTEGER64;
      case "decimal", "float", "double" -> t = list ? FieldType.REAL_LIST : FieldType.REAL;
      case "boolean" -> {
        t = FieldType.INTEGER;
        sub = FieldSubType.BOOLEAN;
      }
      case "date" -> t = FieldType.DATE;
      case "dateTime" -> t = FieldType.DATETIME;
      case "time" -> t = FieldType.TIME;
      case "string",
          "normalizedString",
          "token",
          "anyURI",
          "ID",
          "NCName",
          "Name",
          "language",
          "anySimpleType" ->
          t = list ? FieldType.STRING_LIST : FieldType.STRING;
      default -> {
        return null; // a complex type: not a plain attribute
      }
    }
    return new FieldDefn(
        name,
        t,
        sub,
        t == FieldType.REAL ? width : width,
        precision,
        true,
        false,
        null,
        null,
        null);
  }
}
