package io.scalac

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors


object AkkaQuickstartTyped extends App {

  case class PrintCommand(s: String, onBehalfOf: ActorRef[GreeterProtocol])

  val printerB = Behaviors.receiveMessage[PrintCommand] { msg =>
    println("Printer: " + msg.s + " on behalf of " + msg.onBehalfOf)
    Behaviors.same
  }



  sealed trait GreeterProtocol
  final case class WhoToGreet(who: String) extends GreeterProtocol
  case object Greet extends GreeterProtocol

  def greeterB(state: String, printer: ActorRef[PrintCommand]): Behavior[GreeterProtocol] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case WhoToGreet(w) =>
        greeterB(state = w, printer)
      case Greet =>
        printer ! PrintCommand(state, ctx.self)
        Behaviors.same[GreeterProtocol]
    }
  }



  val mainB = Behaviors.setup[String] { ctx =>
    val printer = ctx.spawn(printerB, "printer")

    Behaviors.receive { (_, msg) =>
      val greeter = ctx.spawn(greeterB("", printer), "greeter" + msg)
      greeter ! WhoToGreet(msg)
      greeter ! Greet
      //NOTE: this will not kill the actor!
      Behaviors.same
    }
  }

  val sys = ActorSystem(mainB, "hello")
  sys ! "foo"
  sys ! "bar"
  sys ! "baz"
}