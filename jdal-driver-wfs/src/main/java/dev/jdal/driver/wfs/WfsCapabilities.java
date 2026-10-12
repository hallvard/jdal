package dev.jdal.driver.wfs;

import dev.jdal.core.JdalException;
import dev.jdal.geom.Envelope;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** What a WFS GetCapabilities document says about the service and its feature types. */
record WfsCapabilities(
    String version,
    List<FeatureTypeInfo> featureTypes,
    String getFeatureUrl,
    String describeFeatureTypeUrl,
    List<String> outputFormats,
    boolean pagingImplemented) {

  record FeatureTypeInfo(
      String name,
      String title,
      String abstractText,
      String defaultSrs,
      List<String> otherSrs,
      Envelope wgs84Bbox) {}

  /** Parses the document; WFS and OWS exception reports become exceptions. */
  static WfsCapabilities parse(String xml) {
    Document doc = document(xml);
    Element root = doc.getDocumentElement();
    String rootName = local(root);
    if (rootName.endsWith("ExceptionReport"))
      throw new JdalException("WFS server error: " + exceptionText(root));
    if (!rootName.equals("WFS_Capabilities"))
      throw new JdalException("Cannot find WFS_Capabilities in the response");
    String version = root.getAttribute("version");
    Element list = firstDescendant(root, "FeatureTypeList");
    if (list == null) throw new JdalException("Cannot find FeatureTypeList in the capabilities");
    List<FeatureTypeInfo> types = new ArrayList<>();
    for (Element ft : children(list, "FeatureType")) {
      String name = text(ft, "Name");
      if (name == null || name.isEmpty()) continue;
      String srs = firstNonNull(text(ft, "DefaultSRS"), text(ft, "DefaultCRS"), text(ft, "SRS"));
      List<String> other = new ArrayList<>();
      for (String tag : List.of("OtherSRS", "OtherCRS"))
        for (Element e : children(ft, tag)) other.add(e.getTextContent().strip());
      types.add(
          new FeatureTypeInfo(name, text(ft, "Title"), text(ft, "Abstract"), srs, other, bbox(ft)));
    }
    String[] urls = operationUrls(root);
    List<String> formats = new ArrayList<>();
    boolean paging = false;
    Element ops = firstDescendant(root, "OperationsMetadata");
    if (ops != null) {
      for (Element op : children(ops, "Operation")) {
        if (!"GetFeature".equals(op.getAttribute("name"))) continue;
        for (Element p : children(op, "Parameter")) {
          if (p.getAttribute("name").equalsIgnoreCase("outputFormat")) {
            NodeList values = p.getElementsByTagNameNS("*", "Value");
            for (int i = 0; i < values.getLength(); i++)
              formats.add(values.item(i).getTextContent().strip());
          }
        }
      }
      for (Element c : descendants(ops, "Constraint")) {
        if ("ImplementsResultPaging".equals(c.getAttribute("name"))) {
          Element v = firstDescendant(c, "DefaultValue");
          paging = v != null && v.getTextContent().strip().equalsIgnoreCase("TRUE");
        }
      }
    }
    return new WfsCapabilities(version, types, urls[0], urls[1], formats, paging);
  }

  /** GetFeature and DescribeFeatureType URLs as declared by the service, or null. */
  private static String[] operationUrls(Element root) {
    String getFeature = null;
    String describe = null;
    for (Element op : descendants(root, "Operation")) {
      String url = getUrl(op);
      if ("GetFeature".equals(op.getAttribute("name")) && url != null) getFeature = url;
      if ("DescribeFeatureType".equals(op.getAttribute("name")) && url != null) describe = url;
    }
    // WFS 1.0: Capability/Request/GetFeature/DCPType/HTTP/Get
    Element request = firstDescendant(root, "Request");
    if (request != null) {
      for (Element e : children(request, "GetFeature"))
        if (getUrl(e) != null) getFeature = getUrl(e);
      for (Element e : children(request, "DescribeFeatureType"))
        if (getUrl(e) != null) describe = getUrl(e);
    }
    return new String[] {getFeature, describe};
  }

  private static String getUrl(Element op) {
    for (Element get : descendants(op, "Get")) {
      String href = get.getAttributeNS("http://www.w3.org/1999/xlink", "href");
      if (href.isEmpty()) href = get.getAttribute("onlineResource");
      if (href.isEmpty()) href = get.getAttribute("xlink:href");
      if (!href.isEmpty()) return href;
    }
    return null;
  }

  private static Envelope bbox(Element ft) {
    Element w = firstDescendant(ft, "WGS84BoundingBox");
    if (w != null) {
      String lo = text(w, "LowerCorner");
      String up = text(w, "UpperCorner");
      if (lo != null && up != null) {
        String[] a = lo.strip().split("\\s+");
        String[] b = up.strip().split("\\s+");
        if (a.length >= 2 && b.length >= 2)
          return new Envelope(
              Double.parseDouble(a[0]),
              Double.parseDouble(a[1]),
              Double.parseDouble(b[0]),
              Double.parseDouble(b[1]));
      }
    }
    Element l = firstDescendant(ft, "LatLongBoundingBox");
    if (l != null && !l.getAttribute("minx").isEmpty()) {
      return new Envelope(
          Double.parseDouble(l.getAttribute("minx")), Double.parseDouble(l.getAttribute("miny")),
          Double.parseDouble(l.getAttribute("maxx")), Double.parseDouble(l.getAttribute("maxy")));
    }
    return null;
  }

  // ------------------------------------------------------------------ DOM helpers

  static Document document(String xml) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    } catch (Exception e) {
      throw new JdalException("Invalid XML in the WFS response: " + e.getMessage(), e);
    }
  }

  static String exceptionText(Element root) {
    for (String tag : List.of("ExceptionText", "ServiceException")) {
      Element e = firstDescendant(root, tag);
      if (e != null) return e.getTextContent().strip();
    }
    return root.getTextContent().strip();
  }

  static String local(Node n) {
    String l = n.getLocalName();
    return l != null ? l : n.getNodeName();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && local(e).equals(name)) out.add(e);
    }
    return out;
  }

  private static List<Element> descendants(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    NodeList l = parent.getElementsByTagNameNS("*", name);
    for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
    return out;
  }

  private static Element firstDescendant(Element parent, String name) {
    NodeList l = parent.getElementsByTagNameNS("*", name);
    return l.getLength() == 0 ? null : (Element) l.item(0);
  }

  private static String text(Element parent, String name) {
    List<Element> c = children(parent, name);
    return c.isEmpty() ? null : c.getFirst().getTextContent().strip();
  }

  private static String firstNonNull(String... v) {
    for (String s : v) if (s != null) return s;
    return null;
  }
}
