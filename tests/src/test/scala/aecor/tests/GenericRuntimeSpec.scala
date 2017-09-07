package aecor.tests

import aecor.data.{ Behavior, EventsourcedBehavior }
import aecor.effect.monix._
import aecor.runtime.akkageneric.GenericAkkaRuntime
import aecor.testkit.StateRuntime
import aecor.tests.e2e._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.cats._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._

object GenericRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        akka.persistence.journal.plugin=akka.persistence.journal.inmem
        akka.persistence.snapshot-store.plugin=akka.persistence.no-snapshot-store
        aecor.generic-akka-runtime.idle-timeout = 1s
        cluster.seed-nodes = ["akka://test@127.0.0.1:51000"]
     """).withFallback(ConfigFactory.load())
}

class GenericRuntimeSpec
    extends TestKit(ActorSystem("test", GenericRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val behavior: Behavior[Task, CounterOp] = Behavior.fromState(
    Vector.empty[CounterEvent],
    StateRuntime.shared(EventsourcedBehavior(CounterOpHandler[Task], CounterState.folder))
  )

  val startRuntime =
    GenericAkkaRuntime[Task](system).start("Counter", (_: CounterId) => behavior)

  test("Runtime should work") {
    val program = for {
      runtime <- startRuntime
      _ <- runtime("1")(CounterOp.Increment)
      _ <- runtime("2")(CounterOp.Increment)
      _ <- runtime("1")(CounterOp.Decrement)
      first <- runtime("1")(CounterOp.GetValue)
      second <- runtime("2")(CounterOp.GetValue)
      secondAfterPassivation <- runtime("2")(CounterOp.GetValue).delayExecution(2.seconds)
    } yield (first, second, secondAfterPassivation)

    val (first, second, afterPassivation) = program.runAsync.futureValue
    first shouldBe 0L
    second shouldBe 1L
    afterPassivation shouldBe 0
  }
}
