//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.springframework.boot:spring-boot-starter:4.1.1
//DEPS org.modeljars.composite:harriet:0.1.69

package demo;

import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Map;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class HarrietTicketsApp {

  public static void main(String[] args) {
    SpringApplication.run(HarrietTicketsApp.class, args);
  }

  @Bean
  ModelJarDecisionRuntime decisions() {
    return Harriet.open();
  }

  @Bean
  ApplicationRunner go(TicketTriage triage) {
    return args -> {
      List<String> tickets = List.of(
          "My invoice was charged twice this month and nobody answers the phone.",
          "The export button has been broken since the last release; every CSV comes out empty.",
          "Can I get a quote for 50 seats on the annual plan with SSO?",
          "Our production API keys stopped working ten minutes ago and checkout is down for every customer.",
          "The VAT number on last month's invoice is wrong; accounting needs a corrected copy by Friday.",
          "Where do I find the API documentation for webhooks?");
      int n = 0;
      for (String ticket : tickets) {
        long t0 = System.nanoTime();
        Triage result = triage.triage(ticket);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("T%d %-100s%n   queue=%s (%.2f)  urgent=%.2f  mood=%s (%.2f)  %d ms%n",
            ++n, ticket, result.queue(), result.queueProbability(), result.urgent(), result.mood(), result.moodProbability(), ms);
      }
    };
  }

  public record Triage(String queue, double queueProbability, double urgent, String mood, double moodProbability) {}

  @Component
  public static class TicketTriage {

    private static final Choice QUEUE = new Choice(
        "Which queue should handle this ticket?",
        List.of("billing", "technical", "sales"),
        Map.of("billing", "charges, invoices, refunds and subscription payments",
               "technical", "defects, outages, errors, login and integration problems",
               "sales", "pricing, quotes, plans, seats and upgrades"));

    private static final Noul URGENT = new Noul(
        "Is this ticket urgent?",
        "the customer is blocked, locked out, losing money, or being charged wrongly right now",
        "a question or request that can wait a few days without harm");

    private static final Score MOOD = new Score(
        "How frustrated is the customer?",
        List.of("calm", "frustrated", "very angry"),
        Map.of("calm", "neutral or friendly wording, a plain question",
               "frustrated", "complains about a problem or a delay",
               "very angry", "hostile wording, threats to leave, or repeated failed attempts"));

    private final ModelJarDecisionRuntime runtime;

    public TicketTriage(ModelJarDecisionRuntime runtime) {
      this.runtime = runtime;
    }

    Triage triage(String ticket) {
      Map<String, Verdict> answers = Harriet.systemOne(runtime,
          Map.of("queue", QUEUE, "urgent", URGENT, "mood", MOOD), ticket);
      Verdict queue = answers.get("queue");
      Verdict mood = answers.get("mood");
      return new Triage(queue.winner(), queue.probabilityOf(queue.winner()),
          answers.get("urgent").probabilityOfTrue(), mood.winner(), mood.probabilityOf(mood.winner()));
    }
  }
}
