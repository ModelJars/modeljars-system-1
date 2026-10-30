//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69
import org.modeljars.composite.harriet.Harriet;
class HarrietDiag {
  public static void main(String... a) {
    try (var h = Harriet.open()) {
      System.out.println("backend.name=" + h.backend().name());
      var d = h.backend().diagnostics();
      System.out.println("diag.backend=" + d.backend() + " plan=" + d.planVersion());
      d.environment().forEach((k, v) -> System.out.println("env " + k + "=" + v));
      d.optimizations().forEach(o -> System.out.println("opt " + o));
    }
  }
}
