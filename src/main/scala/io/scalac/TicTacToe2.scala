package io.scalac

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.scaladsl.Behaviors.Receive
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Random

// goals:
// 1) player can only submit moves as himself
// 2) player cannot submit invalid moves
// 3) system should honor move order
// 4) we can use actor code from non actor code
// 5) should support request-reply from the client code

object TicTacToe2 extends App {
  println("Starting TicTacToe2")

  //primitives and utils
  sealed trait Pos {
    def asInt: Int
  }
  case object Zero extends Pos {
    def asInt: Int = 0
  }
  case object One extends Pos {
    def asInt: Int = 1
  }
  case object Two extends Pos {
    def asInt: Int = 2
  }

  sealed trait Player
  case object XPlayer extends Player
  case object CirclePlayer extends Player

  type Board = Seq[Seq[Option[Player]]]

  def randomMove() = {
    Random.nextInt(3) match {
      case 0 =>
        Zero
      case 1 =>
        One
      case _ =>
        Two
    }
  }

  // player proxies
  case class YourTurnX(b: Board, replyTo: ActorRef[MoveX])
  val playerXProxy = Behaviors.receiveMessage[YourTurnX]{ msg =>
    msg.replyTo ! MoveX(randomMove(), randomMove())
    Behaviors.same
  }
  case class YourTurnCircle(b: Board, replyTo: ActorRef[MoveCircle])
  val playerCircleProxy = Behaviors.receiveMessage[YourTurnCircle]{ msg =>
    msg.replyTo ! MoveCircle(randomMove(), randomMove())
    Behaviors.same
  }

  case class MoveX(x: Pos, y: Pos)
  case class MoveCircle(x: Pos, y: Pos)

  // translators
  def xTranslator(parent: ActorRef[GenericMove]) = Behaviors.receiveMessage[MoveX]{ msg =>
    val translated = GenericMove(XPlayer, msg.x, msg.y)
    parent ! translated
    Behaviors.stopped // usable one time
  }

  def circleTranslator(parent: ActorRef[GenericMove]) = Behaviors.receiveMessage[MoveCircle]{ msg =>
    val translated = GenericMove(CirclePlayer, msg.x, msg.y)
    parent ! translated
    Behaviors.stopped // usable one time
  }

  // manager
  sealed trait MoveCommand
  case class GenericMove(p: Player, x: Pos, y: Pos) extends MoveCommand
  sealed trait MoveResult extends MoveCommand {
    def currentBoard: Board
    def currentlyPlaced: Int = currentBoard.flatten.flatten.size
  }
  case class ConfirmedMove(currentBoard: Board) extends MoveResult
  case class RejectedMove(currentBoard: Board) extends MoveResult

  trait Winner
  case object XWon extends Winner
  case object CircleWon extends Winner
  case object Draw extends Winner

  def getWinner(currentBoard: Board): Option[Winner] = {
    val check1 = for {
      c <- Seq(Zero, One, Two)
      if currentBoard(Zero.asInt)(c.asInt) == currentBoard(One.asInt)(c.asInt)
      if currentBoard(Zero.asInt)(c.asInt) == currentBoard(Two.asInt)(c.asInt)
    } yield {
      currentBoard(Zero.asInt)(c.asInt)
    }

    val check2 = for {
      c <- Seq(Zero, One, Two)
      if currentBoard(c.asInt)(Zero.asInt) == currentBoard(c.asInt)(One.asInt)
      if currentBoard(c.asInt)(Zero.asInt) == currentBoard(c.asInt)(Two.asInt)
    } yield {
      currentBoard(c.asInt)(Zero.asInt)
    }

    // check across
    val check3 = (for {
      a <- currentBoard(Zero.asInt)(Zero.asInt)
      b <- currentBoard(One.asInt)(One.asInt)
      c <- currentBoard(Two.asInt)(Two.asInt)
      if a == b && b == c
    } yield {
      currentBoard(Zero.asInt)(Zero.asInt)
    }).toSeq

    // check across-2
    val check4 = (for {
      a <- currentBoard(Two.asInt)(Zero.asInt)
      b <- currentBoard(One.asInt)(One.asInt)
      c <- currentBoard(Zero.asInt)(Two.asInt)
      if a == b && b == c
    } yield {
      currentBoard(Two.asInt)(Zero.asInt)
    }).toSeq


    val piecesPlaced = currentBoard.flatten.flatten.size
    val winningPiece = (check1 ++ check2 ++ check3 ++ check4).flatten.headOption
    winningPiece match {
      case None if piecesPlaced < 9 =>
        None // the game is on
      case None if piecesPlaced >= 9 =>
        Option(Draw)
      case Some(XPlayer) =>
        Option(XWon)
      case Some(CirclePlayer) =>
        Option(CircleWon)
    }
  }

  def managerBehaviour(reportResultTo: ActorRef[Winner],
                       board: ActorRef[UpdateBoard],
                       x: ActorRef[YourTurnX],
                       circle: ActorRef[YourTurnCircle],
                       currentMove: Player): Receive[MoveCommand] = Behaviors.receive[MoveCommand] {
    case (ctx, msg) =>
      msg match {
        case m: MoveResult =>
          getWinner(m.currentBoard) match {
            case Some(Draw) =>
              println("Nobody won")
              reportResultTo ! Draw
              Behaviors.stopped
            case Some(w) =>
              println(s"${w} is the winner!")
              reportResultTo ! w
              Behaviors.stopped
            case None => // this means we can carry on with the game
              m match {
                case evt: ConfirmedMove if currentMove == XPlayer => // x has successfully placed a piece
                  askForCircleMove(circle, ctx, evt)
                  managerBehaviour(reportResultTo, board, x, circle, CirclePlayer)

                case evt: ConfirmedMove if currentMove == CirclePlayer => // o has successfully placed a piece
                  askForXMove(x, ctx, evt)
                  managerBehaviour(reportResultTo, board, x, circle, XPlayer)

                case evt: RejectedMove if currentMove == XPlayer => // x could NOT place a piece
                  askForXMove(x, ctx, evt)
                  managerBehaviour(reportResultTo, board, x, circle, XPlayer)

                case evt: RejectedMove if currentMove == CirclePlayer => // o could NOT place a piece
                  askForCircleMove(circle, ctx, evt)
                  managerBehaviour(reportResultTo, board, x, circle, CirclePlayer)
              }
          }

        case GenericMove(p, x, y) =>
          board ! UpdateBoard(p, x, y, ctx.self)
          Behaviors.same
      }
  }

  private def askForXMove(x: ActorRef[YourTurnX], ctx: ActorContext[MoveCommand], evt: MoveResult) = {
    val translator = ctx.spawn(xTranslator(ctx.self), "x_" + System.currentTimeMillis() + "_" + Random.nextInt())
    x ! YourTurnX(evt.currentBoard, translator)
  }

  private def askForCircleMove(circle: ActorRef[YourTurnCircle], ctx: ActorContext[MoveCommand], evt: MoveResult) = {
    val translator = ctx.spawn(circleTranslator(ctx.self), "o_" + System.currentTimeMillis() + "_" + Random.nextInt())
    circle ! YourTurnCircle(evt.currentBoard, translator)
  }

  // board
  def updateTheBoard(oldBoard: Board, x: Pos, y: Pos, p: Player): Either[Throwable, Board] = {
    oldBoard(x.asInt)(y.asInt) match {
      case None =>
        val updatedRow = oldBoard(x.asInt).patch(y.asInt, Seq(Option(p)), 1)
        val updatedBoard = oldBoard.patch(x.asInt, Seq(updatedRow), 1)
        Right(updatedBoard)
      case Some(takenBy) =>
        Left(new Exception(s"Collision with ${takenBy}"))
    }
  }

  def boardAsString(board: Board): String = board.map(_.map{
    case Some(XPlayer) =>      "x"
    case Some(CirclePlayer) => "o"
    case None =>               " "
  }.mkString("|")).mkString("\n")

  def emptyBoard() =  Seq.fill(3)(Seq.fill(3)(None))

  case class UpdateBoard(p: Player, x: Pos, y: Pos, replyTo: ActorRef[MoveResult])

  def boardUpdateBehaviour(board: Board): Behavior[UpdateBoard] = Behaviors.receiveMessage { msg =>
    updateTheBoard(board, msg.x, msg.y, msg.p) match {
      case Left(_) =>
        msg.replyTo ! RejectedMove(board)
        boardUpdateBehaviour(board)
      case Right(updated) =>
        println(s"Updating the Board ${msg}\n${boardAsString(updated)}")
        msg.replyTo ! ConfirmedMove(board)
        boardUpdateBehaviour(updated)
    }
  }



  /// DEMO starts here
  case class StartDemo(replyTo: ActorRef[Winner])

  val mainBehaviour = Behaviors.setup[StartDemo] { ctx =>
    val initialBoardState = emptyBoard()
    val board = ctx.spawn(boardUpdateBehaviour(initialBoardState), "game-board")
    val x = ctx.spawn(playerXProxy, "x-proxy")
    val circle = ctx.spawn(playerCircleProxy, "circle-proxy")
    val whoWillGoFirst = XPlayer

    Behaviors.receiveMessage { msg =>
      val manager = ctx.spawn(managerBehaviour(msg.replyTo, board, x, circle, whoWillGoFirst), "game-manager")

      manager ! RejectedMove(initialBoardState) // this will kick start the process
      Behaviors.empty
    }
  }


  trait External {
    def startTheGame(): Future[Winner]
  }
  case class ExternalAkkaTyped(system: ActorSystem[StartDemo]) extends External {
    override def startTheGame(): Future[Winner] = {
      import akka.actor.typed.scaladsl.AskPattern._

      implicit val timeout: Timeout = 3.seconds
      implicit val scheduler = system.scheduler
      implicit val ec = system.executionContext

      system ? (ref => StartDemo(ref)) // this is untyped!
    }
  }

  val system = ActorSystem(mainBehaviour, "hello")

  implicit val ec = system.executionContext

  ExternalAkkaTyped(system).startTheGame().onComplete { r =>
    println(s"Completed the game with ${r}")
    system.terminate()
  }
}
