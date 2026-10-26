package fx;

import com.google.auto.service.AutoService;

@AutoService(Greeter.class)
public class Door implements Greeter {
  @Override
  public boolean welcomes(int age) {
    return age >= 18;
  }
}
