package dev.jdal.sql;

import dev.jdal.core.Layer;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.sql.Bound.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-to-one JOIN lookups: for each primary feature the first secondary feature for which the ON
 * expression holds. Equality on a key of each side is answered from a hash index built on first
 * use; other expressions scan the secondary layer.
 */
final class Joiner {

  private final Plan plan;
  private final List<List<Feature>> secondary = new ArrayList<>();
  private final Map<Integer, Map<Object, Feature>> indexes = new HashMap<>();

  Joiner(Plan plan) {
    this.plan = plan;
  }

  private List<Feature> rows(int table) {
    while (secondary.size() <= table) secondary.add(null);
    List<Feature> l = secondary.get(table);
    if (l == null) {
      Layer layer = plan.tables.get(table).layer();
      try (Stream<Feature> s = layer.features()) {
        l = s.toList();
      }
      secondary.set(table, l);
    }
    return l;
  }

  /** Fills {@code rows[1..]} with the join partners of {@code rows[0]} (null where none). */
  void fill(Feature[] rows) {
    for (Plan.JoinDef j : plan.joins) {
      rows[j.table()] = lookup(j, rows);
    }
  }

  private Feature lookup(Plan.JoinDef j, Feature[] rows) {
    BColumn primary = null, secondaryCol = null;
    if (j.on() instanceof BOp o
        && o.op() == SqlOp.EQ
        && o.args().size() == 2
        && o.args().get(0) instanceof BColumn a
        && o.args().get(1) instanceof BColumn b
        && a.ref().kind() == FieldRef.Kind.FIELD
        && b.ref().kind() == FieldRef.Kind.FIELD) {
      if (a.table() == 0 && b.table() == j.table()) {
        primary = a;
        secondaryCol = b;
      } else if (b.table() == 0 && a.table() == j.table()) {
        primary = b;
        secondaryCol = a;
      }
    }
    FeatureType st = plan.tables.get(j.table()).type();
    FeatureType pt = plan.tables.getFirst().type();
    if (primary != null && sameKeyKind(primary.type(), secondaryCol.type())) {
      Object key = key(rows[0].get(primary.ref().index()), primary.type());
      if (key == null) return null; // a null key never joins
      Map<Object, Feature> index =
          indexes.computeIfAbsent(
              j.table(),
              t -> {
                Map<Object, Feature> m = new HashMap<>();
                for (Feature f : rows(j.table())) {
                  Object k =
                      key(
                          f.get(((BColumn) secondaryColumn(j)).ref().index()),
                          ((BColumn) secondaryColumn(j)).type());
                  if (k != null) m.putIfAbsent(k, f);
                }
                return m;
              });
      return index.get(key);
    }
    for (Feature candidate : rows(j.table())) {
      Feature[] pair = rows.clone();
      pair[j.table()] = candidate;
      Val v =
          Eval.eval(
              j.on(), c -> Values.fetch(pair[c.table()], plan.tables.get(c.table()).type(), c));
      if (Eval.isTrue(v)) return candidate;
    }
    return null;
  }

  private static Bound secondaryColumn(Plan.JoinDef j) {
    BOp o = (BOp) j.on();
    BColumn a = (BColumn) o.args().get(0), b = (BColumn) o.args().get(1);
    return a.table() == j.table() ? a : b;
  }

  private static boolean sameKeyKind(SqlType a, SqlType b) {
    boolean ia = a.isInteger() || a == SqlType.BOOLEAN, ib = b.isInteger() || b == SqlType.BOOLEAN;
    return (ia && ib) || (a == SqlType.STRING && b == SqlType.STRING);
  }

  private static Object key(Object v, SqlType t) {
    if (v == null) return null;
    if (v instanceof Number n) return n.longValue();
    return v.toString().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
  }
}
