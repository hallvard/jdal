package dev.jdal.ops;

import dev.jdal.core.GeometryOps;
import dev.jdal.core.JdalException;
import dev.jdal.core.SpatialOp;
import dev.jdal.geom.Geometry;
import java.util.function.Predicate;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/**
 * {@link GeometryOps} on JTS. The filter geometry is prepared, which makes many tests against it
 * cheap.
 */
public final class JtsGeometryOps implements GeometryOps {

  @Override
  public Predicate<Geometry> prepare(SpatialOp op, Geometry filter) {
    org.locationtech.jts.geom.Geometry f = Jts.toJts(filter);
    PreparedGeometry prepared = PreparedGeometryFactory.prepare(f);
    return g -> {
      if (g == null || g.isEmpty()) return false;
      try {
        org.locationtech.jts.geom.Geometry x = Jts.toJts(g);
        return switch (op) {
          case INTERSECTS -> prepared.intersects(x);
          case WITHIN -> prepared.contains(x); // the feature lies within the filter
          case CONTAINS -> prepared.within(x); // the filter lies within the feature
          case DISJOINT -> prepared.disjoint(x);
          case TOUCHES -> prepared.touches(x);
          case CROSSES ->
              x.crosses(f); // not symmetric for lines and areas: the feature is the subject
          case OVERLAPS -> prepared.overlaps(x);
          case EQUALS -> x.equalsTopo(f);
        };
      } catch (RuntimeException e) {
        throw new JdalException("Cannot test " + op + " on this geometry: " + e.getMessage(), e);
      }
    };
  }
}
