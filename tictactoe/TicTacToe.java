import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Verdict;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

/**
 * Tic-tac-toe as a typed decision, shared by the bench and the JavaFX app (JBang {@code //SOURCES}).
 *
 * <p>The model always plays O. One call per move asks three questions about the same board: which
 * empty cell to play (a Choice over the legal cells), whether O can win on this move (a Noul) and
 * whether X could win on the next move (a Noul). Minimax supplies exact ground truth for all three.
 */
final class TicTacToe {

  static final String[] NAMES = {
    "top-left", "top-center", "top-right",
    "middle-left", "center", "middle-right",
    "bottom-left", "bottom-center", "bottom-right"
  };
  static final int[][] LINES = {
    {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
  };

  /** How the board is written into the state; the HINTS variants add the rules and a one-line strategy. */
  enum Rep { GRID, LIST, GRID_HINTS, LIST_HINTS }

  static final String HINTS =
      "Rules: players alternate; the first to fill a whole row, column or diagonal with their mark wins; a full board is a draw.\n"
      + "Good play: take a cell that completes three O in a line if there is one; otherwise take the cell where X would complete three in a line; "
      + "otherwise prefer the center, then a corner.\n";

  static final Noul WIN_NOW = new Noul("Can O win right now, on this move?");
  static final Noul THREAT = new Noul("If O does not block, can X win on X's very next move?");

  private TicTacToe() {}

  /** Immutable board: nine cells, each 'X', 'O' or '.'; X always moves first. */
  record Board(String cells) {
    static Board empty() { return new Board("........."); }

    char at(int i) { return cells.charAt(i); }

    Board play(int i, char mark) {
      if (at(i) != '.') throw new IllegalArgumentException("cell " + NAMES[i] + " is taken");
      char[] c = cells.toCharArray();
      c[i] = mark;
      return new Board(new String(c));
    }

    List<Integer> legal() {
      List<Integer> out = new ArrayList<>();
      for (int i = 0; i < 9; i++) if (at(i) == '.') out.add(i);
      return out;
    }

    char winner() {
      for (int[] l : LINES) {
        char a = at(l[0]);
        if (a != '.' && a == at(l[1]) && a == at(l[2])) return a;
      }
      return '.';
    }

    boolean full() { return cells.indexOf('.') < 0; }

    boolean terminal() { return winner() != '.' || full(); }

    char toMove() {
      long x = cells.chars().filter(ch -> ch == 'X').count();
      long o = cells.chars().filter(ch -> ch == 'O').count();
      return x == o ? 'X' : 'O';
    }

    /** Cells where {@code mark} completes a line this move. */
    Set<Integer> immediateWins(char mark) {
      Set<Integer> out = new TreeSet<>();
      for (int i : legal()) if (play(i, mark).winner() == mark) out.add(i);
      return out;
    }
  }

  // ---- ground truth -------------------------------------------------------------------------

  private static final Map<String, Integer> MEMO = new HashMap<>();

  /** Minimax value for the side to move, from O's point of view: +1 O wins, 0 draw, -1 X wins. */
  static int value(Board b) {
    Integer m = MEMO.get(b.cells());
    if (m != null) return m;
    int v;
    char w = b.winner();
    if (w == 'O') v = 1;
    else if (w == 'X') v = -1;
    else if (b.full()) v = 0;
    else {
      char side = b.toMove();
      v = side == 'O' ? -2 : 2;
      for (int i : b.legal()) {
        int child = value(b.play(i, side));
        v = side == 'O' ? Math.max(v, child) : Math.min(v, child);
      }
    }
    MEMO.put(b.cells(), v);
    return v;
  }

  /** The legal moves for the side to move that keep the best minimax value. */
  static Set<Integer> optimalMoves(Board b) {
    char side = b.toMove();
    Map<Integer, Integer> values = new HashMap<>();
    for (int i : b.legal()) values.put(i, value(b.play(i, side)));
    int best = side == 'O'
        ? values.values().stream().mapToInt(Integer::intValue).max().orElseThrow()
        : values.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
    Set<Integer> out = new TreeSet<>();
    values.forEach((i, v) -> { if (v == best) out.add(i); });
    return out;
  }

  // ---- the decision -------------------------------------------------------------------------

  static String state(Board b, Rep rep) {
    StringBuilder s = new StringBuilder("You are O in a game of tic-tac-toe against X. X moved first.\n");
    if (rep == Rep.GRID_HINTS || rep == Rep.LIST_HINTS) s.append(HINTS);
    switch (rep) {
      case GRID, GRID_HINTS -> {
        s.append("The board, rows from top to bottom, columns from left to right ('.' is empty):\n");
        for (int r = 0; r < 3; r++) {
          s.append(' ').append(b.at(3 * r)).append(" | ").append(b.at(3 * r + 1)).append(" | ").append(b.at(3 * r + 2)).append('\n');
          if (r < 2) s.append("---+---+---\n");
        }
      }
      case LIST, LIST_HINTS -> {
        s.append("X occupies: ").append(names(b, 'X')).append(".\n");
        s.append("O occupies: ").append(names(b, 'O')).append(".\n");
      }
    }
    s.append("Empty cells: ").append(names(b, '.')).append(".\n");
    s.append("It is O's turn.");
    return s.toString();
  }

  private static String names(Board b, char mark) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < 9; i++) if (b.at(i) == mark) out.add(NAMES[i]);
    return out.isEmpty() ? "nothing" : String.join(", ", out);
  }

  static Choice moveQuestion(Board b) {
    List<String> options = new ArrayList<>();
    for (int i : b.legal()) options.add(NAMES[i]);
    return new Choice("Which empty cell should O play now?", options);
  }

  /** The three questions asked about one board, in a fixed order. */
  static Map<String, AnswerSpace> questions(Board b) {
    Map<String, AnswerSpace> q = new LinkedHashMap<>();
    q.put("move", moveQuestion(b));
    q.put("win", WIN_NOW);
    q.put("threat", THREAT);
    return q;
  }

  static int cellOf(String name) {
    for (int i = 0; i < 9; i++) if (NAMES[i].equals(name)) return i;
    throw new IllegalArgumentException("not a cell: " + name);
  }

  /** One answered call: probabilities over the legal cells and the two Nouls, with the clock. */
  record Decision(
      String arm, String model, Rep rep, String cells, String state,
      Map<String, Double> move, double winNow, double threat, long millis) {

    int top() {
      return cellOf(move.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());
    }
  }

  /** The move question alone: the fast path for interactive play; the two Nouls are reported as NaN. */
  static Decision decideMoveOnly(ModelJarDecisionRuntime runtime, Board b, Rep rep) {
    String state = state(b, rep);
    long t = System.nanoTime();
    Verdict move = Harriet.decide(runtime, moveQuestion(b), state);
    long ms = (System.nanoTime() - t) / 1_000_000;
    Map<String, Double> p = new LinkedHashMap<>();
    for (int i : b.legal()) p.put(NAMES[i], move.probabilityOf(NAMES[i]));
    return new Decision("harriet", runtime.backend().name(), rep, b.cells(), state, p, Double.NaN, Double.NaN, ms);
  }

  static Decision decideHarriet(ModelJarDecisionRuntime runtime, Board b, Rep rep) {
    String state = state(b, rep);
    long t = System.nanoTime();
    Map<String, Verdict> answers = Harriet.systemOne(runtime, questions(b), state);
    long ms = (System.nanoTime() - t) / 1_000_000;
    Verdict move = answers.get("move");
    Map<String, Double> p = new LinkedHashMap<>();
    for (int i : b.legal()) p.put(NAMES[i], move.probabilityOf(NAMES[i]));
    return new Decision("harriet", runtime.backend().name(), rep, b.cells(), state, p,
        answers.get("win").probabilityOfTrue(), answers.get("threat").probabilityOfTrue(), ms);
  }

  /** The hosted arm: the same state and questions over TypeSafe's HTTP API. */
  static final class Jev {
    static final String URL = "https://api.typesafe.ai/v1/systemone";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final String model;

    Jev(String key, String model) {
      this.key = key;
      this.model = model;
    }

    /** Reads the key from TYPESAFE_API_KEY, or returns null when it is not set. */
    static Jev fromEnvironment() {
      String key = System.getenv("TYPESAFE_API_KEY");
      return key == null || key.isBlank() ? null : new Jev(key, "jev-1.13.0");
    }

    Decision decide(Board b, Rep rep) throws IOException, InterruptedException {
      return decide(b, rep, true);
    }

    /** With {@code nouls} false only the move question is sent, matching the app's fast path. */
    Decision decide(Board b, Rep rep, boolean nouls) throws IOException, InterruptedException {
      String state = state(b, rep);
      ObjectNode body = json.createObjectNode();
      body.put("model", model);
      body.put("state", state);
      ObjectNode qs = body.putObject("questions");
      ObjectNode move = qs.putObject("move");
      move.put("type", "choice");
      move.put("instructions", moveQuestion(b).question());
      ObjectNode criteria = move.putObject("criteria");
      for (int i : b.legal()) criteria.put(NAMES[i], NAMES[i]);
      if (nouls) {
        qs.putObject("win").put("type", "noul").put("instructions", WIN_NOW.question());
        qs.putObject("threat").put("type", "noul").put("instructions", THREAT.question());
      }
      HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
          .header("Authorization", "Bearer " + key)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build();
      long t = System.nanoTime();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      long ms = (System.nanoTime() - t) / 1_000_000;
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
      }
      JsonNode answers = json.readTree(response.body()).path("answers");
      Map<String, Double> p = new LinkedHashMap<>();
      JsonNode probs = answers.path("move").path("probabilities");
      for (int i : b.legal()) p.put(NAMES[i], probs.path(NAMES[i]).asDouble());
      return new Decision("jev", json.readTree(response.body()).path("model").asText(model), rep,
          b.cells(), state, p, nouls ? answers.path("win").path("noul").asDouble() : Double.NaN,
          nouls ? answers.path("threat").path("noul").asDouble() : Double.NaN, ms);
    }
  }
}
