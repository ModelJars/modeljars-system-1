//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx4g
//DEPS org.modeljars:modeljars:0.1.69
//DEPS org.modeljars.huggingface:ggml-org.laya-gguf.q8_0:1.0.0-q8_0.1

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionAnswer;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionModel;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionPrompt.Option;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarInstaller;
import org.modeljars.ModelJarRegistry;

class LayaHello {
  private static final ModelJar LAYA =
      ModelJar.of("org.modeljars.huggingface:ggml-org.laya-gguf.q8_0:1.0.0-q8_0.1");

  public static void main(String... args) {
    long t0 = System.nanoTime();
    Path weights = new ModelJarInstaller(ModelJarRegistry.fromClasspath()).install(LAYA);
    String ticket = "My invoice was charged twice this month and nobody answers the phone.";
    try (ModernBertDecisionModel laya = ModernBertDecisionModel.open(weights)) {
      long t1 = System.nanoTime();
      System.out.printf("install+open: %.2f s (%s)%n", (t1 - t0) / 1e9, weights);
      List<Option> teams = List.of(Option.of("billing"), Option.of("technical"), Option.of("sales"));
      ModernBertDecisionAnswer dept = laya.decide(QuestionType.CHOICE,
          "Which team should handle this ticket?", teams, ticket);
      ModernBertDecisionAnswer urgent = laya.decide(QuestionType.NOUL,
          "Is this ticket urgent?", List.of(Option.of("false"), Option.of("true")), ticket);
      ModernBertDecisionAnswer frustration = laya.decide(QuestionType.SCORE,
          "How frustrated is the customer?",
          List.of(Option.of("calm"), Option.of("frustrated"), Option.of("very angry")), ticket);
      long t2 = System.nanoTime();
      System.out.printf("three questions: %.3f s%n", (t2 - t1) / 1e9);
      for (var e : List.of(Map.entry("department", dept), Map.entry("urgent", urgent), Map.entry("frustration", frustration))) {
        var a = e.getValue();
        System.out.printf("%-12s chosen=%d probs=%s confidence=%.3f bucket=%s uncalibrated=%b%n",
            e.getKey(), a.chosen(), Arrays.toString(a.probabilities()), a.confidence(), a.temperatureBucket(), a.uncalibrated());
      }
    }
  }
}
