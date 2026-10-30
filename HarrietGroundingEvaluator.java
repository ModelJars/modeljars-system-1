//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69
//DEPS org.springframework.ai:spring-ai-commons:2.0.1

import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

/** Spring AI's Evaluator contract, answered by a local Noul instead of a judge LLM. */
class HarrietGroundingEvaluator implements Evaluator {

  private static final Noul GROUNDED = new Noul(
      "Is every claim in the assistant answer supported by the supporting context?",
      "each statement in the answer is stated in, or follows directly from, the supporting context",
      "the answer introduces a fact, promise or detail the supporting context does not support");

  private final ModelJarDecisionRuntime runtime;
  private final double threshold;

  HarrietGroundingEvaluator(ModelJarDecisionRuntime runtime, double threshold) {
    this.runtime = runtime;
    this.threshold = threshold;
  }

  @Override
  public EvaluationResponse evaluate(EvaluationRequest request) {
    String context = request.getDataList().stream().map(Document::getText).collect(Collectors.joining("\n"));
    String state = "USER QUESTION:\n" + request.getUserText()
        + "\n\nSUPPORTING CONTEXT:\n" + context
        + "\n\nASSISTANT ANSWER:\n" + request.getResponseContent();
    Verdict verdict = Harriet.decide(runtime, GROUNDED, state);
    double grounded = verdict.probabilityOfTrue();
    boolean pass = grounded >= threshold;
    return new EvaluationResponse(pass, (float) grounded,
        pass ? "grounded" : "the answer introduces something the context does not support",
        Map.of("model", runtime.descriptor().alias(), "threshold", threshold));
  }

  public static void main(String... args) {
    List<Document> context = List.of(
        new Document("Refunds are issued to the original payment method within five business days."),
        new Document("A duplicate charge is reversed automatically once billing reconciliation runs nightly."),
        new Document("Account closures require written confirmation from the account holder."));
    String question = "I was charged twice. Do I need to call to get the duplicate charge reversed?";
    try (ModelJarDecisionRuntime runtime = Harriet.open()) {
      Evaluator evaluator = new HarrietGroundingEvaluator(runtime, 0.7);
      for (String answer : List.of(
          "No. A duplicate charge is reversed automatically when billing reconciliation runs nightly.",
          "No. Duplicate charges are reversed automatically overnight, and you will also receive a courtesy credit for the inconvenience.")) {
        long t = System.nanoTime();
        EvaluationResponse response = evaluator.evaluate(new EvaluationRequest(question, context, answer));
        System.out.printf("%s%n   pass=%b score=%.3f feedback=\"%s\" (%d ms)%n", answer, response.isPass(), response.getScore(), response.getFeedback(), (System.nanoTime() - t) / 1_000_000);
      }
    }
  }
}
