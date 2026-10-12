package dev.jdal.sql;

import dev.jdal.core.Dataset;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.SqlEngine;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.geom.Geometry;
import dev.jdal.sql.Ast.Select;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** The OGR SQL dialect, registered with {@link java.util.ServiceLoader}. */
public final class OgrSqlEngine implements SqlEngine {

  @Override
  public Predicate<Feature> compileFilter(FeatureType type, String where) {
    Bound b =
        new Binder(FieldList.forFilter(type), false, false).bind(Parser.parseExpression(where));
    return f -> Eval.isTrue(Eval.eval(b, c -> Values.fetch(f, type, c)));
  }

  @Override
  public Layer execute(Dataset dataset, String sql, Geometry spatialFilter) {
    String s = sql.strip();
    if (s.regionMatches(true, 0, "DELLAYER:", 0, 9)) {
      dataset.deleteLayer(s.substring(9).strip());
      return null;
    }
    if (Ddl.isDdl(s)) {
      Ddl.execute(dataset, s);
      return null;
    }
    Select sel = Parser.parseSelect(s);
    List<Layer> layers = new ArrayList<>();
    try {
      for (Select cur = sel; cur != null; cur = cur.union()) {
        layers.add(new SelectLayer(Plan.build(dataset, cur), spatialFilter));
      }
    } catch (RuntimeException e) {
      layers.forEach(Layer::close);
      throw e;
    }
    return layers.size() == 1 ? layers.getFirst() : new UnionAllLayer(layers);
  }

  static JdalException unsupported(String what) {
    return new JdalException(what);
  }
}
