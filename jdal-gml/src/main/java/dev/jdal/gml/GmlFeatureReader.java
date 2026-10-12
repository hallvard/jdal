package dev.jdal.gml;

import dev.jdal.core.JdalException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streams the features of a GML document: {@code featureMember}, {@code featureMembers} and {@code
 * member} (WFS 2.0) below the root element, or, without them, the root's children. The parser does
 * not load DTDs or external entities.
 */
public final class GmlFeatureReader implements Iterator<GmlFeature>, AutoCloseable {

  private static final Set<String> MEMBERS =
      Set.of("featureMember", "featureMembers", "member", "members");
  private static final Set<String> METADATA =
      Set.of(
          "boundedBy",
          "name",
          "description",
          "metaDataProperty",
          "identifier",
          "descriptionReference",
          "location");

  private static XMLInputFactory factory() {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setProperty(XMLInputFactory.IS_COALESCING, true);
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    return f;
  }

  private final XMLStreamReader r;
  private final AutoCloseable source;
  private String rootBoundedBySrs;
  private int rootDimension;
  private boolean atRoot = true;
  private String schemaLocation;
  private String rootName;
  private final java.util.Map<String, String> rootAttributes = new java.util.HashMap<>();
  private boolean started;
  private boolean inMember; // positioned between features of a member wrapper
  private boolean finished;
  private GmlFeature pending;

  public GmlFeatureReader(Reader in) {
    this(in, in);
  }

  public GmlFeatureReader(InputStream in) {
    this(in, in);
  }

  private GmlFeatureReader(Object in, AutoCloseable source) {
    this.source = source;
    try {
      this.r =
          in instanceof Reader rd
              ? factory().createXMLStreamReader(rd)
              : factory().createXMLStreamReader((InputStream) in);
    } catch (XMLStreamException e) {
      throw new JdalException("Cannot read GML: " + e.getMessage(), e);
    }
  }

  /** {@code xsi:schemaLocation} of the root element (pairs of namespace and location), or null. */
  public String schemaLocation() {
    start();
    return schemaLocation;
  }

  /** An attribute of the root element by local name (such as {@code numberMatched}), or null. */
  public String rootAttribute(String localName) {
    start();
    return rootAttributes.get(localName);
  }

  /** Local name of the root element, or null for an empty document. */
  public String rootName() {
    start();
    return rootName;
  }

  private void start() {
    if (started) return;
    started = true;
    try {
      while (r.hasNext()) {
        if (r.next() == XMLStreamConstants.START_ELEMENT) {
          rootName = r.getLocalName();
          schemaLocation =
              r.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation");
          for (int i = 0; i < r.getAttributeCount(); i++)
            rootAttributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
          return;
        }
      }
      finished = true;
    } catch (XMLStreamException e) {
      throw new JdalException("Invalid GML: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean hasNext() {
    if (pending == null) pending = fetch();
    return pending != null;
  }

  @Override
  public GmlFeature next() {
    if (!hasNext()) throw new NoSuchElementException();
    GmlFeature f = pending;
    pending = null;
    return f;
  }

  private GmlFeature fetch() {
    start();
    if (finished) return null;
    try {
      while (true) {
        if (inMember) {
          if (GmlGeometries.nextChild(r)) {
            if (isRealFeature()) return feature();
            GmlGeometries.skip(r);
            continue;
          }
          inMember = false; // the member wrapper ended
        }
        if (!GmlGeometries.nextChild(r)) {
          finished = true;
          return null;
        }
        String n = r.getLocalName();
        if (MEMBERS.contains(n)) {
          atRoot = false;
          inMember = true;
        } else if (n.equals("boundedBy")) {
          rootBoundedBySrs = boundedBySrs();
        } else if (METADATA.contains(n) || !isRealFeature()) {
          atRoot = false;
          GmlGeometries.skip(r);
        } else {
          return feature();
        }
      }
    } catch (XMLStreamException e) {
      finished = true;
      throw new JdalException("Invalid GML: " + e.getMessage(), e);
    }
  }

  /** Elements such as an empty {@code xlink:href} placeholder are not features. */
  private boolean isRealFeature() {
    return r.getAttributeValue("http://www.w3.org/1999/xlink", "href") == null
        || r.getAttributeCount() > 1;
  }

  /** Reads {@code boundedBy} and returns the srsName of its envelope. */
  private String boundedBySrs() throws XMLStreamException {
    String srs = null;
    while (GmlGeometries.nextChild(r)) {
      if (srs == null) srs = r.getAttributeValue(null, "srsName");
      if (atRoot && rootDimension == 0) {
        String d = r.getAttributeValue(null, "srsDimension");
        if (d != null) rootDimension = Integer.parseInt(d.strip());
      }
      GmlGeometries.skip(r);
    }
    return srs;
  }

  private GmlFeature feature() throws XMLStreamException {
    String typeName = r.getLocalName();
    String id = null;
    String idAttribute = null;
    for (int i = 0; i < r.getAttributeCount(); i++) {
      String n = r.getAttributeLocalName(i);
      if (n.equals("id") || n.equals("fid")) {
        id = r.getAttributeValue(i);
        idAttribute = n.equals("fid") ? "fid" : "gml_id";
        if (r.getAttributeNamespace(i) != null
            && !r.getAttributeNamespace(i).isEmpty()
            && n.equals("id")) break;
      }
    }
    List<GmlFeature.Property> props = new ArrayList<>();
    List<GmlFeature.GeometryProperty> geoms = new ArrayList<>();
    String boundedBy = null;
    while (GmlGeometries.nextChild(r)) {
      String name = r.getLocalName();
      if (name.equals("boundedBy")) {
        boundedBy = boundedBySrs();
      } else {
        property(name, "", props, geoms);
      }
    }
    return new GmlFeature(
        typeName, id, idAttribute, props, geoms, boundedBy != null ? boundedBy : rootBoundedBySrs);
  }

  /**
   * Reads one property element (positioned on it); nested elements are flattened with underscores.
   */
  private void property(
      String name,
      String prefix,
      List<GmlFeature.Property> props,
      List<GmlFeature.GeometryProperty> geoms)
      throws XMLStreamException {
    String full = prefix.isEmpty() ? name : prefix + "_" + name;
    StringBuilder text = new StringBuilder();
    boolean sawChild = false;
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
        text.append(r.getText());
      } else if (e == XMLStreamConstants.START_ELEMENT) {
        sawChild = true;
        if (GmlGeometries.isGeometry(r.getLocalName())) {
          GmlGeometries.Result g = GmlGeometries.read(r, null, rootDimension);
          geoms.add(
              new GmlFeature.GeometryProperty(
                  prefix.isEmpty() ? name : full, g.geometry(), g.srsName()));
        } else if (r.getAttributeValue("http://www.w3.org/1999/xlink", "href") != null
            && !hasContent()) {
          GmlGeometries.skip(r);
        } else {
          property(r.getLocalName(), full, props, geoms);
        }
      } else if (e == XMLStreamConstants.END_ELEMENT) {
        break;
      }
    }
    if (!sawChild) {
      String t = text.toString().strip();
      props.add(new GmlFeature.Property(full, t));
    }
  }

  private boolean hasContent() {
    return r.getAttributeCount() > 1;
  }

  @Override
  public void close() {
    try {
      r.close();
    } catch (XMLStreamException e) {
      // nothing to do
    }
    try {
      source.close();
    } catch (Exception e) {
      throw new JdalException("Cannot close GML source: " + e.getMessage(), e);
    }
  }

  /** Reads all features of a document that is already in memory. */
  public static List<GmlFeature> readAll(String xml) {
    List<GmlFeature> out = new ArrayList<>();
    try (GmlFeatureReader reader = new GmlFeatureReader(new java.io.StringReader(xml))) {
      reader.forEachRemaining(out::add);
    }
    return out;
  }
}
