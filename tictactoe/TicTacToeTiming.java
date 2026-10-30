//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx8g
//DEPS org.modeljars.composite:harriet:0.1.69
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4
//SOURCES TicTacToe.java

import com.integrallis.models.decisions.AnswerSpace;
import java.util.List;
import java.util.Map;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.composite.harriet.Harriet;

/**
 * What one move costs, and where: the move question alone on a fresh board, the three questions in
 * one call on a fresh board, and two separate calls on one board (the second reuses the prefix),
 * for each board text. A warm-up call on an unmeasured board comes first.
 */
class TicTacToeTiming {
  /** Six boards with O to move and six empty cells each; every measurement gets one the runtime has not seen. */
  static final List<String> BOARDS = List.of("X.O.X....", "..X.OX...", "X...O..X.", ".X..O...X", "O.X..X...", "...XO.X..");

  public static void main(String[] args) {
    try (ModelJarDecisionRuntime rt = Harriet.open()) {
      System.out.printf("backend %s%n", rt.backend().name());
      // warm the kernels once on a board that is not measured
      Harriet.decide(rt, TicTacToe.moveQuestion(new TicTacToe.Board("X...O...X")), TicTacToe.state(new TicTacToe.Board("X...O...X"), TicTacToe.Rep.GRID));
      int next = 0;
      for (TicTacToe.Rep rep : TicTacToe.Rep.values()) {
        TicTacToe.Board b1 = new TicTacToe.Board(BOARDS.get(next++ % BOARDS.size()));
        TicTacToe.Board b2 = new TicTacToe.Board(BOARDS.get(next++ % BOARDS.size()));
        TicTacToe.Board b3 = new TicTacToe.Board(BOARDS.get(next++ % BOARDS.size()));
        long t = System.nanoTime();
        Harriet.decide(rt, TicTacToe.moveQuestion(b1), TicTacToe.state(b1, rep));
        long alone = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        Harriet.systemOne(rt, TicTacToe.questions(b2), TicTacToe.state(b2, rep));
        long all3 = (System.nanoTime() - t) / 1_000_000;
        String state3 = TicTacToe.state(b3, rep);
        t = System.nanoTime();
        Harriet.decide(rt, TicTacToe.moveQuestion(b3), state3);
        long first = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        Harriet.decide(rt, TicTacToe.WIN_NOW, state3);
        long second = (System.nanoTime() - t) / 1_000_000;
        System.out.printf("%-10s state %4d chars | move alone, fresh board %6d ms | three questions, fresh board %6d ms | same board twice: move %6d ms then noul %6d ms%n",
            rep, state3.length(), alone, all3, first, second);
      }
    }
  }
}
