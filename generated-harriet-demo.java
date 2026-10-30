//JAVA 25+
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
// Library version: newest Maven Central release when this demo was generated. Regenerate to refresh.
//DEPS org.modeljars.composite:harriet:0.1.69

import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

class ModelJarsDemo {
  public static void main(String... args) {
    quietLibraries();
    var evidence = args.length == 0 ? "I was charged twice for my September subscription and no refund arrived." : String.join(" ", args);
    System.out.println("Evidence: " + evidence);
    System.out.println();

    // Every outcome carries a written rule. That is not decoration: measured over 120
    // JevBench items on the same weights, accuracy was 0.8917 with a rubric and 0.7500
    // without, and the ordinal family went from 0.2500 to 0.9167.
    var billing =
        new Noul(
            "Is this ticket about billing?",
            "the customer writes about a charge, invoice, payment, refund or fee",
            "the ticket is about something else: a defect, a login, a delivery");
    var queue =
        new Choice(
            "Which queue should handle this?",
            List.of("billing", "technical", "shipping"),
            Map.of(
                "billing", "charges, invoices, refunds and subscription payments",
                "technical", "defects, outages, errors, login and integration problems",
                "shipping", "delivery, tracking, and damaged or missing parcels"));
    var urgency =
        new Score(
            "How urgent is this ticket?",
            List.of("can wait", "this week", "today"),
            Map.of(
                "can wait", "a question or request with no money or access at stake",
                "this week", "the customer is losing money or a feature but can go on",
                "today", "the customer is blocked, locked out or being charged wrongly"));

    long opening = System.nanoTime();
    try (var harriet = Harriet.open()) {
      System.out.printf(
          "Open: %,d ms  (resolve the weights, verify their digest, map them; backend %s)%n",
          elapsedMillis(opening), harriet.backend().name());
      System.out.println();

      // Nothing is generated: each answer is one forward pass over exactly the declared
      // outcomes. The first decision also pays the kernels' warm-up, so the same question
      // is asked twice and the second answer is the steady state.
      decide("billing?", billing, harriet, evidence, "cold: warm-up + reading the evidence");
      decide("billing?", billing, harriet, evidence, "warm: the same decision again");
      decide("queue", queue, harriet, evidence, "warm");
      decide("urgency", urgency, harriet, evidence, "warm");
    }
  }

  private static void decide(
      String label,
      AnswerSpace space,
      ModelJarDecisionRuntime harriet,
      String evidence,
      String note) {
    long started = System.nanoTime();
    Verdict verdict = harriet.decide(space, evidence);
    System.out.printf(
        "  %-10s %-12s p=%.3f  confidence=%.2f  %,7d ms  %s%n",
        label,
        verdict.winner(),
        verdict.probabilityOf(verdict.winner()),
        verdict.confidence(),
        elapsedMillis(started),
        note);
  }

  private static long elapsedMillis(long since) {
    return (System.nanoTime() - since) / 1_000_000L;
  }

  private static void quietLibraries() {
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
  }
}
