package dev.jdal.driver.postgis;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import org.junit.jupiter.api.Test;

/** Tests that need no database server. */
class PgUnitTest {

  @Test
  void parsesGdalStyleConnectionString() {
    PgConnection c =
        PgConnection.parse(
            "PG:host=db.example port=5433 dbname=gis user=me password='se cret' active_schema=geo");
    assertEquals("jdbc:postgresql://db.example:5433/gis", c.jdbcUrl());
    assertEquals("me", c.properties().getProperty("user"));
    assertEquals("se cret", c.properties().getProperty("password"));
    assertEquals("geo", c.schema());
  }

  @Test
  void parsesUrlForms() {
    PgConnection c = PgConnection.parse("postgresql://u:p%40ss@localhost:5432/db?sslmode=disable");
    assertEquals("jdbc:postgresql://localhost:5432/db?sslmode=disable", c.jdbcUrl());
    assertEquals("p@ss", c.properties().getProperty("password"));
    assertTrue(PgConnection.isPgLocation("PG:dbname=x"));
    assertTrue(PgConnection.isPgLocation("pg:dbname=x"));
    assertFalse(PgConnection.isPgLocation("/data/x.gpkg"));
    assertEquals("jdbc:postgresql://h/db", PgConnection.parse("PG:host=h dbname=db").jdbcUrl());
  }

  @Test
  void mapsTypes() {
    assertEquals(FieldSubType.BOOLEAN, PgTypes.fieldFor("a", "bool", 0, 0, 0).subType());
    assertEquals(FieldType.INTEGER64, PgTypes.fieldFor("a", "int8", 0, 0, 0).type());
    assertEquals(FieldType.INTEGER_LIST, PgTypes.fieldFor("a", "_int4", 0, 0, 0).type());
    assertEquals(20, PgTypes.fieldFor("a", "varchar", 20, 0, 0).width());
    assertEquals(FieldSubType.JSON, PgTypes.fieldFor("a", "jsonb", 0, 0, 0).subType());
    assertEquals(
        "boolean", PgTypes.sqlType(FieldDefn.of("a", FieldType.INTEGER, FieldSubType.BOOLEAN)));
    assertEquals(
        "varchar(5)", PgTypes.sqlType(FieldDefn.of("a", FieldType.STRING).withWidth(5, 0)));
    assertEquals("PointZ", PgTypes.geometryTypeClause(GeometryType.POINT, Dimension.XYZ));
    assertEquals("GeometryZM", PgTypes.geometryTypeClause(GeometryType.UNKNOWN, Dimension.XYZM));
    assertEquals(GeometryType.MULTIPOLYGON, PgTypes.geometryType("MULTIPOLYGONZ"));
    assertEquals(Dimension.XYM, PgTypes.dimension(3, "POINTM"));
    assertEquals(Dimension.XYZ, PgTypes.dimension(3, "POINT"));
    assertEquals("my_layer_1", PgLayer.launder("My Layer-1"));
    assertEquals("_1abc", PgLayer.launder("1abc"));
  }
}
