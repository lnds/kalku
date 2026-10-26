package fx;

public final class Registry {
  private Registry() {}

  // Found by its name, when this class is first used.
  private static final int SIZE = sized("fx.Shelf");

  private static int sized(String name) {
    try {
      return (Integer) Class.forName(name).getMethod("size").invoke(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int size() {
    return SIZE;
  }
}
