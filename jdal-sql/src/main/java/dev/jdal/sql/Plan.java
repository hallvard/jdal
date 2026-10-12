package dev.jdal.sql;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.sql.Ast.*;
import dev.jdal.sql.Bound.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A SELECT after name resolution: tables, output columns, query mode, filters, joins and ordering.
 */
final class Plan {

  enum Mode {
    RECORDSET,
    SUMMARY,
    DISTINCT
  }

  /**
   * One output column.
   *
   * @param fieldName name of the referenced field, "" for general expressions
   * @param aggregate the summary function or null
   * @param expr the bound expression for general columns (null for plain fields and aggregates)
   * @param ref the field of plain columns and aggregates (null for COUNT(*) and expressions)
   * @param fieldType the SQL type of the column
   * @param cast the CAST at the root of an expression column, or null
   */
  record Column(
      String tableName,
      String fieldName,
      String alias,
      boolean hidden,
      boolean distinct,
      SqlOp aggregate,
      Bound expr,
      int table,
      FieldRef ref,
      SqlType fieldType,
      BCast cast) {

    boolean isPlainField() {
      return aggregate == null && expr == null;
    }
  }

  record JoinDef(int table, Bound on) {}

  record OrderKey(int table, FieldRef ref, SqlType type, boolean ascending, String name) {}

  final Dataset dataset;
  final List<FieldList.Table> tables = new ArrayList<>();
  final List<Dataset> extraDatasets = new ArrayList<>();
  final FieldList fields = new FieldList(true);
  final List<Column> columns = new ArrayList<>();
  Mode mode;
  Bound where;
  final List<JoinDef> joins = new ArrayList<>();
  final List<OrderKey> order = new ArrayList<>();
  long limit;
  long offset;
  boolean excludedGeometry;

  private Plan(Dataset dataset) {
    this.dataset = dataset;
  }

  // ------------------------------------------------------------------ building

  static Plan build(Dataset ds, Select sel) {
    Plan p = new Plan(ds);
    p.limit = sel.limit();
    p.offset = sel.offset();
    p.openTables(sel);
    p.buildFieldList();
    List<SelectColumn> cols = p.expandWildcards(sel);
    p.fields.addSpecial(0, SqlType.INTEGER);
    p.bindColumns(cols);
    p.determineMode(sel.distinct());
    p.bindJoins(sel);
    p.bindOrder(sel);
    if (sel.where() != null) p.where = new Binder(p.fields, false, false).bind(sel.where());
    return p;
  }

  private void openTables(Select sel) {
    for (TableRef t : sel.tables()) {
      Dataset d = dataset;
      if (t.dataSource() != null) {
        try {
          d = Jdal.open(t.dataSource());
        } catch (JdalException e) {
          throw new JdalException(
              "Unable to open secondary datasource `" + t.dataSource() + "' required by JOIN.", e);
        }
        extraDatasets.add(d);
      }
      Layer l =
          d.layer(t.name())
              .orElseThrow(
                  () ->
                      new JdalException(
                          "SELECT from table "
                              + t.name()
                              + " failed, no such table/featureclass."));
      tables.add(new FieldList.Table(t.dataSource(), t.name(), t.alias(), l, l.type()));
    }
    fields.tables.addAll(tables);
  }

  private void buildFieldList() {
    for (int t = 0; t < tables.size(); t++) {
      var type = tables.get(t).type();
      for (int i = 0; i < type.fields().size(); i++) {
        fields.entries.add(
            new FieldList.Entry(
                type.fields().get(i).name(),
                FieldList.typeOf(type.fields().get(i)),
                t,
                new FieldRef(FieldRef.Kind.FIELD, i)));
      }
      if (t == 0) {
        for (int g = 0; g < type.geomFields().size(); g++) {
          String n = type.geomFields().get(g).name();
          fields.entries.add(
              new FieldList.Entry(
                  n.isEmpty() ? FieldList.DEFAULT_GEOMETRY_NAME : n,
                  SqlType.GEOMETRY,
                  0,
                  new FieldRef(FieldRef.Kind.GEOM, g)));
        }
      }
    }
  }

  private List<SelectColumn> expandWildcards(Select sel) {
    List<SelectColumn> out = new ArrayList<>();
    int entryCount = fields.entries.size();
    for (int idx = 0; idx < sel.columns().size(); idx++) {
      SelectColumn sc = sel.columns().get(idx);
      if (!(sc.expr() instanceof Col c) || !c.name().equals("*")) {
        out.add(sc);
        continue;
      }
      int itable = -1;
      if (c.table() != null) {
        for (int t = 0; t < tables.size(); t++)
          if (tables.get(t).alias().equalsIgnoreCase(c.table())) itable = t;
        if (itable < 0) {
          throw new JdalException(
              "Table " + c.table() + " not recognised from " + c.table() + ".* definition.");
        }
      }
      List<Col> excl = new ArrayList<>(sel.excludes().getOrDefault(idx, List.of()));
      for (int i = 0; i < entryCount; i++) {
        FieldList.Entry e = fields.entries.get(i);
        if (itable != -1 && e.table() != itable) continue;
        if (isExcluded(excl, tables.get(e.table()).name(), e.name())) {
          if (e.type() == SqlType.GEOMETRY) excludedGeometry = true;
          continue;
        }
        boolean compose = itable != -1;
        if (e.table() != 0 && !compose) {
          for (int other = 0; other < i; other++) {
            if (fields.entries.get(other).name().equalsIgnoreCase(e.name())) {
              compose = true;
              break;
            }
          }
        }
        out.add(
            new SelectColumn(
                new Col(tables.get(e.table()).alias(), e.name()),
                compose ? null : e.name(),
                false,
                false));
      }
      if (!excl.isEmpty()) {
        throw new JdalException(
            "Field "
                + excl.getFirst().name()
                + " specified in EXCEPT/EXCLUDE expression not found");
      }
    }
    return out;
  }

  private static boolean isExcluded(List<Col> excl, String tableName, String fieldName) {
    for (int i = 0; i < excl.size(); i++) {
      Col x = excl.get(i);
      if ((x.table() == null || x.table().isEmpty() || tableName.equalsIgnoreCase(x.table()))
          && x.name().equalsIgnoreCase(fieldName)) {
        excl.remove(i);
        return true;
      }
    }
    return false;
  }

  private void bindColumns(List<SelectColumn> cols) {
    Binder binder = new Binder(fields, true, false);
    for (SelectColumn sc : cols) {
      Expr e = sc.expr();
      String tableName = "", fieldName = "";
      String alias = sc.alias();
      Column col;
      if (e instanceof Col c) {
        tableName = c.table() == null ? "" : c.table();
        fieldName = c.name();
        col = plainColumn(tableName, fieldName, alias, sc, null);
      } else if (e instanceof Op o && o.op().isAggregate()) {
        if (o.args().size() != 1) {
          throw new JdalException(
              "Column Summary Function '" + o.op().sqlName + "' has wrong number of arguments.");
        }
        if (!(o.args().getFirst() instanceof Col c)) {
          throw new JdalException(
              "Argument of column Summary Function '" + o.op().sqlName + "' should be a column.");
        }
        tableName = c.table() == null ? "" : c.table();
        fieldName = c.name();
        col = plainColumn(tableName, fieldName, alias, sc, o.op());
      } else {
        BCast cast = null;
        if (e instanceof Cast c && c.arg() instanceof Col ac) {
          tableName = ac.table() == null ? "" : ac.table();
          fieldName = ac.name();
        }
        if (alias == null
            && e instanceof Op o
            && (o.op() == SqlOp.CONCAT || o.op() == SqlOp.SUBSTR)
            && o.args().getFirst() instanceof Col ac) {
          alias = o.op().sqlName + "_" + ac.name();
        }
        Bound b = binder.bind(e);
        if (b instanceof BCast bc) cast = bc;
        col =
            new Column(
                tableName,
                fieldName,
                alias,
                sc.hidden(),
                sc.distinct(),
                null,
                b,
                -1,
                null,
                b.type(),
                cast);
      }
      if (sc.hidden()) {
        String dst = col.alias() != null ? col.alias() : col.fieldName();
        if (!dst.equalsIgnoreCase("OGR_STYLE")) {
          throw new JdalException("HIDDEN keyword only supported on a column named OGR_STYLE");
        }
      }
      columns.add(col);
    }
  }

  private Column plainColumn(
      String tableName, String fieldName, String alias, SelectColumn sc, SqlOp aggregate) {
    boolean countStar = aggregate == SqlOp.COUNT && fieldName.equals("*");
    int idx = countStar ? -1 : fields.identify(tableName.isEmpty() ? null : tableName, fieldName);
    if (idx < 0 && !countStar) {
      throw new JdalException(
          "Unrecognized field name "
              + (tableName.isEmpty() ? fieldName : tableName + "." + fieldName)
              + ".");
    }
    SqlType type = countStar ? SqlType.OTHER : fields.entries.get(idx).type();
    FieldRef ref = countStar ? null : fields.entries.get(idx).ref();
    int table = countStar ? -1 : fields.entries.get(idx).table();
    if (aggregate != null
        && aggregate != SqlOp.COUNT
        && (type == SqlType.GEOMETRY
            || (type == SqlType.STRING && aggregate != SqlOp.MIN && aggregate != SqlOp.MAX))) {
      throw new JdalException(
          "Use of field function "
              + aggregate.sqlName
              + "() on "
              + type.displayName()
              + " field "
              + fieldName
              + " illegal.");
    }
    return new Column(
        tableName,
        fieldName,
        alias,
        sc.hidden(),
        sc.distinct(),
        aggregate,
        null,
        table,
        ref,
        type,
        null);
  }

  private void determineMode(boolean selectDistinct) {
    Mode m = selectDistinct ? Mode.DISTINCT : null;
    if (m == Mode.DISTINCT && columns.size() > 1)
      throw new JdalException("SELECT DISTINCT not supported on multiple columns.");
    for (int i = 0; i < columns.size(); i++) {
      Column c = columns.get(i);
      Mode indicator = null;
      if (m == Mode.DISTINCT && c.fieldType() == SqlType.GEOMETRY) {
        throw new JdalException("SELECT DISTINCT on a geometry not supported.");
      }
      if (c.aggregate() == null) {
        if (m == Mode.DISTINCT) {
          columns.set(
              i,
              new Column(
                  c.tableName(),
                  c.fieldName(),
                  c.alias(),
                  c.hidden(),
                  true,
                  null,
                  c.expr(),
                  c.table(),
                  c.ref(),
                  c.fieldType(),
                  c.cast()));
          indicator = Mode.DISTINCT;
        } else {
          indicator = Mode.RECORDSET;
        }
      } else {
        indicator = Mode.SUMMARY;
        if (c.aggregate() == SqlOp.COUNT && c.distinct() && c.fieldType() == SqlType.GEOMETRY) {
          throw new JdalException("SELECT COUNT DISTINCT on a geometry not supported.");
        }
      }
      if (indicator != m && indicator != null && m != null) {
        throw new JdalException(
            "Field list implies mixture of regular recordset mode, summary mode or distinct field"
                + " list mode.");
      }
      if (indicator != null) m = indicator;
    }
    mode = columns.isEmpty() || m == null ? Mode.RECORDSET : m;
  }

  private void bindJoins(Select sel) {
    Binder binder = new Binder(fields, true, true);
    for (Join j : sel.joins()) {
      Bound on = binder.bind(j.on());
      checkJoinExpr(on, j.secondaryTable());
      joins.add(new JoinDef(j.secondaryTable(), on));
    }
  }

  private static void checkJoinExpr(Bound b, int secondary) {
    switch (b) {
      case BConst c -> {}
      case BColumn c -> {
        if (c.table() != 0 && c.table() != secondary) {
          throw new JdalException(
              "Field "
                  + c.name()
                  + " in JOIN clause does not correspond to the primary table nor the joint"
                  + " (secondary) table.");
        }
      }
      case BOp o -> o.args().forEach(a -> checkJoinExpr(a, secondary));
      case BCast c -> checkJoinExpr(c.arg(), secondary);
    }
  }

  private void bindOrder(Select sel) {
    for (OrderBy o : sel.orderBy()) {
      int idx = fields.identify(o.table(), o.field());
      String qualified = o.table() == null ? o.field() : o.table() + "." + o.field();
      if (idx < 0)
        throw new JdalException("Unrecognized field name " + qualified + " in ORDER BY.");
      FieldList.Entry e = fields.entries.get(idx);
      if (e.table() != 0) {
        throw new JdalException(
            "Cannot use field '" + o.field() + "' of a secondary table in an ORDER BY clause");
      }
      if (e.type() == SqlType.GEOMETRY) {
        throw new JdalException(
            "Cannot use geometry field '" + o.field() + "' in an ORDER BY clause");
      }
      order.add(new OrderKey(e.table(), e.ref(), e.type(), o.ascending(), e.name()));
    }
  }

  // ------------------------------------------------------------------ result field definitions

  /** Type and subtype requested by a CAST at the root of a column, or null. */
  static FieldType castFieldType(BCast c) {
    return switch (c.typeName().toLowerCase(Locale.ROOT)) {
      case "integer", "smallint", "boolean" -> FieldType.INTEGER;
      case "bigint" -> FieldType.INTEGER64;
      case "float", "numeric" -> FieldType.REAL;
      case "character" -> FieldType.STRING;
      case "timestamp" -> FieldType.DATETIME;
      case "date" -> FieldType.DATE;
      case "time" -> FieldType.TIME;
      default -> null;
    };
  }

  FieldDefn fieldDefFor(Column c, String name) {
    FieldDefn src = null;
    if (c.ref() != null && c.ref().kind() == FieldRef.Kind.FIELD)
      src = tables.get(c.table()).type().fields().get(c.ref().index());
    FieldType type = FieldType.INTEGER;
    FieldSubType sub = FieldSubType.NONE;
    int width = 0, precision = 0;
    SqlOp agg = c.aggregate();
    if (agg == SqlOp.COUNT) {
      type = FieldType.INTEGER64;
    } else if (src != null) {
      if (agg == SqlOp.STDDEV_POP || agg == SqlOp.STDDEV_SAMP) {
        type = FieldType.REAL;
      } else if (agg != SqlOp.AVG || c.fieldType().isDateLike()) {
        type = src.type();
        if (agg == null || agg == SqlOp.MIN || agg == SqlOp.MAX) sub = src.subType();
      } else {
        type = FieldType.REAL;
      }
      if (agg != SqlOp.AVG
          && agg != SqlOp.STDDEV_POP
          && agg != SqlOp.STDDEV_SAMP
          && agg != SqlOp.SUM) {
        width = src.width();
        precision = src.precision();
      }
    } else if (c.ref() != null && c.ref().kind() != FieldRef.Kind.GEOM) {
      type =
          switch (c.ref().kind()) {
            case FID -> FieldType.INTEGER;
            case OGR_GEOM_AREA -> FieldType.REAL;
            default -> FieldType.STRING;
          };
    } else {
      type =
          switch (c.fieldType()) {
            case INTEGER -> FieldType.INTEGER;
            case INTEGER64 -> FieldType.INTEGER64;
            case BOOLEAN -> {
              sub = FieldSubType.BOOLEAN;
              yield FieldType.INTEGER;
            }
            case FLOAT -> FieldType.REAL;
            default -> FieldType.STRING;
          };
    }
    if (c.cast() != null) {
      BCast bc = c.cast();
      FieldType t = castFieldType(bc);
      String tn = bc.typeName().toLowerCase(Locale.ROOT);
      if (t != null) {
        type = t;
        sub =
            tn.equals("boolean")
                ? FieldSubType.BOOLEAN
                : tn.equals("smallint") ? FieldSubType.INT16 : sub;
        if (tn.equals("boolean")) sub = FieldSubType.BOOLEAN;
      }
      if (tn.equals("character")) width = bc.width() >= 0 ? bc.width() : 1;
      else if (bc.width() > 0) width = bc.width();
      if (bc.precision() >= 0) {
        precision = bc.precision();
        if (tn.equals("numeric") && precision == 0) {
          if (width < 10) type = FieldType.INTEGER;
          else if (width < 19) type = FieldType.INTEGER64;
        }
      }
    }
    return new FieldDefn(name, type, sub, width, precision, true, false, null, null, null);
  }
}
