//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69

import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Map;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

class HarrietHello {
  public static void main(String... args) {
    String ticket = "My invoice was charged twice this month and nobody answers the phone.";
    long t0 = System.nanoTime();
    try (ModelJarDecisionRuntime runtime = Harriet.open()) {
      long t1 = System.nanoTime();
      System.out.printf("open: %.1f s%n", (t1 - t0) / 1e9);
      Map<String, Verdict> answers = Harriet.systemOne(runtime, Map.of(
          "urgent", new Noul("Is this ticket urgent?"),
          "department", new Choice("Which team should handle this ticket?",
              List.of("billing", "technical", "sales")),
          "frustration", new Score("How frustrated is the customer?",
              List.of("calm", "frustrated", "very angry"))),
          ticket);
      long t2 = System.nanoTime();
      System.out.printf("three questions: %.1f s%n", (t2 - t1) / 1e9);
      System.out.printf("urgent      p(true) = %.3f%n", answers.get("urgent").probabilityOfTrue());
      System.out.printf("department  winner  = %s  %s%n", answers.get("department").winner(), answers.get("department"));
      System.out.printf("frustration winner  = %s  %s%n", answers.get("frustration").winner(), answers.get("frustration"));
    }
  }
}
