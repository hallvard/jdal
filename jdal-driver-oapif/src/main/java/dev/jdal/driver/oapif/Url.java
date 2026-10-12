package dev.jdal.driver.oapif;

/** A URL split into everything before the query, and the query as the user wrote it. */
record Url(String base, String path, String query) {

  static Url parse(String url) {
    String query = "";
    int q = url.indexOf('?');
    if (q >= 0) {
      query = url.substring(q + 1);
      url = url.substring(0, q);
    }
    int slashes = url.indexOf("://");
    int pathStart = url.indexOf('/', slashes < 0 ? 0 : slashes + 3);
    if (pathStart < 0) return new Url(url, "", query);
    return new Url(url.substring(0, pathStart), url.substring(pathStart), query);
  }

  Url with(String newPath, String newQuery) {
    return new Url(base, newPath, newQuery);
  }

  /** This URL with extra parameters in front of the user's own ones. */
  String toString(String extraParams) {
    String q =
        extraParams.isEmpty() ? query : query.isEmpty() ? extraParams : extraParams + "&" + query;
    return base + path + (q.isEmpty() ? "" : "?" + q);
  }

  @Override
  public String toString() {
    return toString("");
  }
}
