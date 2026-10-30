//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69

import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

class HarrietDemoTimed {
  static final long JVM_START = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();

  public static void main(String... args) {
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
    var evidence = "I was charged twice for my September subscription and no refund arrived.";
    System.out.printf("JVM up for %d ms before main%n", System.currentTimeMillis() - JVM_START);

    long t = System.nanoTime();
    try (ModelJarDecisionRuntime harriet = Harriet.open()) {
      System.out.printf("Harriet.open()            %7.0f ms   backend=%s%n", ms(t), harriet.backend().getClass().getSimpleName());
      System.out.println("qualification: " + harriet.executionQualification());
      System.out.println("Evidence: " + evidence);

      // The CLI demo's questions, bare, one at a time (what the user ran).
      Map<String, AnswerSpace> bare = new LinkedHashMap<>();
      bare.put("billing?", new Noul("Is this ticket about billing?"));
      bare.put("queue", new Choice("Which queue should handle this?", List.of("billing", "technical", "shipping")));
      bare.put("urgency", new Score("How urgent is this ticket?", List.of("can wait", "this week", "today")));

      // The same questions with a rubric: a written rule per outcome.
      Map<String, AnswerSpace> rubric = new LinkedHashMap<>();
      rubric.put("billing?", new Noul("Is this ticket about billing?",
          "the customer is writing about a charge, an invoice, a payment, a refund or a subscription fee",
          "the ticket is about anything else, such as a bug, a login problem or a delivery"));
      rubric.put("queue", new Choice("Which queue should handle this?", List.of("billing", "technical", "shipping"),
          Map.of("billing", "charges, invoices, refunds, subscription payments",
                 "technical", "bugs, outages, errors, login and integration problems",
                 "shipping", "delivery, tracking, damaged or missing parcels")));
      rubric.put("urgency", new Score("How urgent is this ticket?", List.of("can wait", "this week", "today"),
          Map.of("can wait", "a question or a request with no money or access at stake",
                 "this week", "the customer is losing money or a feature, but can still use the product",
                 "today", "the customer is blocked, locked out, or being charged wrongly right now")));

      System.out.println();
      System.out.println("-- pass 1: bare questions, one decide() each (cold: JIT warm-up + evidence prefill on the first)");
      runEach(harriet, bare, evidence);
      System.out.println("-- pass 2: bare questions again (warm)");
      runEach(harriet, bare, evidence);
      System.out.println("-- pass 3: with rubrics, one decide() each (warm)");
      runEach(harriet, rubric, evidence);
      System.out.println("-- pass 4: with rubrics, systemOne() (one call, evidence read once)");
      t = System.nanoTime();
      Map<String, Verdict> all = Harriet.systemOne(harriet, rubric, evidence);
      double total = ms(t);
      all.forEach((k, v) -> report(k, v, -1));
      System.out.printf("   systemOne total          %7.0f ms%n", total);
    }
  }

  static void runEach(ModelJarDecisionRuntime harriet, Map<String, AnswerSpace> qs, String evidence) {
    for (var e : qs.entrySet()) {
      long t = System.nanoTime();
      Verdict v = harriet.decide(e.getValue(), evidence);
      report(e.getKey(), v, ms(t));
    }
  }

  static void report(String label, Verdict v, double millis) {
    System.out.printf("  %-10s %-12s p=%.3f  confidence=%.2f  %s%n",
        label, v.winner(), v.probabilityOf(v.winner()), v.confidence(),
        millis < 0 ? "" : String.format("%7.0f ms", millis));
  }

  static double ms(long since) { return (System.nanoTime() - since) / 1e6; }
}
