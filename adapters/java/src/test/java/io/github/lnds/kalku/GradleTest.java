package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The order a Gradle build's modules are compiled in. */
class GradleTest {
  private static Maven.Build module(String name, Maven.Build... uses) {
    Maven.Build build = new Maven.Build();
    build.name = name;
    build.uses.addAll(Arrays.asList(uses));
    return build;
  }

  private static List<String> names(List<Maven.Build> modules) {
    return modules.stream().map(m -> m.name).collect(Collectors.toList());
  }

  @Test
  void aModuleComesAfterOneItUsesWithAnUnrelatedOneBetween() {
    // Compared in pairs, each neighbour looks unrelated to the next, and a sort keeps A first.
    Maven.Build b = module("b");
    Maven.Build a = module("a", b);
    Maven.Build c = module("c");
    assertEquals(List.of("c", "b", "a"), names(Gradle.ordered(List.of(a, c, b))));
  }

  @Test
  void aChainIsOrderedAndTheRestKeepTheirPlaces() {
    Maven.Build x = module("x");
    Maven.Build c = module("c");
    Maven.Build b = module("b", c);
    Maven.Build a = module("a", b, c);
    Maven.Build y = module("y");
    List<Maven.Build> given = new ArrayList<>(List.of(x, a, y, b, c));
    assertEquals(List.of("x", "y", "c", "b", "a"), names(Gradle.ordered(given)));
    assertEquals(List.of("x", "a", "y", "b", "c"), names(given));
  }

  @Test
  void aCycleEndsWithEveryModuleInIt() {
    Maven.Build a = module("a");
    Maven.Build b = module("b", a);
    a.uses.add(b);
    Maven.Build c = module("c");
    assertEquals(List.of("c", "a", "b"), names(Gradle.ordered(List.of(a, b, c))));
  }
}
