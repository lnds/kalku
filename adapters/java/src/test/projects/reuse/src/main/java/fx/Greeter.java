package fx;

public final class Greeter implements Named {
  @Override
  public String greet(String name) {
    return Words.HELLO + ", " + name;
  }
}
