package dev.jdal.sql;

import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldValues;
import dev.jdal.geom.Geometry;
import dev.jdal.sql.Bound.BColumn;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Conversions between feature field values and SQL engine values. */
final class Values {

  private Values() {}

  /**
   * The value of {@code c} in feature {@code f} of type {@code t}; {@code f} may be null (no join
   * partner).
   */
  static Val fetch(Feature f, FeatureType t, BColumn c) {
    SqlType type = c.type();
    switch (c.ref().kind()) {
      case FIELD -> {
        FieldDefn d = t.fields().get(c.ref().index());
        Object v = f == null ? null : f.get(c.ref().index());
        return switch (type) {
          case INTEGER, INTEGER64, BOOLEAN ->
              v == null
                  ? new Val(type, 0, 0, null, null, true)
                  : Val.ofInt(type, ((Number) v).longValue());
          case FLOAT ->
              v == null
                  ? new Val(type, 0, 0, null, null, true)
                  : Val.real(((Number) v).doubleValue());
          case DATE, TIME, TIMESTAMP ->
              new Val(type, 0, 0, v == null ? "" : FieldValues.format(d, v), null, v == null);
          default ->
              new Val(
                  type == SqlType.OTHER ? SqlType.OTHER : SqlType.STRING,
                  0,
                  0,
                  v == null ? "" : v instanceof String s ? s : FieldValues.format(d, v),
                  null,
                  v == null);
        };
      }
      case GEOM -> {
        return Val.geometry(f == null ? null : f.geometry(c.ref().index()));
      }
      case FID -> {
        return f == null ? new Val(type, 0, 0, null, null, true) : Val.ofInt(type, f.fid());
      }
      case OGR_GEOMETRY -> {
        Geometry g = f == null || f.geometries().isEmpty() ? null : f.geometry();
        return Val.string(
                g == null ? "" : g.type().displayName().toUpperCase(java.util.Locale.ROOT))
            .withNullFlag(false);
      }
      case OGR_STYLE -> {
        return Val.string("").withNullFlag(false);
      }
      case OGR_GEOM_WKT -> {
        Geometry g = f == null || f.geometries().isEmpty() ? null : f.geometry();
        return Val.string(g == null ? "" : g.toWkt()).withNullFlag(false);
      }
      default -> {
        Geometry g = f == null || f.geometries().isEmpty() ? null : f.geometry();
        return Val.real(g == null ? 0 : Eval.area(g));
      }
    }
  }

  // ------------------------------------------------------------------ assignment
  // (OGRFeature::SetField semantics)

  /**
   * Converts {@code v} (Long, Double, String or a field value) into the representation of {@code
   * dst}; null stays null.
   */
  static Object assign(FieldDefn dst, Object v) {
    if (v == null) return null;
    try {
      return switch (dst.type()) {
        case INTEGER ->
            clampInt(
                v instanceof Number n
                    ? (n instanceof Double d ? (long) (double) d : n.longValue())
                    : CNum.atoll(v.toString()));
        case INTEGER64 ->
            v instanceof Number n
                ? (n instanceof Double d ? (long) (double) d : n.longValue())
                : CNum.atoll(v.toString());
        case REAL -> v instanceof Number n ? n.doubleValue() : CNum.atof(v.toString());
        case STRING -> v instanceof Double d ? dev.jdal.geom.Wkt.number(d) : v.toString();
        case DATE ->
            v instanceof LocalDate
                ? v
                : v instanceof LocalDateTime t
                    ? t.toLocalDate()
                    : FieldValues.parseDate(v.toString());
        case TIME ->
            v instanceof LocalTime
                ? v
                : v instanceof LocalDateTime t
                    ? t.toLocalTime()
                    : FieldValues.parseTime(v.toString());
        case DATETIME ->
            v instanceof LocalDateTime || v instanceof OffsetDateTime
                ? v
                : v instanceof LocalDate d
                    ? d.atStartOfDay()
                    : FieldValues.parseDateTime(v.toString());
        default -> v;
      };
    } catch (DateTimeParseException | IllegalArgumentException e) {
      return null;
    }
  }

  private static Integer clampInt(long v) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  /** A value copied from a field of type {@code src} into a field of type {@code dst}. */
  static Object convert(Object raw, FieldDefn src, FieldDefn dst) {
    if (raw == null) return null;
    if (src.type() == dst.type()) return raw;
    if (raw instanceof Number n && (dst.type().isNumeric()))
      return assign(dst, n instanceof Double ? n : (Object) n.longValue());
    return assign(dst, FieldValues.format(src, raw));
  }
}
