package dev.jdal.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolygonOrganizerTest {

  static CoordinateSequence cw(double x0, double y0, double x1, double y1) {
    return CoordinateSequence.xy(x0, y0, x0, y1, x1, y1, x1, y0, x0, y0);
  }

  static CoordinateSequence ccw(double x0, double y0, double x1, double y1) {
    return CoordinateSequence.xy(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0);
  }

  @Test
  void holesGoToTheSmallestContainingShell() {
    List<Polygon> ps =
        PolygonOrganizer.organize(
            List.of(
                cw(0, 0, 100, 100),
                ccw(10, 10, 20, 20),
                cw(30, 30, 60, 60),
                ccw(40, 40, 50, 50),
                cw(200, 200, 210, 210)));
    assertEquals(3, ps.size());
    assertEquals(2, ps.get(0).rings().size()); // outer with one hole
    assertEquals(ccw(10, 10, 20, 20), ps.get(0).rings().get(1));
    assertEquals(
        2, ps.get(1).rings().size()); // island in the outer ring's... its own hole at 40..50
    assertEquals(ccw(40, 40, 50, 50), ps.get(1).rings().get(1));
    assertEquals(1, ps.get(2).rings().size());
  }

  @Test
  void holeOutsideEveryShellBecomesAShell() {
    List<Polygon> ps = PolygonOrganizer.organize(List.of(cw(0, 0, 10, 10), ccw(50, 50, 60, 60)));
    assertEquals(2, ps.size());
  }

  @Test
  void withoutClockwiseRingsNestingDecides() {
    List<Polygon> ps =
        PolygonOrganizer.organize(
            List.of(ccw(0, 0, 100, 100), ccw(10, 10, 20, 20), ccw(200, 0, 300, 100)));
    assertEquals(2, ps.size());
    assertEquals(2, ps.get(0).rings().size());
  }
}
