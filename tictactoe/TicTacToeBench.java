//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4
//SOURCES TicTacToe.java

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

/**
 * Headless measurement behind the tic-tac-toe demo: how often the model's top cell is a minimax-
 * optimal move, whether it takes wins and blocks threats, how its two Nouls track the truth, and
 * how long each decision takes. Runs the same positions through hosted Jev when TYPESAFE_API_KEY
 * is set and {@code --arm jev} or {@code --arm both} is given.
 *
 * <pre>
 *   jbang TicTacToeBench.java                       # Harriet, both board representations
 *   jbang TicTacToeBench.java --arm both --rep GRID # plus Jev, one representation
 * </pre>
 *
 * Positions are sampled from random play with a fixed seed (20 per ply with O to move), then the
 * model plays whole games as O against a random X and against a perfect X. Every decision goes to
 * {@code out/tictactoe/<arm>-<rep>.jsonl}; the summary is printed and written beside it.
 */
class TicTacToeBench {

  record Row(TicTacToe.Decision d, String source, Set<Integer> optimal, Set<Integer> oWins, Set<Integer> xThreats) {
    boolean topOptimal() { return optimal.contains(d.top()); }
    boolean winAvailable() { return !oWins.isEmpty(); }
    boolean tookWin() { return oWins.contains(d.top()); }
    boolean blockNeeded() { return oWins.isEmpty() && !xThreats.isEmpty(); }
    boolean blocked() { return xThreats.contains(d.top()); }
    double massOnOptimal() {
      double m = 0;
      for (var e : d.move().entrySet()) if (optimal.contains(TicTacToe.cellOf(e.getKey()))) m += e.getValue();
      return m;
    }
  }

  interface Arm {
    String name();
    TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep rep) throws Exception;
  }

  public static void main(String[] args) throws Exception {
    Map<String, String> opts = parse(args);
    String arm = opts.getOrDefault("arm", "harriet");
    List<TicTacToe.Rep> reps = new ArrayList<>();
    for (String r : opts.getOrDefault("rep", "GRID,LIST").split(",")) reps.add(TicTacToe.Rep.valueOf(r.trim()));
    int perPly = Integer.parseInt(opts.getOrDefault("positions", "20"));
    int randomGames = Integer.parseInt(opts.getOrDefault("games-random", "10"));
    int perfectGames = Integer.parseInt(opts.getOrDefault("games-perfect", "3"));
    Path out = Path.of(opts.getOrDefault("out", "out/tictactoe"));
    Files.createDirectories(out);

    List<Arm> arms = new ArrayList<>();
    ModelJarDecisionRuntime runtime = null;
    if (arm.equals("harriet") || arm.equals("both")) {
      long t = System.nanoTime();
      runtime = Harriet.open();
      System.out.printf("Harriet.open(): %.1f s, backend %s%n", (System.nanoTime() - t) / 1e9, runtime.backend().name());
      ModelJarDecisionRuntime rt = runtime;
      arms.add(new Arm() {
        public String name() { return "harriet"; }
        public TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep rep) { return TicTacToe.decideHarriet(rt, b, rep); }
      });
    }
    if (arm.equals("jev") || arm.equals("both")) {
      TicTacToe.Jev jev = TicTacToe.Jev.fromEnvironment();
      if (jev == null) throw new IllegalStateException("--arm " + arm + " needs TYPESAFE_API_KEY in the environment");
      arms.add(new Arm() {
        public String name() { return "jev"; }
        public TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep rep) throws Exception { return jev.decide(b, rep); }
      });
    }

    List<TicTacToe.Board> positions = samplePositions(perPly, new Random(42));
    System.out.printf("%d sampled positions with O to move%n", positions.size());
    ObjectMapper json = new ObjectMapper();
    List<String> summaries = new ArrayList<>();
    try {
      for (Arm a : arms) {
        for (TicTacToe.Rep rep : reps) {
          List<Row> rows = new ArrayList<>();
          Path jsonl = out.resolve(a.name() + "-" + rep + ".jsonl");
          try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(jsonl))) {
            for (TicTacToe.Board b : positions) {
              Row r = row(a.decide(b, rep), "position", b);
              rows.add(r);
              w.println(json.writeValueAsString(r));
              progress(a.name(), rep, r);
            }
            Map<String, int[]> results = new LinkedHashMap<>();
            results.put("vs-random", new int[3]);
            results.put("vs-perfect", new int[3]);
            for (int g = 0; g < randomGames; g++) playGame(a, rep, new Random(1000 + g), false, rows, w, results.get("vs-random"), json);
            for (int g = 0; g < perfectGames; g++) playGame(a, rep, new Random(2000 + g), true, rows, w, results.get("vs-perfect"), json);
            String s = summarize(a.name(), rep, rows, results);
            summaries.add(s);
            System.out.println(s);
          }
        }
      }
    } finally {
      if (runtime != null) runtime.close();
    }
    Files.writeString(out.resolve("summary.md"), String.join("\n\n", summaries) + "\n");
    System.out.println("written: " + out.toAbsolutePath());
  }

  static Row row(TicTacToe.Decision d, String source, TicTacToe.Board b) {
    return new Row(d, source, TicTacToe.optimalMoves(b), b.immediateWins('O'), b.immediateWins('X'));
  }

  static void progress(String arm, TicTacToe.Rep rep, Row r) {
    System.out.printf("%-7s %-4s %-9s %s  top=%-13s %s  win=%.2f thr=%.2f  %5d ms%n", arm, rep, r.source(), r.d().cells(),
        TicTacToe.NAMES[r.d().top()], r.topOptimal() ? "optimal" : "blunder", r.d().winNow(), r.d().threat(), r.d().millis());
  }

  /** O is the model; X is random, or a perfect player picking randomly among its optimal moves. */
  static void playGame(Arm a, TicTacToe.Rep rep, Random rnd, boolean perfect, List<Row> rows, PrintWriter w, int[] tally, ObjectMapper json) throws Exception {
    TicTacToe.Board b = TicTacToe.Board.empty();
    while (!b.terminal()) {
      if (b.toMove() == 'X') {
        List<Integer> pool = perfect ? new ArrayList<>(TicTacToe.optimalMoves(b)) : b.legal();
        b = b.play(pool.get(rnd.nextInt(pool.size())), 'X');
      } else {
        Row r = row(a.decide(b, rep), perfect ? "vs-perfect" : "vs-random", b);
        rows.add(r);
        w.println(json.writeValueAsString(r));
        progress(a.name(), rep, r);
        b = b.play(r.d().top(), 'O');
      }
    }
    char win = b.winner();
    tally[win == 'O' ? 0 : win == '.' ? 1 : 2]++;
    System.out.printf("%-7s %-4s game %-10s %s -> %s%n", a.name(), rep, perfect ? "vs-perfect" : "vs-random", b.cells(),
        win == 'O' ? "O (model) wins" : win == 'X' ? "X wins" : "draw");
  }

  static String summarize(String arm, TicTacToe.Rep rep, List<Row> rows, Map<String, int[]> results) {
    int n = rows.size();
    long optimal = rows.stream().filter(Row::topOptimal).count();
    long winAvail = rows.stream().filter(Row::winAvailable).count();
    long tookWin = rows.stream().filter(r -> r.winAvailable() && r.tookWin()).count();
    long blockNeeded = rows.stream().filter(Row::blockNeeded).count();
    long blocked = rows.stream().filter(r -> r.blockNeeded() && r.blocked()).count();
    double mass = rows.stream().mapToDouble(Row::massOnOptimal).average().orElse(0);
    double winAcc = rows.stream().filter(r -> (r.d().winNow() >= 0.5) == r.winAvailable()).count() / (double) n;
    double winBrier = rows.stream().mapToDouble(r -> sq(r.d().winNow() - (r.winAvailable() ? 1 : 0))).average().orElse(0);
    long threatTrue = rows.stream().filter(r -> !r.xThreats().isEmpty()).count();
    double thrAcc = rows.stream().filter(r -> (r.d().threat() >= 0.5) == !r.xThreats().isEmpty()).count() / (double) n;
    double thrBrier = rows.stream().mapToDouble(r -> sq(r.d().threat() - (r.xThreats().isEmpty() ? 0 : 1))).average().orElse(0);
    long[] ms = rows.stream().mapToLong(r -> r.d().millis()).sorted().toArray();
    StringBuilder s = new StringBuilder();
    s.append(String.format("### %s, %s (%d decisions; model %s)%n%n", arm, rep, n, rows.get(0).d().model()));
    s.append("| measure | value |\n|---|---:|\n");
    s.append(String.format("| top cell is minimax-optimal | %d / %d (%.0f%%) |%n", optimal, n, 100.0 * optimal / n));
    s.append(String.format("| mean probability mass on optimal cells | %.2f |%n", mass));
    s.append(String.format("| took an available win | %d / %d |%n", tookWin, winAvail));
    s.append(String.format("| blocked when a block was needed | %d / %d |%n", blocked, blockNeeded));
    s.append(String.format("| \"can O win now\" Noul: accuracy at 0.5, Brier (positives %d) | %.0f%%, %.3f |%n", winAvail, 100 * winAcc, winBrier));
    s.append(String.format("| \"can X win next\" Noul: accuracy at 0.5, Brier (positives %d) | %.0f%%, %.3f |%n", threatTrue, 100 * thrAcc, thrBrier));
    s.append(String.format("| ms per decision: min / median / max | %d / %d / %d |%n", ms[0], ms[ms.length / 2], ms[ms.length - 1]));
    for (var e : results.entrySet()) {
      int[] t = e.getValue();
      if (t[0] + t[1] + t[2] > 0) s.append(String.format("| games %s (model wins / draws / X wins) | %d / %d / %d |%n", e.getKey(), t[0], t[1], t[2]));
    }
    return s.toString();
  }

  static double sq(double x) { return x * x; }

  /** Random play, fixed seed: up to {@code perPly} distinct non-terminal boards with O to move after 1, 2, 3 and 4 X moves. */
  static List<TicTacToe.Board> samplePositions(int perPly, Random rnd) {
    List<TicTacToe.Board> out = new ArrayList<>();
    for (int xMoves = 1; xMoves <= 4; xMoves++) {
      Set<String> seen = new LinkedHashSet<>();
      int attempts = 0;
      while (seen.size() < perPly && attempts++ < 5000) {
        TicTacToe.Board b = TicTacToe.Board.empty();
        for (int i = 0; i < 2 * xMoves - 1 && !b.terminal(); i++) {
          List<Integer> legal = b.legal();
          b = b.play(legal.get(rnd.nextInt(legal.size())), b.toMove());
        }
        if (!b.terminal() && b.toMove() == 'O') seen.add(b.cells());
      }
      for (String c : seen) out.add(new TicTacToe.Board(c));
    }
    return out;
  }

  static Map<String, String> parse(String[] args) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--")) m.put(args[i].substring(2), i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
    }
    return m;
  }
}
