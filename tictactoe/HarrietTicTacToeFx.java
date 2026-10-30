//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4
//DEPS org.openjfx:javafx-controls:25.0.5
//SOURCES TicTacToe.java

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.transform.Transform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.Glow;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

/**
 * Harriet plays O against you and shows its intuition before it moves: every empty cell glows
 * with the probability the model gives it, and a clock shows what the decision cost. With
 * {@code --jev} (and TYPESAFE_API_KEY set) the same board and question also go to TypeSafe's hosted
 * Jev, which is shown by name once Harriet has moved, and a score table compares the two arms
 * over the session: minimax-optimal moves, median time per move, agreement, game record.
 *
 * <pre>
 *   jbang HarrietTicTacToeFx.java                      # you are X; click a cell
 *   jbang HarrietTicTacToeFx.java --auto random        # X plays itself, for a hands-free recording
 *   jbang HarrietTicTacToeFx.java --jev                # also ask hosted Jev; needs TYPESAFE_API_KEY
 *   jbang HarrietTicTacToeFx.java --jev --player jev   # Jev plays, Harriet shadows
 *   jbang HarrietTicTacToeFx.java --nouls              # also ask "can O win now" and "must O block" (two more readouts)
 *   jbang HarrietTicTacToeFx.java --auto random --games 2 --record out/rec   # 20 fps frames + demo.mp4/.gif via ffmpeg
 *   jbang HarrietTicTacToeFx.java --rep LIST           # another board text (see TicTacToe.Rep)
 *   jbang HarrietTicTacToeFx.java --auto random --seed 42   # replay the same auto-played games
 * </pre>
 */
public class HarrietTicTacToeFx {

  public static void main(String[] args) {
    Application.launch(Game.class, args);
  }

  /** One decision-maker: Harriet in-process or Jev over HTTP. */
  interface Arm {
    String name();
    String where();
    TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep rep, boolean nouls) throws Exception;
  }

  /** Running tally for one arm over the session. */
  static final class Tally {
    int moves, optimal, agreed;
    final List<Long> millis = new ArrayList<>();
    final int[] games = new int[3];

    double medianSeconds() {
      if (millis.isEmpty()) return Double.NaN;
      List<Long> s = new ArrayList<>(millis);
      Collections.sort(s);
      return s.get(s.size() / 2) / 1000.0;
    }
  }

  public static class Game extends Application {

    static final Color BG = Color.web("#1b1f2a");
    static final Color GRID = Color.web("#3e4656");
    static final Color X_COLOR = Color.web("#ff6b6b");
    static final Color O_COLOR = Color.web("#4ecdc4");
    static final Color HEAT = Color.web("#ffb347");
    static final Color TEXT = Color.web("#e6e9ef");
    static final Color MUTED = Color.web("#8a93a6");
    static final Color PICK = Color.web("#ffd166");
    static final Color TRACK = Color.web("#2a3040");
    static final Color SHADOW = Color.web("#7fd8ff");
    static final double CELL = 128;
    static final double BOARD = 3 * CELL;
    static final double STROKE = 11;

    TicTacToe.Rep rep = TicTacToe.Rep.GRID;
    String auto = "off";
    String playerName = "harriet";
    boolean useJev = false;
    boolean nouls = false;
    int gamesToPlay = Integer.MAX_VALUE;
    File snapshotDir;
    File recordDir;

    final ExecutorService harrietPool = Executors.newSingleThreadExecutor(r -> daemon(r, "harriet"));
    final ExecutorService jevPool = Executors.newSingleThreadExecutor(r -> daemon(r, "jev"));
    ModelJarDecisionRuntime runtime;
    Arm player, shadow;
    final Tally playerTally = new Tally();
    final Tally shadowTally = new Tally();

    TicTacToe.Board board = TicTacToe.Board.empty();
    boolean thinking;
    boolean over;
    int gamesPlayed;
    int moveNumber;
    long seed = System.nanoTime() % 100_000;
    Random autoRandom;
    TicTacToe.Decision pendingShadow;
    boolean moveLanded;

    Scene scene;
    final Cell[] cells = new Cell[9];
    Label playerTitle, status, clock, shadowTitle, shadowLine, footer;
    Label[] scorePlayer = new Label[4];
    Label[] scoreShadow = new Label[4];
    Label scorePlayerHead, scoreShadowHead;
    Bar winBar, threatBar;
    Button autoButton;
    AnimationTimer recorder;
    ExecutorService frameWriter;
    WritableImage frameImage;
    int frameCount;
    long lastFrameNs;

    @Override
    public void start(Stage stage) {
      parseArgs(getParameters().getRaw());
      autoRandom = new Random(seed);

      HBox root = new HBox(28, boardPane(), sidePanel());
      root.setAlignment(Pos.CENTER_LEFT);
      root.setPadding(new Insets(22, 24, 18, 24));
      root.setStyle("-fx-background-color: #1b1f2a;");

      scene = new Scene(root, 740, 450, BG);
      stage.setTitle("Harriet plays tic-tac-toe");
      stage.setResizable(false);
      stage.setScene(scene);
      stage.setOnCloseRequest(e -> Platform.exit());
      stage.show();

      status.setText("Opening Harriet…");
      harrietPool.submit(() -> {
        long t = System.nanoTime();
        ModelJarDecisionRuntime rt = Harriet.open();
        long ms = (System.nanoTime() - t) / 1_000_000;
        TicTacToe.Jev jev = useJev ? TicTacToe.Jev.fromEnvironment() : null;
        Platform.runLater(() -> {
          runtime = rt;
          Arm harriet = new Arm() {
            public String name() { return "Harriet"; }
            public String where() { return "local, in this JVM"; }
            public TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep r, boolean n) {
              return n ? TicTacToe.decideHarriet(rt, b, r) : TicTacToe.decideMoveOnly(rt, b, r);
            }
          };
          Arm hosted = jev == null ? null : new Arm() {
            public String name() { return "Jev"; }
            public String where() { return "hosted by TypeSafe, over the network"; }
            public TicTacToe.Decision decide(TicTacToe.Board b, TicTacToe.Rep r, boolean n) throws Exception {
              return jev.decide(b, r, n);
            }
          };
          if (playerName.equals("jev") && hosted != null) {
            player = hosted;
            shadow = harriet;
          } else {
            player = harriet;
            shadow = hosted;
          }
          playerTitle.setText(player.name().toUpperCase() + " PLAYS O · " + player.where());
          if (shadow != null) {
            shadowTitle.setText(shadow.name().toUpperCase() + " · " + shadow.where());
            scoreShadowHead.setText(shadow.name());
          } else if (useJev) {
            shadowTitle.setText("JEV · not asked: TYPESAFE_API_KEY is not set");
          }
          scorePlayerHead.setText(player.name());
          footer.setText(String.format("Harriet.open() %.1f s · %s · board text %s · %s · auto seed %d",
              ms / 1000.0, rt.backend().name(), rep, nouls ? "three questions per move" : "one question per move", seed));
          startGame();
        });
      });
    }

    @Override
    public void stop() {
      harrietPool.shutdownNow();
      jevPool.shutdownNow();
      if (runtime != null) runtime.close();
    }

    // ---- layout ------------------------------------------------------------------------------

    Node boardPane() {
      Pane pane = new Pane();
      pane.setPrefSize(BOARD, BOARD);
      pane.setMinSize(BOARD, BOARD);
      pane.setMaxSize(BOARD, BOARD);
      for (int i = 0; i < 9; i++) {
        cells[i] = new Cell(i);
        cells[i].relocate((i % 3) * CELL, (i / 3) * CELL);
        pane.getChildren().add(cells[i]);
      }
      double inset = 10;
      for (int k = 1; k <= 2; k++) {
        pane.getChildren().add(gridLine(k * CELL, inset, k * CELL, BOARD - inset));
        pane.getChildren().add(gridLine(inset, k * CELL, BOARD - inset, k * CELL));
      }
      return pane;
    }

    Line gridLine(double x1, double y1, double x2, double y2) {
      Line l = new Line(x1, y1, x2, y2);
      l.setStroke(GRID);
      l.setStrokeWidth(6);
      l.setStrokeLineCap(StrokeLineCap.ROUND);
      l.setMouseTransparent(true);
      return l;
    }

    Node sidePanel() {
      playerTitle = label("HARRIET PLAYS O · local, in this JVM", 11, MUTED, FontWeight.BOLD);
      status = label("", 17, TEXT, FontWeight.SEMI_BOLD);
      status.setWrapText(true);
      status.setMinHeight(44);
      clock = label("", 12, MUTED, FontWeight.NORMAL);

      winBar = new Bar("can O win now", O_COLOR);
      threatBar = new Bar("must O block X", X_COLOR);
      winBar.setVisible(nouls);
      winBar.setManaged(nouls);
      threatBar.setVisible(nouls);
      threatBar.setManaged(nouls);

      shadowTitle = label("", 11, SHADOW, FontWeight.BOLD);
      shadowLine = label("", 12, TEXT, FontWeight.NORMAL);
      shadowLine.setWrapText(true);
      shadowLine.setMinHeight(32);
      VBox shadowBox = new VBox(3, shadowTitle, shadowLine);
      shadowBox.setVisible(useJev);
      shadowBox.setManaged(useJev);

      GridPane score = new GridPane();
      score.setHgap(14);
      score.setVgap(3);
      scorePlayerHead = label("Harriet", 11, TEXT, FontWeight.BOLD);
      scoreShadowHead = label("", 11, SHADOW, FontWeight.BOLD);
      score.add(label("SCORE, this session", 11, MUTED, FontWeight.BOLD), 0, 0);
      score.add(scorePlayerHead, 1, 0);
      score.add(scoreShadowHead, 2, 0);
      String[] rows = {"optimal moves (minimax)", "median s per move", "agreed with each other", "games W / D / L"};
      for (int r = 0; r < rows.length; r++) {
        score.add(label(rows[r], 11, MUTED, FontWeight.NORMAL), 0, r + 1);
        scorePlayer[r] = label("", 11, TEXT, FontWeight.NORMAL);
        scoreShadow[r] = label("", 11, TEXT, FontWeight.NORMAL);
        score.add(scorePlayer[r], 1, r + 1);
        score.add(scoreShadow[r], 2, r + 1);
      }

      Button newGame = button("New game");
      newGame.setOnAction(e -> startGame());
      autoButton = button("Auto X: " + auto);
      autoButton.setOnAction(e -> {
        auto = switch (auto) { case "off" -> "random"; case "random" -> "perfect"; default -> "off"; };
        autoButton.setText("Auto X: " + auto);
        maybeAutoMove();
      });
      HBox buttons = new HBox(8, newGame, autoButton);

      footer = label("", 10, MUTED, FontWeight.NORMAL);
      footer.setWrapText(true);

      VBox box = new VBox(9, playerTitle, status, clock, winBar, threatBar, spacer(2), shadowBox, spacer(2), score, spacer(2), buttons, spacer(1), footer);
      box.setPrefWidth(300);
      box.setMinWidth(300);
      box.setAlignment(Pos.TOP_LEFT);
      return box;
    }

    // ---- game flow ---------------------------------------------------------------------------

    void startGame() {
      if (recordDir != null && recorder == null) startRecording();
      board = TicTacToe.Board.empty();
      over = false;
      thinking = false;
      moveNumber = 0;
      pendingShadow = null;
      for (Cell c : cells) c.reset();
      winBar.set(0);
      threatBar.set(0);
      clock.setText("");
      shadowLine.setText("");
      status.setText(auto.equals("off") ? "Your move. You are X." : "X plays itself (" + auto + ").");
      updateScore();
      maybeAutoMove();
    }

    void humanMove(int cell) {
      if (player == null || thinking || over || board.toMove() != 'X' || board.at(cell) != '.') return;
      placeX(cell);
    }

    void placeX(int cell) {
      board = board.play(cell, 'X');
      moveNumber++;
      for (Cell c : cells) c.clearHeat();
      cells[cell].mark('X');
      if (finish()) return;
      modelMove();
    }

    void maybeAutoMove() {
      if (auto.equals("off") || over || thinking || player == null || board.toMove() != 'X') return;
      PauseTransition p = new PauseTransition(Duration.millis(moveNumber == 0 ? 600 : 900));
      p.setOnFinished(e -> {
        if (auto.equals("off") || over || thinking || board.toMove() != 'X') return;
        List<Integer> pool = auto.equals("perfect") ? new ArrayList<>(TicTacToe.optimalMoves(board)) : board.legal();
        placeX(pool.get(autoRandom.nextInt(pool.size())));
      });
      p.play();
    }

    void modelMove() {
      thinking = true;
      moveLanded = false;
      pendingShadow = null;
      shadowLine.setText("");
      status.setText(player.name() + " is thinking…");
      clock.setText(nouls ? "one forward pass, three questions" : "one forward pass, one question");
      for (Cell c : cells) if (board.at(c.index) == '.') c.pulse(true);
      TicTacToe.Board snapshot = board;
      Future<TicTacToe.Decision> shadowFuture = shadow == null ? null
          : (shadow.name().equals("Jev") ? jevPool : harrietPool).submit(() -> shadow.decide(snapshot, rep, nouls));
      (player.name().equals("Jev") ? jevPool : harrietPool).submit(() -> {
        try {
          TicTacToe.Decision d = player.decide(snapshot, rep, nouls);
          Platform.runLater(() -> showDecision(snapshot, d));
        } catch (Exception ex) {
          Platform.runLater(() -> status.setText(player.name() + " failed: " + ex.getMessage()));
        }
        if (shadowFuture != null) {
          try {
            TicTacToe.Decision sd = shadowFuture.get();
            Platform.runLater(() -> { pendingShadow = sd; if (moveLanded) showShadow(snapshot); });
          } catch (Exception ex) {
            Platform.runLater(() -> shadowLine.setText(shadow.name() + " call failed: " + ex.getMessage()));
          }
        }
      });
    }

    void showDecision(TicTacToe.Board before, TicTacToe.Decision d) {
      for (Cell c : cells) c.pulse(false);
      double max = d.move().values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
      int top = d.top();
      for (var e : d.move().entrySet()) {
        int i = TicTacToe.cellOf(e.getKey());
        cells[i].heat(e.getValue(), e.getValue() / max, i == top);
      }
      if (nouls) {
        winBar.set(d.winNow());
        threatBar.set(d.threat());
      }
      boolean optimal = TicTacToe.optimalMoves(before).contains(top);
      playerTally.moves++;
      if (optimal) playerTally.optimal++;
      playerTally.millis.add(d.millis());
      clock.setText(String.format("%.1f s%s · %s", d.millis() / 1000.0,
          player.name().equals("Jev") ? " over the network" : (nouls ? " · three questions, one prefill" : " · one question"),
          optimal ? "a minimax-optimal move" : "not a minimax-optimal move"));
      status.setText(String.format("%s plays %s · %.0f%%", player.name(), TicTacToe.NAMES[top], 100 * d.move().get(TicTacToe.NAMES[top])));
      PauseTransition linger = new PauseTransition(Duration.millis(1400));
      linger.setOnFinished(e -> {
        board = board.play(top, 'O');
        moveNumber++;
        cells[top].mark('O');
        thinking = false;
        moveLanded = true;
        if (pendingShadow != null) showShadow(before);
        updateScore();
        snapshot("move");
        if (!finish()) {
          status.setText(auto.equals("off") ? "Your move." : "X plays itself (" + auto + ").");
          maybeAutoMove();
        }
      });
      linger.play();
    }

    /** Shown only after the player's mark has landed: what the other arm would have played. */
    void showShadow(TicTacToe.Board before) {
      TicTacToe.Decision sd = pendingShadow;
      pendingShadow = null;
      int top = sd.top();
      int played = cells[top].hasMark() ? top : -1;
      boolean agrees = board.at(top) == 'O' && played == top;
      boolean optimal = TicTacToe.optimalMoves(before).contains(top);
      shadowTally.moves++;
      if (optimal) shadowTally.optimal++;
      shadowTally.millis.add(sd.millis());
      if (agrees) {
        playerTally.agreed++;
        shadowTally.agreed++;
      }
      shadowLine.setText(String.format("would play %s · %.0f%% · %s · %s", TicTacToe.NAMES[top],
          100 * sd.move().get(TicTacToe.NAMES[top]),
          shadow.name().equals("Jev") ? String.format("%d ms round trip", sd.millis()) : String.format("%.1f s in-process", sd.millis() / 1000.0),
          agrees ? "agrees" : "differs"));
      if (!agrees) cells[top].shadowMark(shadow.name());
      updateScore();
    }

    void updateScore() {
      fill(scorePlayer, playerTally, shadow != null);
      if (shadow != null) fill(scoreShadow, shadowTally, false);
      scorePlayer[3].setText(String.format("%d / %d / %d", playerTally.games[0], playerTally.games[1], playerTally.games[2]));
      if (shadow != null) scoreShadow[3].setText("shadows");
    }

    static void fill(Label[] row, Tally t, boolean withAgreement) {
      row[0].setText(t.moves == 0 ? "–" : String.format("%d / %d", t.optimal, t.moves));
      row[1].setText(t.millis.isEmpty() ? "–" : String.format("%.2f", t.medianSeconds()));
      row[2].setText(!withAgreement || t.moves == 0 ? "–" : String.format("%d / %d", t.agreed, t.moves));
    }

    boolean finish() {
      char w = board.winner();
      if (w == '.' && !board.full()) return false;
      over = true;
      thinking = false;
      status.setText(w == 'O' ? player.name() + " wins." : w == 'X' ? "X wins." : "Draw.");
      if (w != '.') {
        for (int[] l : TicTacToe.LINES) {
          if (board.at(l[0]) == w && board.at(l[1]) == w && board.at(l[2]) == w) for (int i : l) cells[i].glow();
        }
      }
      playerTally.games[w == 'O' ? 0 : w == '.' ? 1 : 2]++;
      gamesPlayed++;
      updateScore();
      snapshot("end");
      if (!auto.equals("off") && gamesPlayed < gamesToPlay) {
        PauseTransition p = new PauseTransition(Duration.millis(2400));
        p.setOnFinished(e -> startGame());
        p.play();
      } else if (gamesPlayed >= gamesToPlay) {
        PauseTransition p = new PauseTransition(Duration.millis(1800));
        p.setOnFinished(e -> { if (recordDir != null) finishRecording(); else Platform.exit(); });
        p.play();
      }
      return true;
    }

    // ---- widgets -----------------------------------------------------------------------------

    /** One cell: a heat square and a probability under the mark; the mark draws itself in. */
    class Cell extends StackPane {
      final int index;
      final Rectangle heat = new Rectangle(CELL - 30, CELL - 30, HEAT);
      final Rectangle ring = new Rectangle(CELL - 24, CELL - 24, Color.TRANSPARENT);
      final Label prob = label("", 20, TEXT, FontWeight.SEMI_BOLD);
      final Group mark = new Group();
      final Group shadowMark = new Group();
      Timeline pulsing;

      Cell(int index) {
        this.index = index;
        setPrefSize(CELL, CELL);
        setMinSize(CELL, CELL);
        setMaxSize(CELL, CELL);
        heat.setArcWidth(22);
        heat.setArcHeight(22);
        heat.setOpacity(0);
        ring.setArcWidth(26);
        ring.setArcHeight(26);
        ring.setStrokeWidth(3);
        ring.setStroke(Color.TRANSPARENT);
        getChildren().addAll(heat, ring, prob, mark, shadowMark);
        setOnMouseClicked(e -> humanMove(index));
        setCursor(Cursor.HAND);
      }

      boolean hasMark() { return !mark.getChildren().isEmpty(); }

      void reset() {
        mark.getChildren().clear();
        clearHeat();
      }

      void clearHeat() {
        pulse(false);
        heat.setOpacity(0);
        ring.setStroke(Color.TRANSPARENT);
        prob.setText("");
        shadowMark.getChildren().clear();
      }

      void heat(double p, double relative, boolean top) {
        animate(heat.opacityProperty(), 0.10 + 0.75 * relative, 450);
        prob.setText(String.format("%.0f%%", 100 * p));
        prob.setTextFill(relative > 0.6 ? BG : TEXT);
        ring.setStroke(top ? PICK : Color.TRANSPARENT);
      }

      void pulse(boolean on) {
        if (on) {
          if (pulsing == null) {
            pulsing = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(heat.opacityProperty(), 0.04)),
                new KeyFrame(Duration.millis(750), new KeyValue(heat.opacityProperty(), 0.22)));
            pulsing.setAutoReverse(true);
            pulsing.setCycleCount(Timeline.INDEFINITE);
          }
          pulsing.play();
        } else if (pulsing != null) {
          pulsing.stop();
          heat.setOpacity(0);
        }
      }

      void mark(char m) {
        pulse(false);
        prob.setText("");
        ring.setStroke(Color.TRANSPARENT);
        animate(heat.opacityProperty(), 0, 350);
        double s = CELL * 0.26;
        if (m == 'X') {
          Line a = new Line(-s, -s, s, s);
          Line b = new Line(-s, s, s, -s);
          style(a, X_COLOR);
          style(b, X_COLOR);
          mark.getChildren().setAll(a, b);
          draw(a, 2 * s * Math.sqrt(2), 0, 220);
          draw(b, 2 * s * Math.sqrt(2), 200, 220);
        } else {
          Circle c = new Circle(0, 0, s);
          c.setFill(Color.TRANSPARENT);
          style(c, O_COLOR);
          c.setRotate(-90);
          mark.getChildren().setAll(c);
          draw(c, 2 * Math.PI * s, 0, 420);
        }
      }

      /** A dashed ring with the other arm's name: "this is where it would have played". */
      void shadowMark(String who) {
        Circle c = new Circle(0, 0, CELL * 0.26);
        c.setFill(Color.TRANSPARENT);
        c.setStroke(SHADOW);
        c.setStrokeWidth(3);
        c.getStrokeDashArray().setAll(9.0, 7.0);
        Label l = label(who, 11, SHADOW, FontWeight.BOLD);
        l.setTranslateY(CELL * 0.26 + 10);
        StackPane p = new StackPane(c, l);
        p.setOpacity(0);
        shadowMark.getChildren().setAll(p);
        animate(p.opacityProperty(), 1, 300);
      }

      void glow() {
        for (Node n : mark.getChildren()) n.setEffect(new Glow(0.9));
      }
    }

    static void style(Shape s, Color c) {
      s.setStroke(c);
      s.setStrokeWidth(STROKE);
      s.setStrokeLineCap(StrokeLineCap.ROUND);
    }

    /** Reveals a stroke by sliding its dash offset from the full length to zero. */
    static void draw(Shape s, double length, double delayMs, double durationMs) {
      s.getStrokeDashArray().setAll(length, length);
      s.setStrokeDashOffset(length);
      new Timeline(
          new KeyFrame(Duration.millis(delayMs), new KeyValue(s.strokeDashOffsetProperty(), length)),
          new KeyFrame(Duration.millis(delayMs + durationMs), new KeyValue(s.strokeDashOffsetProperty(), 0, Interpolator.EASE_OUT)))
          .play();
    }

    /** A thin probability bar with its caption and value. */
    class Bar extends VBox {
      static final double WIDTH = 280;
      final Rectangle fill = new Rectangle(0, 8);
      final Label value;

      Bar(String caption, Color color) {
        super(4);
        value = label("", 12, MUTED, FontWeight.NORMAL);
        HBox head = new HBox(label(caption, 12, MUTED, FontWeight.NORMAL), spacerH(), value);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPrefWidth(WIDTH);
        head.setMaxWidth(WIDTH);
        Rectangle track = new Rectangle(WIDTH, 8, TRACK);
        track.setArcWidth(8);
        track.setArcHeight(8);
        fill.setFill(color);
        fill.setArcWidth(8);
        fill.setArcHeight(8);
        StackPane bar = new StackPane(track, fill);
        bar.setMaxWidth(WIDTH);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        getChildren().addAll(head, bar);
        setAlignment(Pos.CENTER_LEFT);
      }

      void set(double p) {
        animate(fill.widthProperty(), WIDTH * p, 500);
        value.setText(p == 0 ? "" : String.format("%s %.2f", p >= 0.5 ? "yes" : "no", p));
      }
    }

    // ---- recording ---------------------------------------------------------------------------

    /** Captures the scene twenty times a second into numbered PNG frames, off the FX thread. */
    void startRecording() {
      recordDir.mkdirs();
      frameWriter = Executors.newSingleThreadExecutor(r -> daemon(r, "frames"));
      frameImage = new WritableImage((int) scene.getWidth(), (int) scene.getHeight());
      recorder = new AnimationTimer() {
        @Override
        public void handle(long now) {
          if (now - lastFrameNs < 50_000_000L) return;
          lastFrameNs = now;
          scene.snapshot(frameImage);
          int w = (int) frameImage.getWidth(), h = (int) frameImage.getHeight();
          int[] argb = new int[w * h];
          frameImage.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
          int n = ++frameCount;
          frameWriter.submit(() -> {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            out.setRGB(0, 0, w, h, argb, 0, w);
            try {
              ImageIO.write(out, "png", new File(recordDir, String.format("frame-%05d.png", n)));
            } catch (Exception e) {
              System.err.println("frame " + n + " failed: " + e);
            }
          });
        }
      };
      recorder.start();
    }

    /** Stops capturing, waits for the frames, then assembles demo.mp4 and demo.gif with ffmpeg if present. */
    void finishRecording() {
      if (recorder != null) recorder.stop();
      status.setText("Writing the recording…");
      daemon(() -> {
        try {
          frameWriter.shutdown();
          frameWriter.awaitTermination(5, TimeUnit.MINUTES);
          System.out.printf("%d frames in %s%n", frameCount, recordDir.getAbsolutePath());
          String frames = new File(recordDir, "frame-%05d.png").getPath();
          List<List<String>> commands = List.of(
              List.of("ffmpeg", "-y", "-loglevel", "error", "-framerate", "20", "-i", frames,
                  "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", "-movflags", "+faststart", new File(recordDir, "demo.mp4").getPath()),
              List.of("ffmpeg", "-y", "-loglevel", "error", "-framerate", "20", "-i", frames,
                  "-vf", "fps=12,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=3",
                  new File(recordDir, "demo.gif").getPath()));
          for (List<String> cmd : commands) {
            try {
              Process p = new ProcessBuilder(cmd).inheritIO().start();
              int code = p.waitFor();
              System.out.println((code == 0 ? "wrote " : "ffmpeg failed (" + code + ") for ") + cmd.get(cmd.size() - 1));
            } catch (java.io.IOException notInstalled) {
              System.out.println("ffmpeg not found; assemble the frames yourself: " + String.join(" ", cmd));
              break;
            }
          }
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        Platform.exit();
      }, "recording").start();
    }

    // ---- helpers -----------------------------------------------------------------------------

    static void animate(DoubleProperty prop, double to, double ms) {
      new Timeline(new KeyFrame(Duration.millis(ms), new KeyValue(prop, to, Interpolator.EASE_OUT))).play();
    }

    static Label label(String s, double size, Color c, FontWeight w) {
      Label l = new Label(s);
      l.setFont(Font.font("System", w, size));
      l.setTextFill(c);
      return l;
    }

    static Button button(String caption) {
      Button b = new Button(caption);
      b.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
      b.setStyle("-fx-background-color: #2a3040; -fx-text-fill: #e6e9ef; -fx-background-radius: 8; -fx-padding: 6 14 6 14; -fx-cursor: hand;");
      return b;
    }

    static Region spacer(double h) {
      Region r = new Region();
      r.setPrefHeight(h);
      return r;
    }

    static Region spacerH() {
      Region r = new Region();
      HBox.setHgrow(r, Priority.ALWAYS);
      return r;
    }

    static Thread daemon(Runnable r, String name) {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    }

    void snapshot(String kind) {
      if (snapshotDir == null) return;
      int n = moveNumber;
      int g = gamesPlayed + (kind.equals("end") ? 0 : 1);
      PauseTransition p = new PauseTransition(Duration.millis(700));
      p.setOnFinished(e -> writeSnapshot(String.format("game%d-%s-%d.png", g, kind, n)));
      p.play();
    }

    void writeSnapshot(String name) {
      try {
        snapshotDir.mkdirs();
        SnapshotParameters params = new SnapshotParameters();   // stills at 2x for print
        params.setTransform(Transform.scale(2, 2));
        WritableImage img = scene.getRoot().snapshot(params, null);
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int[] argb = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(out, "png", new File(snapshotDir, name));
      } catch (Exception e) {
        System.err.println("snapshot failed: " + e);
      }
    }

    void parseArgs(List<String> args) {
      for (int i = 0; i < args.size(); i++) {
        switch (args.get(i)) {
          case "--rep" -> rep = TicTacToe.Rep.valueOf(args.get(++i));
          case "--auto" -> auto = args.get(++i);
          case "--games" -> gamesToPlay = Integer.parseInt(args.get(++i));
          case "--snapshot" -> snapshotDir = new File(args.get(++i));
          case "--record" -> recordDir = new File(args.get(++i));
          case "--jev" -> useJev = true;
          case "--player" -> playerName = args.get(++i).toLowerCase();
          case "--nouls" -> nouls = true;
          case "--seed" -> seed = Long.parseLong(args.get(++i));
          default -> System.err.println("ignored argument: " + args.get(i));
        }
      }
      if (playerName.equals("jev")) useJev = true;
    }
  }
}
