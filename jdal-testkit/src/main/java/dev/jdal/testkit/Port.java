package dev.jdal.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Traces a test back to the GDAL test it was ported from, e.g. {@code
 * autotest/ogr/ogr_geom.py::test_ogr_geom_area}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Port {
  String[] value();
}
