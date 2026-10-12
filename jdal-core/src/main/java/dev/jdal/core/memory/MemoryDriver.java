package dev.jdal.core.memory;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import java.util.Map;

/** The "Memory" driver. It cannot open anything, only create fresh datasets. */
public final class MemoryDriver implements Driver {

  public static final MemoryDriver INSTANCE = new MemoryDriver();

  @Override
  public String name() {
    return "Memory";
  }

  @Override
  public String description() {
    return "In-memory datasets";
  }

  @Override
  public boolean identify(OpenInfo info) {
    return false;
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    throw new JdalException("The Memory driver cannot open existing datasets");
  }

  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    return new MemoryDataset(location);
  }
}
