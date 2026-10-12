package dev.jdal.utils;

import dev.jdal.core.Query;
import dev.jdal.feature.Feature;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Options of {@link Translate} (the library behind {@code ogr2ogr}). Everything is optional.
 *
 * @param driver output driver name; guessed from the file extension if null
 * @param layers source layers to copy; all if empty
 * @param newLayerName name of the output layer (only meaningful when a single layer is copied)
 * @param sourceSrs overrides the source layers' spatial reference
 * @param targetSrs reproject to this spatial reference
 * @param bbox only copy features intersecting this box (in source coordinates)
 * @param spatial only copy features in this relation to a geometry (in source coordinates)
 * @param where additional attribute filter
 * @param forceGeometryType declared geometry type of the output layers, null to keep
 * @param limit maximum number of features per layer, -1 for all
 * @param layerOptions layer creation options
 * @param datasetOptions dataset creation options
 * @param openOptions open options for the source
 */
public record TranslateOptions(
    String driver,
    List<String> layers,
    String newLayerName,
    SpatialReference sourceSrs,
    SpatialReference targetSrs,
    Envelope bbox,
    dev.jdal.core.SpatialFilter spatial,
    Predicate<Feature> where,
    GeometryType forceGeometryType,
    long limit,
    Map<String, String> layerOptions,
    Map<String, String> datasetOptions,
    Map<String, String> openOptions) {

  public TranslateOptions {
    layers = layers == null ? List.of() : List.copyOf(layers);
    layerOptions = layerOptions == null ? Map.of() : Map.copyOf(layerOptions);
    datasetOptions = datasetOptions == null ? Map.of() : Map.copyOf(datasetOptions);
    openOptions = openOptions == null ? Map.of() : Map.copyOf(openOptions);
  }

  public static TranslateOptions defaults() {
    return new TranslateOptions(
        null, List.of(), null, null, null, null, null, null, null, -1, Map.of(), Map.of(),
        Map.of());
  }

  public TranslateOptions withDriver(String d) {
    return new TranslateOptions(
        d,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withLayers(List<String> l) {
    return new TranslateOptions(
        driver,
        l,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withNewLayerName(String n) {
    return new TranslateOptions(
        driver,
        layers,
        n,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withSourceSrs(SpatialReference s) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        s,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withTargetSrs(SpatialReference s) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        s,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withSpatialFilter(dev.jdal.core.SpatialFilter f) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        f,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withBbox(Envelope b) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        b,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withWhere(Predicate<Feature> w) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        w,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withForceGeometryType(GeometryType t) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        t,
        limit,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withLimit(long l) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        l,
        layerOptions,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withLayerOptions(Map<String, String> o) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        o,
        datasetOptions,
        openOptions);
  }

  public TranslateOptions withDatasetOptions(Map<String, String> o) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        o,
        openOptions);
  }

  public TranslateOptions withOpenOptions(Map<String, String> o) {
    return new TranslateOptions(
        driver,
        layers,
        newLayerName,
        sourceSrs,
        targetSrs,
        bbox,
        spatial,
        where,
        forceGeometryType,
        limit,
        layerOptions,
        datasetOptions,
        o);
  }

  Query query() {
    return new Query(bbox, where, limit).withSpatial(spatial);
  }
}
