package dev.jdal.geom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Groups loose rings (as found in shapefiles and some other formats) into polygons with holes, the
 * job of GDAL's {@code OGRGeometryFactory::organizePolygons}. Clockwise rings are shells and
 * counter-clockwise rings are holes; each hole goes to the smallest shell containing it, and a hole
 * outside every shell becomes a shell. If no ring is clockwise, nesting depth decides: rings inside
 * an odd number of other rings are holes.
 */
public final class PolygonOrganizer {

  private PolygonOrganizer() {}

  /** Signed area (shoelace); positive for counter-clockwise rings. */
  public static double signedArea(CoordinateSequence ring) {
    double sum = 0;
    int n = ring.size();
    for (int i = 0; i < n - 1; i++) sum += ring.x(i) * ring.y(i + 1) - ring.x(i + 1) * ring.y(i);
    return sum / 2;
  }

  /** True if the point is inside the ring (even-odd rule, boundary counts as outside). */
  public static boolean contains(CoordinateSequence ring, double x, double y) {
    boolean inside = false;
    int n = ring.size();
    for (int i = 0, j = n - 1; i < n; j = i++) {
      double xi = ring.x(i), yi = ring.y(i), xj = ring.x(j), yj = ring.y(j);
      if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }

  /**
   * A ring is contained in another if the other contains its first vertex that is not on a shared
   * position.
   */
  private static boolean ringInside(CoordinateSequence inner, CoordinateSequence outer) {
    if (!outer.envelope().intersects(inner.envelope())) return false;
    int votesIn = 0, votesOut = 0;
    int n = Math.min(inner.size(), 8);
    for (int k = 0; k < n; k++) {
      int i = k * (inner.size() - 1) / Math.max(n - 1, 1);
      if (contains(outer, inner.x(i), inner.y(i))) votesIn++;
      else votesOut++;
    }
    return votesIn > votesOut;
  }

  /** Organizes {@code rings} (all of the same dimension) into polygons, keeping shell order. */
  public static List<Polygon> organize(List<CoordinateSequence> rings) {
    if (rings.isEmpty()) return List.of();
    Dimension dim = rings.getFirst().dimension();
    int n = rings.size();
    boolean[] shell = new boolean[n];
    boolean anyClockwise = false;
    for (int i = 0; i < n; i++) {
      shell[i] = signedArea(rings.get(i)) <= 0 && rings.get(i).size() >= 4;
      anyClockwise |= shell[i];
    }
    if (!anyClockwise && n > 1) {
      for (int i = 0; i < n; i++) {
        int depth = 0;
        for (int j = 0; j < n; j++) if (i != j && ringInside(rings.get(i), rings.get(j))) depth++;
        shell[i] = depth % 2 == 0;
      }
    } else if (!anyClockwise) {
      shell[0] = true;
    }
    List<List<CoordinateSequence>> polygons = new ArrayList<>();
    List<Integer> shellIndex = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (shell[i]) {
        shellIndex.add(i);
        List<CoordinateSequence> p = new ArrayList<>();
        p.add(rings.get(i));
        polygons.add(p);
      }
    }
    List<Integer> shellsBySize =
        new ArrayList<>(java.util.stream.IntStream.range(0, shellIndex.size()).boxed().toList());
    shellsBySize.sort(
        Comparator.comparingDouble(k -> Math.abs(signedArea(rings.get(shellIndex.get(k))))));
    for (int i = 0; i < n; i++) {
      if (shell[i]) continue;
      CoordinateSequence hole = rings.get(i);
      Integer owner = null;
      for (int k : shellsBySize) {
        if (ringInside(hole, rings.get(shellIndex.get(k)))) {
          owner = k;
          break;
        }
      }
      if (owner != null) {
        polygons.get(owner).add(hole);
      } else {
        polygons.add(
            new ArrayList<>(List.of(hole))); // a hole outside every shell is a shell of its own
      }
    }
    List<Polygon> out = new ArrayList<>(polygons.size());
    for (List<CoordinateSequence> p : polygons) out.add(new Polygon(dim, p));
    return out;
  }
}
