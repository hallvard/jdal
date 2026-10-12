package dev.jdal.sql;

import dev.jdal.core.Dataset;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The non-SELECT statements of OGR SQL: CREATE/DROP INDEX, DROP TABLE and ALTER TABLE. */
final class Ddl {

  private Ddl() {}

  /** Splits on blanks keeping double-quoted parts together (CSLTokenizeString). */
  static List<String> tokens(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (!quoted && Character.isWhitespace(c)) {
        if (cur.length() > 0) out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    if (cur.length() > 0) out.add(cur.toString());
    return out;
  }

  static boolean isDdl(String sql) {
    String u = sql.stripLeading().toUpperCase(Locale.ROOT);
    return u.startsWith("CREATE INDEX")
        || u.startsWith("DROP INDEX")
        || u.startsWith("DROP TABLE")
        || u.startsWith("ALTER TABLE");
  }

  static void execute(Dataset ds, String sql) {
    List<String> t = tokens(sql);
    String u = sql.stripLeading().toUpperCase(Locale.ROOT);
    if (u.startsWith("CREATE INDEX")) createIndex(ds, sql, t);
    else if (u.startsWith("DROP INDEX")) dropIndex(ds, sql, t);
    else if (u.startsWith("DROP TABLE")) dropTable(ds, sql, t);
    else alterTable(ds, sql, t);
  }

  private static boolean eq(List<String> t, int i, String s) {
    return i < t.size() && t.get(i).equalsIgnoreCase(s);
  }

  private static Layer layer(Dataset ds, String name, String what) {
    return ds.layer(name)
        .orElseThrow(() -> new JdalException(what + " failed, no such layer as `" + name + "'."));
  }

  private static void createIndex(Dataset ds, String sql, List<String> t) {
    if (t.size() != 6
        || !eq(t, 0, "CREATE")
        || !eq(t, 1, "INDEX")
        || !eq(t, 2, "ON")
        || !eq(t, 4, "USING")) {
      throw new JdalException(
          "Syntax error in CREATE INDEX command.\nWas '"
              + sql
              + "'\nShould be of form 'CREATE INDEX ON <table> USING <field>'");
    }
    Layer l = layer(ds, t.get(3), "CREATE INDEX ON");
    if (l.type().fieldIndex(t.get(5)).isEmpty())
      throw new JdalException("`" + sql + "' failed, field not found.");
    try {
      l.createAttributeIndex(t.get(5));
    } catch (UnsupportedOperationException e) {
      throw new JdalException(e.getMessage(), e);
    }
  }

  private static void dropIndex(Dataset ds, String sql, List<String> t) {
    if ((t.size() != 4 && t.size() != 6)
        || !eq(t, 0, "DROP")
        || !eq(t, 1, "INDEX")
        || !eq(t, 2, "ON")
        || (t.size() == 6 && !eq(t, 4, "USING"))) {
      throw new JdalException(
          "Syntax error in DROP INDEX command.\nWas '"
              + sql
              + "'\nShould be of form 'DROP INDEX ON <table> [USING <field>]'");
    }
    Layer l = layer(ds, t.get(3), "DROP INDEX ON");
    if (t.size() == 6 && l.type().fieldIndex(t.get(5)).isEmpty())
      throw new JdalException("`" + sql + "' failed, field not found.");
    try {
      l.dropAttributeIndex(t.size() == 6 ? t.get(5) : null);
    } catch (UnsupportedOperationException e) {
      throw new JdalException(e.getMessage(), e);
    }
  }

  private static void dropTable(Dataset ds, String sql, List<String> t) {
    if (t.size() != 3 || !eq(t, 0, "DROP") || !eq(t, 1, "TABLE")) {
      throw new JdalException(
          "Syntax error in DROP TABLE command.\nWas '"
              + sql
              + "'\nShould be of form 'DROP TABLE <table>'");
    }
    layer(ds, t.get(2), "DROP TABLE");
    try {
      ds.deleteLayer(t.get(2));
    } catch (UnsupportedOperationException e) {
      throw new JdalException(e.getMessage(), e);
    }
  }

  private static void alterTable(Dataset ds, String sql, List<String> t) {
    try {
      if (t.size() >= 4 && eq(t, 3, "ADD")) addColumn(ds, sql, t);
      else if (t.size() >= 4 && eq(t, 3, "DROP")) dropColumn(ds, sql, t);
      else if (t.size() == 6 && eq(t, 3, "RENAME") && eq(t, 4, "TO"))
        layer(ds, t.get(2), sql).rename(t.get(5));
      else if (t.size() >= 4 && eq(t, 3, "RENAME")) renameColumn(ds, sql, t);
      else if (t.size() >= 4 && eq(t, 3, "ALTER")) alterColumn(ds, sql, t);
      else throw new JdalException("Unsupported ALTER TABLE command : " + sql);
    } catch (UnsupportedOperationException e) {
      throw new JdalException(e.getMessage(), e);
    }
  }

  /** ALTER TABLE t ADD [COLUMN] name type */
  private static void addColumn(Dataset ds, String sql, List<String> t) {
    String layerName, column;
    int typeIndex;
    if (t.size() >= 7 && eq(t, 4, "COLUMN")) {
      layerName = t.get(2);
      column = t.get(5);
      typeIndex = 6;
    } else if (t.size() >= 6) {
      layerName = t.get(2);
      column = t.get(4);
      typeIndex = 5;
    } else {
      throw new JdalException(
          "Syntax error in ALTER TABLE ADD COLUMN command.\nWas '"
              + sql
              + "'\n"
              + "Should be of form 'ALTER TABLE <layername> ADD [COLUMN] <columnname>"
              + " <columntype>'");
    }
    Layer l = layer(ds, layerName, sql);
    l.addField(parseType(column, String.join("", t.subList(typeIndex, t.size()))));
  }

  private static void dropColumn(Dataset ds, String sql, List<String> t) {
    String layerName, column;
    if (t.size() == 6 && eq(t, 4, "COLUMN")) {
      layerName = t.get(2);
      column = t.get(5);
    } else if (t.size() == 5) {
      layerName = t.get(2);
      column = t.get(4);
    } else {
      throw new JdalException(
          "Syntax error in ALTER TABLE DROP COLUMN command.\nWas '"
              + sql
              + "'\nShould be of form 'ALTER TABLE <layername> DROP [COLUMN] <columnname>'");
    }
    Layer l = layer(ds, layerName, sql);
    if (l.type().fieldIndex(column).isEmpty())
      throw new JdalException("`" + sql + "' failed, no such field as `" + column + "'.");
    l.deleteField(column);
  }

  private static void renameColumn(Dataset ds, String sql, List<String> t) {
    // ALTER TABLE t RENAME [COLUMN] old TO new
    int base = eq(t, 4, "COLUMN") ? 5 : 4;
    if (t.size() != base + 3 || !eq(t, base + 1, "TO")) {
      throw new JdalException(
          "Syntax error in ALTER TABLE RENAME COLUMN command.\nWas '"
              + sql
              + "'\n"
              + "Should be of form 'ALTER TABLE <layername> RENAME [COLUMN] <oldname> TO"
              + " <newname>'");
    }
    Layer l = layer(ds, t.get(2), sql);
    int idx =
        l.type()
            .fieldIndex(t.get(base))
            .orElseThrow(
                () ->
                    new JdalException(
                        "`" + sql + "' failed, no such field as `" + t.get(base) + "'."));
    l.alterField(t.get(base), l.type().fields().get(idx).withName(t.get(base + 2)));
  }

  /** ALTER TABLE t ALTER [COLUMN] name TYPE type | SET NOT NULL | DROP NOT NULL ... */
  private static void alterColumn(Dataset ds, String sql, List<String> t) {
    int base = eq(t, 4, "COLUMN") ? 5 : 4;
    if (t.size() < base + 2) {
      throw new JdalException(
          "Syntax error in ALTER TABLE ALTER COLUMN command.\nWas '"
              + sql
              + "'\n"
              + "Should be of form 'ALTER TABLE <layername> ALTER [COLUMN] <columnname> TYPE"
              + " <columntype>'");
    }
    Layer l = layer(ds, t.get(2), sql);
    String column = t.get(base);
    int idx =
        l.type()
            .fieldIndex(column)
            .orElseThrow(
                () ->
                    new JdalException("`" + sql + "' failed, no such field as `" + column + "'."));
    FieldDefn old = l.type().fields().get(idx);
    if (eq(t, base + 1, "TYPE")) {
      FieldDefn n = parseType(old.name(), String.join("", t.subList(base + 2, t.size())));
      l.alterField(column, n.withNullable(old.nullable()));
    } else if (eq(t, base + 1, "SET") && eq(t, base + 2, "NOT") && eq(t, base + 3, "NULL")) {
      l.alterField(column, old.withNullable(false));
    } else if (eq(t, base + 1, "DROP") && eq(t, base + 2, "NOT") && eq(t, base + 3, "NULL")) {
      l.alterField(column, old.withNullable(true));
    } else if (eq(t, base + 1, "SET") && eq(t, base + 2, "DEFAULT")) {
      l.alterField(column, old.withDefault(String.join(" ", t.subList(base + 3, t.size()))));
    } else if (eq(t, base + 1, "DROP") && eq(t, base + 2, "DEFAULT")) {
      l.alterField(column, old.withDefault(null));
    } else {
      throw new JdalException("Unsupported ALTER TABLE command : " + sql);
    }
  }

  /**
   * SQL type names as accepted by ALTER TABLE ADD COLUMN, e.g. {@code varchar(20)}, {@code
   * numeric(10,3)}, {@code text[]}.
   */
  static FieldDefn parseType(String name, String text) {
    int width = 0, precision = 0;
    String type = text;
    int paren = text.indexOf('(');
    if (paren >= 0) {
      type = text.substring(0, paren);
      String[] parts =
          text.substring(paren + 1, text.indexOf(')') < 0 ? text.length() : text.indexOf(')'))
              .split(",");
      width = CNum.atoi(parts[0]);
      if (parts.length > 1) precision = CNum.atoi(parts[1]);
    }
    FieldType ft;
    switch (type.toUpperCase(Locale.ROOT)) {
      case "INTEGER" -> ft = FieldType.INTEGER;
      case "INTEGER[]" -> ft = FieldType.INTEGER_LIST;
      case "FLOAT", "NUMERIC", "DOUBLE", "REAL" -> ft = FieldType.REAL;
      case "FLOAT[]", "NUMERIC[]", "DOUBLE[]", "REAL[]" -> ft = FieldType.REAL_LIST;
      case "CHARACTER", "TEXT", "STRING", "VARCHAR" -> ft = FieldType.STRING;
      case "TEXT[]", "STRING[]", "VARCHAR[]" -> ft = FieldType.STRING_LIST;
      case "DATE" -> ft = FieldType.DATE;
      case "TIME" -> ft = FieldType.TIME;
      case "TIMESTAMP", "DATETIME" -> ft = FieldType.DATETIME;
      default -> {
        dev.jdal.core.Diagnostics.warn(
            "Unsupported column type '" + type + "'. Defaulting to VARCHAR");
        ft = FieldType.STRING;
      }
    }
    return FieldDefn.of(name, ft, FieldSubType.NONE).withWidth(width, precision);
  }
}
