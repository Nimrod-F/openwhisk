/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool.test

import java.time.Instant

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, ActorSystem, FSM}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import common.{LoggedFunction, StreamLogging, SynchronizedLoggedFunction, WhiskProperties}
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, ActivationMessage}
import org.apache.openwhisk.core.containerpool.WarmingData
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.invoker.InvokerReactive

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

@RunWith(classOf[JUnitRunner])
class ContainerProxyTests
    extends TestKit(ActorSystem("ContainerProxys"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with StreamLogging {

  override def afterAll = TestKit.shutdownActorSystem(system)

  val timeout = 5.seconds
  val pauseGrace = timeout + 1.minute
  val log = logging
  val defaultUserMemory: ByteSize = 1024.MB

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val memoryLimit = 256.MB

  val invocationNamespace = EntityName("invocationSpace")
  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
  val testConcurrencyLimit = if (concurrencyEnabled) ConcurrencyLimit(2) else ConcurrencyLimit(1)
  val concurrentAction = ExecutableWhiskAction(
    EntityPath("actionSpace"),
    EntityName("actionName"),
    exec,
    limits = ActionLimits(concurrency = testConcurrencyLimit))

  // create a transaction id to set the start time and control queue time
  val messageTransId = TransactionId(TransactionId.testing.meta.id)

  val initInterval = {
    val now = messageTransId.meta.start.plusMillis(50) // this is the queue time for cold start
    Interval(now, now.plusMillis(100))
  }

  val runInterval = {
    val now = initInterval.end.plusMillis(75) // delay between init and run
    Interval(now, now.plusMillis(200))
  }

  val errorInterval = {
    val now = initInterval.end.plusMillis(75) // delay between init and run
    Interval(now, now.plusMillis(150))
  }

  val uuid = UUID()

  val activationArguments = JsObject("ENV_VAR" -> "env".toJson, "param" -> "param".toJson)

  val message = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    action.rev,
    Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = Some(activationArguments),
    initArgs = Set("ENV_VAR"))

  /*
   * Helpers for assertions and actor lifecycles
   */
  /** Imitates a StateTimeout in the FSM */
  def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

  /** Registers the transition callback and expects the first message */
  def registerCallback(c: ActorRef) = {
    c ! SubscribeTransitionCallBack(testActor)
    expectMsg(CurrentState(c, Uninitialized))
  }

  /** Pre-warms the given state-machine, assumes good cases */
  def preWarm(machine: ActorRef) = {
    machine ! Start(exec, memoryLimit)
    expectMsg(Transition(machine, Uninitialized, Starting))
    expectPreWarmed(exec.kind)
    expectMsg(Transition(machine, Starting, Started))
  }

  /** Run the common action on the state-machine, assumes good cases */
  def run(machine: ActorRef, currentState: ContainerState) = {
    machine ! Run(action, message)
    expectMsg(Transition(machine, currentState, Running))
    expectWarmed(invocationNamespace.name, action)
    expectMsg(Transition(machine, Running, Ready))
  }

  /** Expect a NeedWork message with prewarmed data */
  def expectPreWarmed(kind: String) = expectMsgPF() {
    case NeedWork(PreWarmedData(_, kind, memoryLimit, _)) => true
  }

  /** Expect a NeedWork message with warmed data */
  def expectWarmed(namespace: String, action: ExecutableWhiskAction) = {
    val test = EntityName(namespace)
    expectMsgPF() {
      case a @ NeedWork(WarmedData(_, `test`, `action`, _, _)) => //matched, otherwise will fail
    }
  }

  /** Expect the container to pause successfully */
  def expectPause(machine: ActorRef) = {
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))
  }

  trait LoggedAcker extends InvokerReactive.ActiveAck {
    def calls =
      mutable.Buffer[(TransactionId, WhiskActivation, Boolean, ControllerInstanceId, UUID, AcknowledegmentMessage)]()

    def verifyAnnotations(activation: WhiskActivation, a: ExecutableWhiskAction) = {
      activation.annotations.get("limits") shouldBe Some(a.limits.toJson)
      activation.annotations.get("path") shouldBe Some(a.fullyQualifiedName(false).toString.toJson)
      activation.annotations.get("kind") shouldBe Some(a.exec.kind.toJson)
    }
  }

  /** Creates an inspectable version of the ack method, which records all calls in a buffer */
  def createAcker(a: ExecutableWhiskAction = action) = new LoggedAcker {
    val acker = LoggedFunction {
      (_: TransactionId,
       activation: WhiskActivation,
       _: Boolean,
       _: ControllerInstanceId,
       _: UUID,
       _: AcknowledegmentMessage) =>
        Future.successful(())
    }

    override def calls = acker.calls

    override def apply(tid: TransactionId,
                       activation: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      verifyAnnotations(activation, a)
      acker(tid, activation, blockingInvoke, controllerInstance, userId, acknowledegment)
    }
  }

  /** Creates an synchronized inspectable version of the ack method, which records all calls in a buffer */
  def createSyncAcker(a: ExecutableWhiskAction = action) = new LoggedAcker {
    val acker = SynchronizedLoggedFunction {
      (_: TransactionId,
       activation: WhiskActivation,
       _: Boolean,
       _: ControllerInstanceId,
       _: UUID,
       _: AcknowledegmentMessage) =>
        Future.successful(())
    }

    override def calls = acker.calls

    override def apply(tid: TransactionId,
                       activation: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      verifyAnnotations(activation, a)
      acker(tid, activation, blockingInvoke, controllerInstance, userId, acknowledegment)
    }
  }

  /** Creates an inspectable factory */
  def createFactory(response: Future[Container]) = LoggedFunction {
    (_: TransactionId, _: String, _: ImageName, _: Boolean, _: ByteSize, _: Int, _: Option[ExecutableWhiskAction]) =>
      response
  }

  def createCollector(response: Future[ActivationLogs] = Future.successful(ActivationLogs(Vector.empty))) =
    LoggedFunction {
      (transid: TransactionId,
       user: Identity,
       activation: WhiskActivation,
       container: Container,
       action: ExecutableWhiskAction) =>
        response
    }

  def createStore = LoggedFunction { (transid: TransactionId, activation: WhiskActivation, context: UserContext) =>
    Future.successful(())
  }
  def createSyncStore = SynchronizedLoggedFunction {
    (transid: TransactionId, activation: WhiskActivation, context: UserContext) =>
      Future.successful(())
  }
  val poolConfig = ContainerPoolConfig(2.MB, 0.5, false)
  val filterEnvVar = (k: String) => Character.isUpperCase(k.charAt(0))

  behavior of "ContainerProxy"

  it should "partition activation arguments into environment variables and main arguments" in {
    ContainerProxy.partitionArguments(None, Set.empty) should be(Map.empty, JsObject.empty)
    ContainerProxy.partitionArguments(Some(JsObject.empty), Set("a")) should be(Map.empty, JsObject.empty)

    val content = JsObject("a" -> "A".toJson, "b" -> "B".toJson, "C" -> "c".toJson, "D" -> "d".toJson)
    val (env, args) = ContainerProxy.partitionArguments(Some(content), Set("C", "D"))
    env should be {
      content.fields.filter(k => filterEnvVar(k._1))
    }

    args should be {
      JsObject(content.fields.filterNot(k => filterEnvVar(k._1)))
    }
  }

  /*
   * SUCCESSFUL CASES
   */
  it should "create a container given a Start message" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val store = createStore
    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            createAcker(),
            store,
            createCollector(),
            InvokerInstanceId(0, Some("myname"), userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    factory.calls should have size 1
    val (tid, name, _, _, memory, cpuShares, _) = factory.calls(0)
    tid shouldBe TransactionId.invokerWarmup
    name should fullyMatch regex """wskmyname\d+_\d+_prewarm_actionKind"""
    memory shouldBe memoryLimit
  }

  it should "run a container which has been started before, write an active ack, write to the store, pause and remove the container" in within(
    timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)

    preWarm(machine)
    run(machine, Started)

    // Timeout causes the container to pause
    timeout(machine)
    expectPause(machine)

    // Another pause causes the container to be removed
    timeout(machine)
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Paused, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  it should "run an action and continue with a next run without pausing the container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    run(machine, Started)
    // Note that there are no intermediate state changes
    run(machine, Ready)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val (initRunActivation, runOnlyActivation) = {
        // false is sorted before true
        val sorted = acker.calls.sortBy(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isEmpty)
        (sorted.head._2, sorted(1)._2)
      }

      initRunActivation.annotations.get(WhiskActivation.initTimeAnnotation) should not be empty
      initRunActivation.duration shouldBe Some((initInterval.duration + runInterval.duration).toMillis)
      initRunActivation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      initRunActivation.annotations
        .get(WhiskActivation.waitTimeAnnotation)
        .get
        .convertTo[Int] shouldBe
        Interval(message.transid.meta.start, initInterval.start).duration.toMillis

      runOnlyActivation.duration shouldBe Some(runInterval.duration.toMillis)
      runOnlyActivation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      runOnlyActivation.annotations.get(WhiskActivation.waitTimeAnnotation).get.convertTo[Int] shouldBe {
        Interval(message.transid.meta.start, runInterval.start).duration.toMillis
      }
    }
  }

  it should "run an action after pausing the container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    run(machine, Started)
    timeout(machine)
    expectPause(machine)
    run(machine, Paused)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }

  it should "successfully run on an uninitialized container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      acker.calls should have size 1
      store.calls should have size 1
      acker
        .calls(0)
        ._2
        .annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }

  it should "not collect logs if the log-limit is set to 0" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(noLogsAction)
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)

    machine ! Run(noLogsAction, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectWarmed(invocationNamespace.name, noLogsAction)
    expectMsg(Transition(machine, Running, Ready))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 0
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  //This tests concurrency from the ContainerPool perspective - where multiple Run messages may be sent to ContainerProxy
  //without waiting for the completion of the previous Run message (signaled by NeedWork message)
  //Multiple messages can only be handled after Warming.
  it should "stay in Running state if others are still running" in within(timeout) {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val initPromise = Promise[Interval]()
    val runPromises = Seq(
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(Some(initPromise), runPromises)
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      (_: TransactionId, _: Identity, _: WhiskActivation, _: Container, _: ExecutableWhiskAction) => {
        container.logs(0.MB, false)(TransactionId.testing)
        Future.successful(ActivationLogs())
      }

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.success(initInterval)

    //complete the first run
    runPromises(0).success(runInterval, ActivationResponse.success())
    expectWarmed(invocationNamespace.name, concurrentAction) //when first completes (count is 0 since stashed not counted)
    expectMsg(Transition(machine, Running, Ready)) //wait for first to complete to skip the delay step that can only reliably be tested in single threaded
    expectMsg(Transition(machine, Ready, Running)) //when second starts (after delay...)

    //complete the second run
    runPromises(1).success(runInterval, ActivationResponse.success())
    expectWarmed(invocationNamespace.name, concurrentAction) //when second completes

    //go back to ready after first and second runs are complete
    expectMsg(Transition(machine, Running, Ready))

    machine ! Run(concurrentAction, message) //third in Ready state
    machine ! Run(concurrentAction, message) //fourth in Ready state
    machine ! Run(concurrentAction, message) //fifth in Ready state - will be queued
    machine ! Run(concurrentAction, message) //sixth in Ready state - will be queued

    //third message will go from Ready -> Running -> Ready (after fourth run)
    expectMsg(Transition(machine, Ready, Running))

    //complete the third run (do not request new work yet)
    runPromises(2).success(runInterval, ActivationResponse.success())

    //complete the fourth run -> dequeue the fifth run (do not request new work yet)
    runPromises(3).success(runInterval, ActivationResponse.success())

    //complete the fifth run (request new work, 1 active remain)
    runPromises(4).success(runInterval, ActivationResponse.success())
    expectWarmed(invocationNamespace.name, concurrentAction) //when fifth completes

    //complete the sixth run (request new work 0 active remain)
    runPromises(5).success(runInterval, ActivationResponse.success())

    expectWarmed(invocationNamespace.name, concurrentAction) //when sixth completes

    // back to ready
    expectMsg(Transition(machine, Running, Ready))

    //timeout + pause after getting back to Ready
    timeout(machine)
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 6
      container.atomicLogsCount.get() shouldBe 6
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 0
      acker.calls should have size 6

      store.calls should have size 6

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }

  }

  it should "complete the transaction and reuse the container on a failed run IFF failure was applicationError" in within(
    timeout) {
    val container = new TestContainer {
      override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration, concurrent: Int)(
        implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        atomicRunCount.incrementAndGet()
        //every other run fails
        if (runCount % 2 == 0) {
          Future.successful((runInterval, ActivationResponse.success()))
        } else {
          Future.successful((errorInterval, ActivationResponse.applicationError(("boom"))))
        }
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = timeout))
    registerCallback(machine)
    preWarm(machine)

    //first one will fail
    run(machine, Started)

    // Note that there are no intermediate state changes
    //second one will succeed
    run(machine, Ready)

    //With exception of the error on first run, the assertions should be the same as in
    //         `run an action and continue with a next run without pausing the container`
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 0
      container.destroyCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val (initErrorActivation, runOnlyActivation) = {
        // false is sorted before true
        val sorted = acker.calls.sortBy(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isEmpty)
        (sorted.head._2, sorted(1)._2)
      }

      initErrorActivation.annotations.get(WhiskActivation.initTimeAnnotation) should not be empty
      initErrorActivation.duration shouldBe Some((initInterval.duration + errorInterval.duration).toMillis)
      initErrorActivation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      initErrorActivation.annotations
        .get(WhiskActivation.waitTimeAnnotation)
        .get
        .convertTo[Int] shouldBe
        Interval(message.transid.meta.start, initInterval.start).duration.toMillis

      runOnlyActivation.duration shouldBe Some(runInterval.duration.toMillis)
      runOnlyActivation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      runOnlyActivation.annotations.get(WhiskActivation.waitTimeAnnotation).get.convertTo[Int] shouldBe {
        Interval(message.transid.meta.start, runInterval.start).duration.toMillis
      }
    }

  }

  /*
   * ERROR CASES
   */
  it should "complete the transaction and abort if container creation fails" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.failed(new Exception()))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls should have size 0 // gather no logs
      container.destroyCount shouldBe 0 // no destroying possible as no container could be obtained
      acker.calls should have size 1
      val activation = acker.calls(0)._2
      activation.response should be a 'whiskError
      activation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container on a failed init" in within(timeout) {
    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              concurrent: Int)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        Future.failed(InitializationError(initInterval, ActivationResponse.developerError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0 // should not run the action
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      val activation = acker.calls(0)._2
      activation.response shouldBe ActivationResponse.developerError("boom")
      activation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis

      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container on a failed run IFF failure was containerError" in within(
    timeout) {
    val container = new TestContainer {
      override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration, concurrent: Int)(
        implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        atomicRunCount.incrementAndGet()
        Future.successful((initInterval, ActivationResponse.developerError(("boom"))))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.developerError("boom")
      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container if log reading failed" in {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val partialLogs = Vector("this log line made it", Messages.logFailure)
    val collector =
      createCollector(Future.failed(LogCollectingException(ActivationLogs(partialLogs))))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls should have size 1
      store.calls(0)._2.logs shouldBe ActivationLogs(partialLogs)
    }
  }

  it should "complete the transaction and destroy the container if log reading failed terminally" in {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector(Future.failed(new Exception))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls should have size 1
      store.calls(0)._2.logs shouldBe ActivationLogs(Vector(Messages.logFailure))
    }
  }

  it should "resend the job to the parent if resuming a container fails" in within(timeout) {
    val container = new TestContainer {
      override def resume()(implicit transid: TransactionId) = {
        resumeCount += 1
        Future.failed(new RuntimeException())
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized) // first run an activation
    timeout(machine) // times out Ready state so container suspends
    expectPause(machine)

    val runMessage = Run(action, message)
    machine ! runMessage
    expectMsg(Transition(machine, Paused, Running))
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(runMessage)

    awaitAssert {
      factory.calls should have size 1
      container.runCount shouldBe 1
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      container.destroyCount shouldBe 1
    }
  }

  it should "remove the container if suspend fails" in within(timeout) {
    val container = new TestContainer {
      override def suspend()(implicit transid: TransactionId) = {
        suspendCount += 1
        Future.failed(new RuntimeException())
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)
    timeout(machine) // times out Ready state so container suspends
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Pausing, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.suspendCount shouldBe 1
      container.destroyCount shouldBe 1
    }
  }

  /*
   * DELAYED DELETION CASES
   */
  // this test represents a Remove message whenever you are in the "Running" state. Therefore, testing
  // a Remove while /init should suffice to guarantee test coverage here.
  it should "delay a deletion message until the transaction is completed successfully" in within(timeout) {
    val initPromise = Promise[Interval]
    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              concurrent: Int)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        initPromise.future
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)

    // Start running the action
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))

    // Schedule the container to be removed
    machine ! Remove

    // Finish /init, note that /run and log-collecting happens nonetheless
    initPromise.success(Interval.zero)
    expectWarmed(invocationNamespace.name, action)
    expectMsg(Transition(machine, Running, Ready))

    // Remove the container after the transaction finished
    expectMsg(ContainerRemoved)
    expectMsg(Transition(machine, Ready, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 0 // skips pausing the container
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  // this tests a Run message in the "Removing" state. The contract between the pool and state-machine
  // is, that only one Run is to be sent until a "NeedWork" comes back. If we sent a NeedWork but no work is
  // there, we might run into the final timeout which will schedule a removal of the container. There is a
  // time window though, in which the pool doesn't know of that decision yet. We handle the collision by
  // sending the Run back to the pool so it can reschedule.
  it should "send back a Run message which got sent before the container decided to remove itself" in within(timeout) {
    val destroyPromise = Promise[Unit]
    val container = new TestContainer {
      override def destroy()(implicit transid: TransactionId): Future[Unit] = {
        destroyCount += 1
        destroyPromise.future
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)
    timeout(machine)
    expectPause(machine)
    timeout(machine)

    // We don't know of this timeout, so we schedule a run.
    machine ! Run(action, message)

    // State-machine shuts down nonetheless.
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Paused, Removing))

    // Pool gets the message again.
    expectMsg(Run(action, message))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  // This tests ensures the user api key is not present in the action context if not requested
  it should "omit api key from action run context" in within(timeout) {
    val container = new TestContainer(apiKeyMustBePresent = false)
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            pauseGrace = pauseGrace))
    registerCallback(machine)

    preWarm(machine)

    val keyFalsyAnnotation = Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
    val actionWithFalsyKeyAnnotation =
      ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec, annotations = keyFalsyAnnotation)

    machine ! Run(actionWithFalsyKeyAnnotation, message)
    expectMsg(Transition(machine, Started, Running))
    expectWarmed(invocationNamespace.name, actionWithFalsyKeyAnnotation)
    expectMsg(Transition(machine, Running, Ready))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }
  it should "reset the lastUse and increment the activationCount on nextRun()" in {
    //NoData/MemoryData/PrewarmedData always reset activation count to 1, and reset lastUse
    val noData = NoData()
    noData.nextRun(Run(action, message)) should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, 1) =>
    }

    val memData = ResourcesData(action.limits.memory.megabytes.MB)
    memData.nextRun(Run(action, message)) should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, 1) =>
    }
    val pwData = PreWarmedData(new TestContainer(), action.exec.kind, action.limits.memory.megabytes.MB)
    pwData.nextRun(Run(action, message)) should matchPattern {
      case WarmingData(pwData.container, message.user.namespace.name, action, _, 1) =>
    }

    //WarmingData, WarmingColdData, and WarmedData increment counts and reset lastUse
    val timeDiffSeconds = 20
    val initialCount = 10
    //WarmingData
    val warmingData = WarmingData(
      pwData.container,
      message.user.namespace.name,
      action,
      Instant.now.minusSeconds(timeDiffSeconds),
      initialCount)
    val nextWarmingData = warmingData.nextRun(Run(action, message))
    val nextCount = warmingData.activeActivationCount + 1
    nextWarmingData should matchPattern {
      case WarmingData(pwData.container, message.user.namespace.name, action, _, nextCount) =>
    }
    warmingData.lastUsed.until(nextWarmingData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong

    //WarmingColdData
    val warmingColdData =
      WarmingColdData(message.user.namespace.name, action, Instant.now.minusSeconds(timeDiffSeconds), initialCount)
    val nextWarmingColdData = warmingColdData.nextRun(Run(action, message))
    nextWarmingColdData should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, newCount) =>
    }
    warmingColdData.lastUsed.until(nextWarmingColdData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong

    //WarmedData
    val warmedData = WarmedData(
      pwData.container,
      message.user.namespace.name,
      action,
      Instant.now.minusSeconds(timeDiffSeconds),
      initialCount)
    val nextWarmedData = warmedData.nextRun(Run(action, message))
    nextWarmedData should matchPattern {
      case WarmedData(pwData.container, message.user.namespace.name, action, _, newCount) =>
    }
    warmedData.lastUsed.until(nextWarmedData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong
  }

  /**
   * Implements all the good cases of a perfect run to facilitate error case overriding.
   */
  class TestContainer(initPromise: Option[Promise[Interval]] = None,
                      runPromises: Seq[Promise[(Interval, ActivationResponse)]] = Seq.empty,
                      apiKeyMustBePresent: Boolean = true)
      extends Container {
    protected[core] val id = ContainerId("testcontainer")
    protected val addr = ContainerAddress("0.0.0.0")
    protected implicit val logging: Logging = log
    protected implicit val ec: ExecutionContext = system.dispatcher
    override implicit protected val as: ActorSystem = system
    var suspendCount = 0
    var resumeCount = 0
    var destroyCount = 0
    var initializeCount = 0
    val atomicRunCount = new AtomicInteger(0) //need atomic tracking since we will test concurrent runs
    var atomicLogsCount = new AtomicInteger(0)

    def runCount = atomicRunCount.get()
    override def suspend()(implicit transid: TransactionId): Future[Unit] = {
      suspendCount += 1
      val s = super.suspend()
      Await.result(s, 5.seconds)
      //verify that httpconn is closed
      httpConnection should be(None)
      s
    }
    override def resume()(implicit transid: TransactionId): Future[Unit] = {
      resumeCount += 1
      val r = super.resume()
      Await.result(r, 5.seconds)
      //verify that httpconn is recreated
      httpConnection should be('defined)
      r
    }
    override def destroy()(implicit transid: TransactionId): Future[Unit] = {
      destroyCount += 1
      super.destroy()
    }
    override def initialize(initializer: JsObject, timeout: FiniteDuration, concurrent: Int)(
      implicit transid: TransactionId): Future[Interval] = {
      initializeCount += 1
      initializer shouldBe action.containerInitializer {
        activationArguments.fields.filter(k => filterEnvVar(k._1))
      }
      timeout shouldBe action.limits.timeout.duration

      initPromise.map(_.future).getOrElse(Future.successful(initInterval))
    }
    override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration, concurrent: Int)(
      implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {

      // the "init" arguments are not passed on run
      parameters shouldBe JsObject(activationArguments.fields.filter(k => !filterEnvVar(k._1)))

      val runCount = atomicRunCount.incrementAndGet()
      environment.fields("namespace") shouldBe invocationNamespace.name.toJson
      environment.fields("action_name") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      environment.fields("activation_id") shouldBe message.activationId.toJson
      environment.fields("transaction_id") shouldBe transid.id.toJson
      val authEnvironment = environment.fields.filterKeys(message.user.authkey.toEnvironment.fields.contains)
      if (apiKeyMustBePresent) {
        message.user.authkey.toEnvironment shouldBe authEnvironment.toJson.asJsObject
      } else {
        authEnvironment shouldBe empty
      }

      val deadline = Instant.ofEpochMilli(environment.fields("deadline").convertTo[String].toLong)
      val maxDeadline = Instant.now.plusMillis(timeout.toMillis)

      // The deadline should be in the future but must be smaller than or equal
      // a freshly computed deadline, as they get computed slightly after each other
      deadline should (be <= maxDeadline and be >= Instant.now)

      //return the future for this run (if runPromises no empty), or a default response
      runPromises
        .lift(runCount - 1)
        .map(_.future)
        .getOrElse(Future.successful((runInterval, ActivationResponse.success())))
    }
    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
      atomicLogsCount.incrementAndGet()
      Source.empty
    }
  }
}
